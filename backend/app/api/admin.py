from datetime import timedelta

from fastapi import APIRouter, HTTPException, Query, status
from sqlalchemy import func, select

from app.ai import local_today, spent_today
from app.config import get_settings
from app.deps import AdminUser, DbSession
from app.models import CATEGORIES, AIUsage, Article, Source
from app.schemas import SourceCreate, SourceOut, SourceUpdate
from app.services import collector, maintenance, pipeline

router = APIRouter(prefix="/admin", tags=["admin"])


def _check_category(category: str | None) -> None:
    if category is not None and category not in CATEGORIES:
        raise HTTPException(status.HTTP_400_BAD_REQUEST, "Unknown category")


@router.get("/sources", response_model=list[SourceOut])
def list_sources(db: DbSession, admin: AdminUser):
    return db.scalars(select(Source).order_by(Source.id)).all()


@router.post("/sources", response_model=SourceOut, status_code=status.HTTP_201_CREATED)
def create_source(body: SourceCreate, db: DbSession, admin: AdminUser):
    _check_category(body.default_category)
    if db.scalar(select(Source.id).where(Source.slug == body.slug)):
        raise HTTPException(status.HTTP_409_CONFLICT, "A source with this slug already exists")
    source = Source(**body.model_dump())
    db.add(source)
    db.commit()
    return source


@router.patch("/sources/{source_id}", response_model=SourceOut)
def update_source(source_id: int, body: SourceUpdate, db: DbSession, admin: AdminUser):
    source = db.get(Source, source_id)
    if source is None:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "Source not found")
    changes = body.model_dump(exclude_unset=True)
    _check_category(changes.get("default_category"))
    if "feed_url" in changes:
        # The cached validators belong to the old feed.
        source.etag = source.last_modified = None
    for field, value in changes.items():
        setattr(source, field, value)
    db.commit()
    return source


@router.post("/collect")
def run_collection(db: DbSession, admin: AdminUser):
    """Fetch every enabled feed now."""
    return [vars(r) for r in collector.collect_all(db)]


@router.post("/process")
def run_processing(db: DbSession, admin: AdminUser, limit: int = Query(5, ge=1, le=50)):
    """Run AI processing on pending articles now."""
    return vars(pipeline.process_pending(db, limit))


@router.post("/articles/{article_id}/reprocess", status_code=status.HTTP_202_ACCEPTED)
def reprocess_article(article_id: int, db: DbSession, admin: AdminUser):
    article = db.get(Article, article_id)
    if article is None:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "Article not found")
    if article.duplicate_of_id:
        raise HTTPException(status.HTTP_409_CONFLICT, "This article is a duplicate")
    pipeline.reset_for_reprocessing(article)
    db.commit()
    return {"id": article.id, "status": article.status}


@router.post("/cleanup")
def run_cleanup(db: DbSession, admin: AdminUser):
    return maintenance.cleanup(db)


@router.get("/status")
def pipeline_status(db: DbSession, admin: AdminUser):
    """Article counts by status and AI spend for the last 7 days."""
    settings = get_settings()
    by_status = dict(
        db.execute(select(Article.status, func.count()).group_by(Article.status)).all()
    )
    usage = db.execute(
        select(
            AIUsage.day,
            func.count(),
            func.sum(AIUsage.input_tokens),
            func.sum(AIUsage.output_tokens),
            func.sum(AIUsage.cost_usd),
        )
        .where(AIUsage.day >= local_today() - timedelta(days=6))
        .group_by(AIUsage.day)
        .order_by(AIUsage.day.desc())
    ).all()
    return {
        "ai_provider": settings.ai_provider,
        "ai_model": settings.resolved_ai_model,
        "articles": by_status,
        "budget_usd": settings.ai_daily_budget_usd,
        "spent_today_usd": round(spent_today(db), 4),
        "usage": [
            {
                "day": day,
                "calls": calls,
                "input_tokens": int(inp or 0),
                "output_tokens": int(out or 0),
                "cost_usd": round(float(cost or 0), 4),
            }
            for day, calls, inp, out, cost in usage
        ],
    }
