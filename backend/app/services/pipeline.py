"""Turns collected articles into study material with one model call per article."""

import logging
from dataclasses import dataclass

from sqlalchemy import select
from sqlalchemy.orm import Session

from app.ai import budget_exhausted, get_provider, record_usage
from app.ai.base import DIFFICULTY_SCORE, AIError, AIRefused, ArticleEnrichment
from app.ai.prompts import PROMPT_VERSION
from app.config import get_settings
from app.models import (
    CATEGORIES,
    Article,
    ArticleFact,
    ArticleWord,
    Question,
    Word,
    utcnow,
)
from app.services import collector
from app.services.textutil import truncate

log = logging.getLogger(__name__)

MAX_ATTEMPTS = 3
MIN_TEXT_CHARS = 80
# Below this a feed item is only a teaser; fetch the page if the source allows it.
FULL_TEXT_WANTED_BELOW = 600
MAX_VOCABULARY = 8
MAX_FACTS = 8
MAX_QUESTIONS = 3


@dataclass
class ProcessResult:
    ready: int = 0
    failed: int = 0
    skipped: int = 0
    stopped_for_budget: bool = False


def _article_text(article: Article) -> str:
    text = article.source_text or article.excerpt
    if article.source.fetch_full_text and len(text) < FULL_TEXT_WANTED_BELOW:
        fetched = collector.fetch_article_text(article.url)
        if len(fetched) > len(text):
            text = fetched
            article.source_text = fetched
    return text


def _valid_question(q) -> bool:
    options = [o.strip() for o in q.options]
    return (
        bool(q.question.strip())
        and len(options) == 4
        and all(options)
        and len({o.lower() for o in options}) == 4
        and 0 <= q.correct_index < 4
    )


def apply_enrichment(db: Session, article: Article, data: ArticleEnrichment, model: str) -> None:
    """Validate the model's output and store it, replacing any earlier result."""
    for link in article.words:
        link.word.occurrences = max(0, link.word.occurrences - 1)
    article.facts.clear()
    article.words.clear()
    article.questions.clear()
    db.flush()

    article.category = data.category if data.category in CATEGORIES else "other"
    article.summary = data.summary.strip()
    article.easy_summary = data.easy_summary.strip()
    article.bangla_summary = data.bangla_summary.strip()
    article.exam_importance = max(0, min(100, data.exam_importance))
    article.exam_reason = data.exam_reason.strip()

    seen: set[str] = set()
    for item in data.vocabulary:
        lemma = " ".join(item.word.lower().split())[:120]
        if not lemma or lemma in seen or not item.meaning_en.strip() or len(seen) >= MAX_VOCABULARY:
            continue
        seen.add(lemma)
        word = db.scalar(select(Word).where(Word.lemma == lemma))
        if word is None:
            word = Word(
                lemma=lemma,
                part_of_speech=item.part_of_speech.strip().lower()[:30],
                meaning_en=truncate(item.meaning_en, 500),
                meaning_bn=truncate(item.meaning_bn, 500),
                example_sentence=truncate(item.example_sentence, 600),
                difficulty=DIFFICULTY_SCORE.get(item.difficulty, 2),
                occurrences=0,
            )
            db.add(word)
            db.flush()
        word.occurrences += 1
        article.words.append(
            ArticleWord(
                word_id=word.id,
                position=len(seen),
                context_sentence=truncate(item.context_sentence, 600),
            )
        )

    for fact in data.facts[:MAX_FACTS]:
        if fact.text.strip():
            article.facts.append(
                ArticleFact(
                    kind=fact.kind,
                    text=truncate(fact.text, 500),
                    detail=truncate(fact.detail, 500),
                )
            )

    for q in [q for q in data.questions if _valid_question(q)][:MAX_QUESTIONS]:
        article.questions.append(
            Question(
                category=article.category,
                text=truncate(q.question, 600),
                options=[truncate(o, 300) for o in q.options],
                correct_index=q.correct_index,
                explanation=truncate(q.explanation, 800),
            )
        )

    article.status = "ready"
    article.error = None
    article.ai_model = model
    article.prompt_version = PROMPT_VERSION
    article.processed_at = utcnow()


def process_article(db: Session, article: Article) -> str:
    """Process one article and return its new status."""
    text = _article_text(article)
    if len(text) < MIN_TEXT_CHARS:
        article.status = "skipped"
        article.error = "too little text in feed"
        return article.status

    article.attempts += 1
    try:
        data, usage = get_provider().enrich_article(
            title=article.title, text=text, source=article.source.name
        )
    except AIRefused as e:
        article.status, article.error = "skipped", str(e)
        return article.status
    except AIError as e:
        article.error = str(e)
        if article.attempts >= MAX_ATTEMPTS:
            article.status = "failed"
        log.warning("article %s: %s", article.id, e)
        return "failed"

    record_usage(db, usage, purpose="enrich")
    if not data.summary.strip():
        article.status, article.error = "failed", "model returned an empty summary"
        return article.status
    apply_enrichment(db, article, data, usage.model)
    return article.status


def process_pending(db: Session, limit: int | None = None) -> ProcessResult:
    limit = limit or get_settings().ai_max_articles_per_run
    result = ProcessResult()
    # Newest first: if the budget runs out, today's news is what got processed.
    ids = db.scalars(
        select(Article.id)
        .where(Article.status == "pending", Article.attempts < MAX_ATTEMPTS)
        .order_by(Article.published_at.desc())
        .limit(limit)
    ).all()
    for article_id in ids:
        if budget_exhausted(db):
            result.stopped_for_budget = True
            log.warning("daily AI budget reached; processing resumes tomorrow")
            break
        article = db.get(Article, article_id)
        status = process_article(db, article)
        db.commit()
        if status == "ready":
            result.ready += 1
        elif status == "skipped":
            result.skipped += 1
        else:
            result.failed += 1
    return result


def reset_for_reprocessing(article: Article) -> None:
    article.status = "pending"
    article.attempts = 0
    article.error = None
