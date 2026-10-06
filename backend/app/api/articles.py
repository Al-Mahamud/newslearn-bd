import base64
from datetime import datetime

from fastapi import APIRouter, HTTPException, Query, status
from sqlalchemy import and_, func, or_, select
from sqlalchemy.orm import Session

from app.deps import CurrentUser, DbSession, OptionalUser
from app.models import (
    CATEGORIES,
    EXAM_IMPORTANT_THRESHOLD,
    Article,
    ArticleWord,
    ReadingHistory,
    SavedArticle,
    Source,
    User,
    UserWord,
    Word,
    utcnow,
)
from app.schemas import (
    ArticleCard,
    ArticleDetail,
    ArticlePage,
    CategoryOut,
    FactOut,
    SearchResponse,
    WordOut,
)
from app.services.timeutil import local_date

router = APIRouter(tags=["articles"])


def encode_cursor(article: Article) -> str:
    raw = f"{article.published_at.isoformat()}|{article.id}"
    return base64.urlsafe_b64encode(raw.encode()).decode()


def decode_cursor(cursor: str) -> tuple[datetime, int]:
    try:
        stamp, article_id = base64.urlsafe_b64decode(cursor.encode()).decode().split("|")
        return datetime.fromisoformat(stamp), int(article_id)
    except (ValueError, UnicodeDecodeError) as e:
        raise HTTPException(status.HTTP_400_BAD_REQUEST, "Invalid cursor") from e


def user_word_states(db: Session, user: User | None, word_ids: list[int]) -> dict[int, bool]:
    """word id -> whether the reader marked it known, for the words they have any record of."""
    if user is None or not word_ids:
        return {}
    return dict(
        db.execute(
            select(UserWord.word_id, UserWord.known).where(
                UserWord.user_id == user.id, UserWord.word_id.in_(word_ids)
            )
        ).all()
    )


def word_out(word: Word, *, context: str = "", state: bool | None = None) -> WordOut:
    """`state` is the reader's record of the word: None = none, False = saved, True = known."""
    return WordOut(
        id=word.id,
        word=word.lemma,
        part_of_speech=word.part_of_speech,
        meaning_en=word.meaning_en,
        meaning_bn=word.meaning_bn,
        example_sentence=word.example_sentence,
        difficulty=word.difficulty,
        context_sentence=context,
        saved=state is False,
        known=state is True,
        synonyms=word.synonyms or [],
        seen_in=word.occurrences,
    )


def article_cards(db: Session, articles: list[Article], user: User | None) -> list[ArticleCard]:
    ids = [a.id for a in articles]
    if not ids:
        return []
    word_counts = dict(
        db.execute(
            select(ArticleWord.article_id, func.count())
            .where(ArticleWord.article_id.in_(ids))
            .group_by(ArticleWord.article_id)
        ).all()
    )
    saved: set[int] = set()
    read: set[int] = set()
    if user:
        saved = set(
            db.scalars(
                select(SavedArticle.article_id).where(
                    SavedArticle.user_id == user.id, SavedArticle.article_id.in_(ids)
                )
            )
        )
        read = set(
            db.scalars(
                select(ReadingHistory.article_id).where(
                    ReadingHistory.user_id == user.id, ReadingHistory.article_id.in_(ids)
                )
            )
        )
    return [
        ArticleCard(
            id=a.id,
            title=a.title,
            source=a.source.name,
            url=a.url,
            image_url=a.image_url,
            published_at=a.published_at,
            category=a.category,
            summary=a.summary,
            exam_importance=a.exam_importance,
            exam_important=a.exam_importance >= EXAM_IMPORTANT_THRESHOLD,
            word_count=word_counts.get(a.id, 0),
            saved=a.id in saved,
            read=a.id in read,
        )
        for a in articles
    ]


def get_ready_article(db: Session, article_id: int) -> Article:
    article = db.get(Article, article_id)
    if article is None or article.status != "ready":
        raise HTTPException(status.HTTP_404_NOT_FOUND, "Article not found")
    return article


def _search_filter(q: str):
    pattern = f"%{q.strip()}%"
    return or_(Article.title.ilike(pattern), Article.summary.ilike(pattern))


@router.get("/categories", response_model=list[CategoryOut])
def categories():
    return [CategoryOut(slug=slug, label=label) for slug, label in CATEGORIES.items()]


