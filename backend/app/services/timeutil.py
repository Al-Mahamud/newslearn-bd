from datetime import UTC, date, datetime, time, timedelta

from app.config import get_settings


def local_today() -> date:
    return datetime.now(get_settings().tz).date()


def local_date(moment: datetime) -> date:
    return moment.astimezone(get_settings().tz).date()


def day_bounds_utc(start: date, days: int = 1) -> tuple[datetime, datetime]:
    """UTC instants covering `days` local calendar days beginning at `start`."""
    tz = get_settings().tz
    begin = datetime.combine(start, time.min, tzinfo=tz)
    return begin.astimezone(UTC), (begin + timedelta(days=days)).astimezone(UTC)


def week_start(day: date) -> date:
    """The Saturday on or before `day` (the Bangladeshi week runs Saturday to Friday)."""
    return day - timedelta(days=(day.weekday() - 5) % 7)
