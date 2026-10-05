"""In-process job scheduler.

Runs inside the API process, so run exactly one API worker (the default). To scale out,
set SCHEDULER_ENABLED=false on the API and run `python -m app.cli worker` once instead.
"""

import logging

from apscheduler.schedulers.background import BackgroundScheduler
from apscheduler.schedulers.base import BaseScheduler

from app.config import get_settings
from app.db import session_scope
from app.services import collector, maintenance, pipeline
from app.services import quiz as quiz_service
from app.services.timeutil import local_today

log = logging.getLogger(__name__)


def collect_job() -> None:
    with session_scope() as db:
        collector.collect_all(db)


def process_job() -> None:
    with session_scope() as db:
        result = pipeline.process_pending(db)
        if result.ready or result.failed or result.skipped:
            log.info("processed: %s", result)


def daily_job() -> None:
    with session_scope() as db:
        maintenance.cleanup(db)
        quiz_service.get_or_create_daily(db, local_today())


def configure(scheduler: BaseScheduler) -> BaseScheduler:
    settings = get_settings()
    common = {"coalesce": True, "max_instances": 1, "misfire_grace_time": 300}
    scheduler.add_job(
        collect_job, "interval", minutes=settings.collect_interval_minutes, id="collect", **common
    )
    scheduler.add_job(
        process_job, "interval", minutes=settings.process_interval_minutes, id="process", **common
    )
    # Evening: the day's news is in, so the daily quiz has a full set to draw on.
    scheduler.add_job(daily_job, "cron", hour=20, minute=0, id="daily", **common)
    return scheduler


def start() -> BackgroundScheduler:
    scheduler = configure(BackgroundScheduler(timezone=get_settings().tz))
    scheduler.start()
    # Catch up straight away rather than waiting a full interval after a restart.
    scheduler.add_job(collect_job, id="collect-on-start", max_instances=1)
    log.info("scheduler started")
    return scheduler
