import logging
from contextlib import asynccontextmanager

from fastapi import APIRouter, FastAPI
from fastapi.middleware.cors import CORSMiddleware
from sqlalchemy import text

from app import scheduler
from app.api import admin, articles, auth, learn, progress, quiz, vocabulary
from app.config import get_settings
from app.deps import DbSession

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")


@asynccontextmanager
async def lifespan(app: FastAPI):
    settings = get_settings()
    settings.validate_for_runtime()
    running = scheduler.start() if settings.scheduler_enabled else None
    yield
    if running:
        running.shutdown(wait=False)


app = FastAPI(
    title="NewsLearn BD API",
    version="0.1.0",
    description="Daily news turned into summaries, vocabulary, current-affairs facts and quizzes.",
    lifespan=lifespan,
)

origins = [o.strip() for o in get_settings().cors_origins.split(",") if o.strip()]
if origins:
    app.add_middleware(
        CORSMiddleware, allow_origins=origins, allow_methods=["*"], allow_headers=["*"]
    )

api = APIRouter(prefix="/api/v1")
for module in (auth, articles, vocabulary, learn, quiz, progress, admin):
    api.include_router(module.router)
app.include_router(api)


@app.get("/health", tags=["system"])
def health(db: DbSession):
    db.execute(text("SELECT 1"))
    return {"status": "ok"}
