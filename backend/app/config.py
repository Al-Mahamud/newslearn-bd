from functools import lru_cache
from zoneinfo import ZoneInfo

from pydantic import field_validator
from pydantic_settings import BaseSettings, SettingsConfigDict

DEV_JWT_SECRET = "dev-only-secret-change-me-before-deploying"


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    environment: str = "development"
    database_url: str = "sqlite:///./newslearn.db"

    @field_validator("database_url")
    @classmethod
    def _use_psycopg3(cls, url: str) -> str:
        # Hosting providers hand out postgres:// or postgresql:// URLs; name the driver we ship.
        for prefix in ("postgres://", "postgresql://"):
            if url.startswith(prefix):
                return "postgresql+psycopg://" + url[len(prefix) :]
        return url

    jwt_secret: str = DEV_JWT_SECRET
    access_token_minutes: int = 30
    refresh_token_days: int = 60
    # Set to false once your own account exists to stop anyone else signing up.
    registration_open: bool = True

    ai_provider: str = "mock"  # "mock" | "gemini" | "claude"
    gemini_api_key: str = ""
    anthropic_api_key: str = ""
    # Empty means the provider's default (see resolved_ai_model).
    ai_model: str = ""
    ai_effort: str = "low"
    ai_daily_budget_usd: float = 1.0
    ai_max_articles_per_run: int = 15

    scheduler_enabled: bool = True
    collect_interval_minutes: int = 30
    process_interval_minutes: int = 10
    article_max_age_hours: int = 72
    article_retention_days: int = 90
    http_user_agent: str = "NewsLearnBD/0.1 (personal study app)"

    explain_daily_limit: int = 60
    tutor_daily_limit: int = 40

    cors_origins: str = ""

    # "Today" for digests, quizzes, streaks and budgets follows the reader's clock.
    timezone: str = "Asia/Dhaka"

    @property
    def tz(self) -> ZoneInfo:
        return ZoneInfo(self.timezone)

    @property
    def resolved_ai_model(self) -> str | None:
        defaults = {"gemini": "gemini-3.8-flash", "claude": "claude-opus-5-5"}
        return self.ai_model or defaults.get(self.ai_provider)

    @property
    def is_production(self) -> bool:
        return self.environment == "production"

    def validate_for_runtime(self) -> None:
        if self.is_production and self.jwt_secret == DEV_JWT_SECRET:
            raise RuntimeError("JWT_SECRET must be set to a private value in production")


@lru_cache
def get_settings() -> Settings:
    return Settings()