@router.get("/articles", response_model=ArticlePage)
def list_articles(
    db: DbSession,
    user: OptionalUser,
    category: str | None = None,
    exam: bool = Query(False, description="Only exam-important news"),
    source: str | None = Query(None, description="Source slug"),
    q: str | None = Query(None, min_length=2, max_length=100),
    saved: bool = Query(False, description="Only articles you saved (requires sign-in)"),
    for_you: bool = Query(False, description="Only your preferred categories"),
    cursor: str | None = None,
    limit: int = Query(20, ge=1, le=50),
):
    query = select(Article).where(Article.status == "ready")
    if category:
        if category not in CATEGORIES:
            raise HTTPException(status.HTTP_400_BAD_REQUEST, "Unknown category")
        query = query.where(Article.category == category)
    if exam:
        query = query.where(Article.exam_importance >= EXAM_IMPORTANT_THRESHOLD)
    if source:
        query = query.join(Source, Article.source_id == Source.id).where(Source.slug == source)
    if q:
        query = query.where(_search_filter(q))
    if saved:
        if user is None:
            raise HTTPException(status.HTTP_401_UNAUTHORIZED, "Authentication required")
        query = query.join(SavedArticle, SavedArticle.article_id == Article.id).where(
            SavedArticle.user_id == user.id
        )
    if for_you and user and user.preferred_categories:
        query = query.where(Article.category.in_(user.preferred_categories))
    if cursor:
        published_at, last_id = decode_cursor(cursor)
        query = query.where(
            or_(
                Article.published_at < published_at,
                and_(Article.published_at == published_at, Article.id < last_id),
            )
        )
    rows = db.scalars(
        query.order_by(Article.published_at.desc(), Article.id.desc()).limit(limit + 1)
    ).all()
    page = list(rows[:limit])
    return ArticlePage(
        items=article_cards(db, page, user),
        next_cursor=encode_cursor(page[-1]) if len(rows) > limit else None,
    )


@router.get("/articles/{article_id}", response_model=ArticleDetail)
def get_article(article_id: int, db: DbSession, user: OptionalUser):
    article = get_ready_article(db, article_id)
    card = article_cards(db, [article], user)[0]

    links = list(article.words)
    # Advanced readers are not shown the easiest words, unless that leaves too few.
    if user and user.english_level == "advanced":
        harder = [link for link in links if link.word.difficulty >= 2]
        if len(harder) >= 3:
            links = harder
    states = user_word_states(db, user, [link.word_id for link in links])
    return ArticleDetail(
        **card.model_dump(),
        author=article.author,
        easy_summary=article.easy_summary,
        bangla_summary=article.bangla_summary,
        exam_reason=article.exam_reason,
        vocabulary=[
            word_out(link.word, context=link.context_sentence, state=states.get(link.word_id))
            for link in links
        ],
        facts=[FactOut.model_validate(f) for f in article.facts],
        question_count=len(article.questions),
    )


@router.post("/articles/{article_id}/read", status_code=status.HTTP_204_NO_CONTENT)
def mark_read(article_id: int, db: DbSession, user: CurrentUser):
    get_ready_article(db, article_id)
    if db.get(ReadingHistory, (user.id, article_id)) is None:
        now = utcnow()
        db.add(
            ReadingHistory(
                user_id=user.id, article_id=article_id, read_at=now, read_on=local_date(now)
            )
        )
        db.commit()


@router.put("/articles/{article_id}/save", status_code=status.HTTP_204_NO_CONTENT)
def save_article(article_id: int, db: DbSession, user: CurrentUser):
    get_ready_article(db, article_id)
    if db.get(SavedArticle, (user.id, article_id)) is None:
        db.add(SavedArticle(user_id=user.id, article_id=article_id))
        db.commit()


@router.delete("/articles/{article_id}/save", status_code=status.HTTP_204_NO_CONTENT)
def unsave_article(article_id: int, db: DbSession, user: CurrentUser):
    entry = db.get(SavedArticle, (user.id, article_id))
    if entry:
        db.delete(entry)
        db.commit()


@router.get("/search", response_model=SearchResponse)
def search(
    db: DbSession,
    user: OptionalUser,
    q: str = Query(min_length=2, max_length=100),
    limit: int = Query(15, ge=1, le=30),
):
    """Search article titles and summaries, and the vocabulary dictionary."""
    articles = db.scalars(
        select(Article)
        .where(Article.status == "ready", _search_filter(q))
        .order_by(Article.published_at.desc())
        .limit(limit)
    ).all()
    term = q.strip().lower()
    words = db.scalars(
        select(Word)
        .where(or_(Word.lemma.ilike(f"{term}%"), Word.meaning_en.ilike(f"%{term}%")))
        .order_by(Word.occurrences.desc(), Word.lemma)
        .limit(limit)
    ).all()
    states = user_word_states(db, user, [w.id for w in words])
    return SearchResponse(
        articles=article_cards(db, list(articles), user),
        words=[word_out(w, state=states.get(w.id)) for w in words],
    )
