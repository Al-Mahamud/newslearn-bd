from datetime import date, timedelta

from fastapi import APIRouter, HTTPException, Query, status
from sqlalchemy import select

from app.ai import budget_exhausted, get_provider, record_usage, user_calls_today
from app.ai.base import AIError, AIRateLimited, AIRefused
from app.api.articles import get_ready_article
from app.config import get_settings
from app.deps import CurrentUser, DbSession
from app.models import CATEGORIES, Article, SentenceExplanation
from app.schemas import (
    DigestArticle,
    DigestGroup,
    DigestResponse,
    ExplainRequest,
    ExplainResponse,
    FactOut,
    TutorRequest,
    TutorResponse,
)
from app.services.textutil import sha256
from app.services.timeutil import day_bounds_utc, local_today, week_start

router = APIRouter(tags=["learning"])

WEEKLY_DIGEST_ARTICLES = 40
FACT_KINDS = ("number", "organization", "person", "place", "date", "fact")


def _check_ai_allowed(db, user, purpose: str, limit: int) -> None:
    if user_calls_today(db, user.id, purpose) >= limit:
        raise HTTPException(
            status.HTTP_429_TOO_MANY_REQUESTS, "Daily limit reached for this feature"
        )
    if budget_exhausted(db):
        raise HTTPException(
            status.HTTP_503_SERVICE_UNAVAILABLE, "AI features are paused until tomorrow"
        )


def _ai_failure(error: AIError) -> HTTPException:
    if isinstance(error, AIRefused):
        return HTTPException(422, "This text could not be explained")
    if isinstance(error, AIRateLimited):
        return HTTPException(
            status.HTTP_503_SERVICE_UNAVAILABLE, "The AI is busy. Try again in a minute."
        )
    return HTTPException(
        status.HTTP_502_BAD_GATEWAY, "The AI service is unavailable. Please try again."
    )


@router.post("/explain", response_model=ExplainResponse)
def explain_sentence(body: ExplainRequest, db: DbSession, user: CurrentUser):
    """Explain a sentence in simple English and Bangla. Identical requests are cached."""
    sentence = " ".join(body.sentence.split())
    level = user.english_level
    text_hash = sha256(sentence.lower())

    cached = db.scalar(
        select(SentenceExplanation).where(
            SentenceExplanation.text_hash == text_hash, SentenceExplanation.level == level
        )
    )
    if cached:
        return ExplainResponse(sentence=sentence, cached=True, **cached.result)

    _check_ai_allowed(db, user, "explain", get_settings().explain_daily_limit)
    context = ""
    if body.article_id:
        article = db.get(Article, body.article_id)
        if article:
            context = f"{article.title}. {article.summary}"
    try:
        result, usage = get_provider().explain_sentence(
            sentence=sentence, context=context, level=level
        )
    except AIError as e:
        raise _ai_failure(e) from e

    record_usage(db, usage, purpose="explain", user_id=user.id)
    data = result.model_dump()
    db.add(SentenceExplanation(text_hash=text_hash, level=level, sentence=sentence, result=data))
    db.commit()
    return ExplainResponse(sentence=sentence, cached=False, **data)


@router.post("/articles/{article_id}/ask", response_model=TutorResponse)
def ask_tutor(article_id: int, body: TutorRequest, db: DbSession, user: CurrentUser):
    """Ask the AI tutor a question about one article."""
    article = get_ready_article(db, article_id)
    _check_ai_allowed(db, user, "tutor", get_settings().tutor_daily_limit)

    notes = [
        f"Title: {article.title}",
        f"Publisher: {article.source.name}, {article.published_at:%d %B %Y}",
        f"Summary: {article.summary}",
        f"Easy summary: {article.easy_summary}",
        f"Why it matters for exams: {article.exam_reason}",
    ]
    if article.excerpt:
        notes.append(f"Publisher's excerpt: {article.excerpt}")
    notes += [f"Fact ({f.kind}): {f.text} — {f.detail}".rstrip(" —") for f in article.facts]
    notes += [f"Vocabulary: {w.word.lemma} = {w.word.meaning_en}" for w in article.words]

    history = [turn.model_dump() for turn in body.history]
    # The provider needs strictly alternating turns starting with the reader.
    while history and history[0]["role"] != "user":
        history.pop(0)
    if history and history[-1]["role"] != "assistant":
        history.pop()
    alternating = all(
        turn["role"] == ("user", "assistant")[i % 2] for i, turn in enumerate(history)
    )
    if not alternating:
        raise HTTPException(422, "History must alternate user and assistant")
    try:
        answer, usage = get_provider().answer_question(
            material="\n".join(notes),
            question=body.question.strip(),
            history=history,
            level=user.english_level,
        )
    except AIError as e:
        raise _ai_failure(e) from e
    record_usage(db, usage, purpose="tutor", user_id=user.id)
    db.commit()
    return TutorResponse(answer=answer)


def _digest(db, *, period: str, start: date, days: int, limit: int | None) -> DigestResponse:
    begin, end = day_bounds_utc(start, days)
    query = (
        select(Article)
        .where(Article.status == "ready", Article.published_at >= begin, Article.published_at < end)
        .order_by(Article.exam_importance.desc(), Article.published_at.desc())
    )
    if limit:
        query = query.limit(limit)
    articles = db.scalars(query).unique().all()

    groups: dict[str, list[DigestArticle]] = {}
    revision: dict[str, list[FactOut]] = {kind: [] for kind in FACT_KINDS}
    seen_facts: set[tuple[str, str]] = set()
    for article in articles:
        facts = [FactOut.model_validate(f) for f in article.facts]
        groups.setdefault(article.category, []).append(
            DigestArticle(
                id=article.id,
                title=article.title,
                source=article.source.name,
                summary=article.summary,
                exam_importance=article.exam_importance,
                exam_reason=article.exam_reason,
                facts=facts,
            )
        )
        for fact in facts:
            key = (fact.kind, fact.text.lower())
            if fact.kind in revision and key not in seen_facts:
                seen_facts.add(key)
                revision[fact.kind].append(fact)

    return DigestResponse(
        period=period,
        start=start,
        end=start + timedelta(days=days - 1),
        article_count=len(articles),
        groups=[
            DigestGroup(category=slug, label=label, articles=groups[slug])
            for slug, label in CATEGORIES.items()
            if slug in groups
        ],
        revision={kind: facts for kind, facts in revision.items() if facts},
    )


@router.get("/current-affairs/daily", response_model=DigestResponse)
def daily_current_affairs(
    db: DbSession, day: date | None = Query(None, alias="date", description="Defaults to today")
):
    """One day's news grouped by topic, most exam-relevant first, with facts to revise."""
    return _digest(db, period="daily", start=day or local_today(), days=1, limit=None)


@router.get("/current-affairs/weekly", response_model=DigestResponse)
def weekly_current_affairs(
    db: DbSession,
    day: date | None = Query(None, alias="date", description="Any day in the week wanted"),
):
    """The week's most exam-relevant news (Saturday to Friday)."""
    return _digest(
        db,
        period="weekly",
        start=week_start(day or local_today()),
        days=7,
        limit=WEEKLY_DIGEST_ARTICLES,
    )
