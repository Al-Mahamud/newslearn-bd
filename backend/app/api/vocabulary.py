from typing import Literal

from fastapi import APIRouter, HTTPException, Query, status
from sqlalchemy import select

from app.api.articles import word_out
from app.deps import CurrentUser, DbSession
from app.models import ArticleWord, UserWord, Word, utcnow
from app.schemas import ReviewRequest, SaveWordRequest, UserWordOut
from app.services import srs

router = APIRouter(tags=["vocabulary"])


def _out(entry: UserWord, context: str = "") -> UserWordOut:
    return UserWordOut(
        word=word_out(entry.word, context=context, saved=True),
        article_id=entry.article_id,
        saved_at=entry.saved_at,
        box=entry.box,
        learned=srs.is_learned(entry),
        due_at=entry.due_at,
        review_count=entry.review_count,
    )


def _with_context(db, entries: list[UserWord]) -> list[UserWordOut]:
    """Attach the sentence each word was saved from, when the article still has it."""
    pairs = {(e.article_id, e.word_id) for e in entries if e.article_id}
    contexts: dict[tuple[int, int], str] = {}
    if pairs:
        rows = db.execute(
            select(ArticleWord.article_id, ArticleWord.word_id, ArticleWord.context_sentence).where(
                ArticleWord.article_id.in_({a for a, _ in pairs}),
                ArticleWord.word_id.in_({w for _, w in pairs}),
            )
        )
        contexts = {(a, w): c for a, w, c in rows}
    return [_out(e, contexts.get((e.article_id, e.word_id), "")) for e in entries]


@router.get("/vocabulary", response_model=list[UserWordOut])
def my_words(
    db: DbSession,
    user: CurrentUser,
    status_filter: Literal["all", "learning", "learned"] = Query("all", alias="status"),
    limit: int = Query(100, ge=1, le=500),
    offset: int = Query(0, ge=0),
):
    query = select(UserWord).where(UserWord.user_id == user.id)
    if status_filter == "learning":
        query = query.where(UserWord.box < srs.LEARNED_BOX)
    elif status_filter == "learned":
        query = query.where(UserWord.box >= srs.LEARNED_BOX)
    entries = db.scalars(query.order_by(UserWord.saved_at.desc()).limit(limit).offset(offset)).all()
    return _with_context(db, list(entries))


@router.get("/vocabulary/review", response_model=list[UserWordOut])
def due_for_review(db: DbSession, user: CurrentUser, limit: int = Query(20, ge=1, le=100)):
    """Saved words whose review is due, longest-overdue first."""
    entries = db.scalars(
        select(UserWord)
        .where(UserWord.user_id == user.id, UserWord.due_at <= utcnow())
        .order_by(UserWord.due_at)
        .limit(limit)
    ).all()
    return _with_context(db, list(entries))


@router.put("/vocabulary/{word_id}", response_model=UserWordOut)
def save_word(word_id: int, db: DbSession, user: CurrentUser, body: SaveWordRequest | None = None):
    if db.get(Word, word_id) is None:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "Word not found")
    entry = db.get(UserWord, (user.id, word_id))
    if entry is None:
        article_id = body.article_id if body else None
        if article_id and db.get(ArticleWord, (article_id, word_id)) is None:
            article_id = None
        entry = UserWord(user_id=user.id, word_id=word_id, article_id=article_id)
        db.add(entry)
        db.commit()
        db.refresh(entry)
    return _with_context(db, [entry])[0]


@router.delete("/vocabulary/{word_id}", status_code=status.HTTP_204_NO_CONTENT)
def remove_word(word_id: int, db: DbSession, user: CurrentUser):
    entry = db.get(UserWord, (user.id, word_id))
    if entry:
        db.delete(entry)
        db.commit()


@router.post("/vocabulary/{word_id}/review", response_model=UserWordOut)
def review_word(word_id: int, body: ReviewRequest, db: DbSession, user: CurrentUser):
    entry = db.get(UserWord, (user.id, word_id))
    if entry is None:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "Word is not in your vocabulary")
    srs.apply_review(entry, body.remembered, utcnow())
    db.commit()
    return _with_context(db, [entry])[0]
