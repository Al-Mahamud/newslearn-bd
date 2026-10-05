from datetime import date, datetime

from sqlalchemy import func, select
from sqlalchemy.orm import Session

from app.ai.base import AIProvider, AIUsageInfo
from app.config import get_settings
from app.models import AIUsage

_provider: AIProvider | None = None


def get_provider() -> AIProvider:
    global _provider
    if _provider is None:
        settings = get_settings()
        if settings.ai_provider == "claude":
            from app.ai.claude import ClaudeProvider

            _provider = ClaudeProvider(
                api_key=settings.anthropic_api_key,
                model=settings.resolved_ai_model,
                effort=settings.ai_effort,
            )
        elif settings.ai_provider == "gemini":
            from app.ai.gemini import GeminiProvider

            _provider = GeminiProvider(
                api_key=settings.gemini_api_key, model=settings.resolved_ai_model
            )
        elif settings.ai_provider == "mock":
            from app.ai.mock import MockProvider

            _provider = MockProvider()
        else:
            raise RuntimeError(f"Unknown AI_PROVIDER: {settings.ai_provider!r}")
    return _provider


def set_provider(provider: AIProvider | None) -> None:
    """Swap the provider (tests) or clear the cached one."""
    global _provider
    _provider = provider


def local_today() -> date:
    return datetime.now(get_settings().tz).date()


def record_usage(
    db: Session, usage: AIUsageInfo, *, purpose: str, user_id: int | None = None
) -> None:
    db.add(
        AIUsage(
            day=local_today(),
            purpose=purpose,
            user_id=user_id,
            model=usage.model,
            input_tokens=usage.input_tokens,
            output_tokens=usage.output_tokens,
            cost_usd=usage.cost_usd,
        )
    )


def spent_today(db: Session) -> float:
    total = db.scalar(select(func.sum(AIUsage.cost_usd)).where(AIUsage.day == local_today()))
    return float(total or 0.0)


def budget_exhausted(db: Session) -> bool:
    return spent_today(db) >= get_settings().ai_daily_budget_usd


def user_calls_today(db: Session, user_id: int, purpose: str) -> int:
    return (
        db.scalar(
            select(func.count())
            .select_from(AIUsage)
            .where(
                AIUsage.user_id == user_id,
                AIUsage.day == local_today(),
                AIUsage.purpose == purpose,
            )
        )
        or 0
    )
