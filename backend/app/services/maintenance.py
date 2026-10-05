import logging
from datetime import timedelta

from sqlalchemy import delete, select, update
from sqlalchemy.orm import Session

from app.config import get_settings
from app.models import Article, RefreshToken, SavedArticle, UserWord, utcnow

log = logging.getLogger(__name__)

# Feed text is kept this long so a failed or outdated result can be reprocessed.
SOURCE_TEXT_RETENTION_DAYS = 7


def cleanup(db: Session) -> dict[str, int]:
    now = utcnow()
    cleared = db.execute(
        update(Article)
        .where(
            Article.source_text != "",
            Article.created_at < now - timedelta(days=SOURCE_TEXT_RETENTION_DAYS),
        )
        .values(source_text="")
    ).rowcount

    # Old articles go unless a reader saved them or saved a word from them.
    cutoff = now - timedelta(days=get_settings().article_retention_days)
    kept = select(SavedArticle.article_id).union(
        select(UserWord.article_id).where(UserWord.article_id.is_not(None)),
        select(Article.duplicate_of_id).where(Article.duplicate_of_id.is_not(None)),
    )
    old_ids = db.scalars(
        select(Article.id).where(Article.published_at < cutoff, Article.id.not_in(kept))
    ).all()
    for article in db.scalars(select(Article).where(Article.id.in_(old_ids))):
        db.delete(article)  # ORM delete so facts, word links and questions go with it

    tokens = db.execute(
        delete(RefreshToken).where(RefreshToken.expires_at < now - timedelta(days=1))
    ).rowcount
    db.commit()
    result = {"source_text_cleared": cleared, "articles_deleted": len(old_ids), "tokens": tokens}
    log.info("cleanup: %s", result)
    return result
