"""Collects article metadata from publisher RSS feeds.

Only what the publisher puts in its feed is stored. Article pages are fetched only for
sources with `fetch_full_text` enabled, only when robots.txt allows it, and that text is
used for AI processing and then discarded (see pipeline.py and maintenance.py).
"""

import calendar
import logging
import time
from dataclasses import dataclass
from datetime import UTC, datetime, timedelta
from urllib.parse import urlsplit
from urllib.robotparser import RobotFileParser

import feedparser
import httpx
from sqlalchemy import select
from sqlalchemy.orm import Session

from app.config import get_settings
from app.models import Article, Source, utcnow
from app.services.textutil import (
    canonical_url,
    clean_text,
    html_to_paragraphs,
    sha256,
    title_similarity,
    title_tokens,
    truncate,
)

log = logging.getLogger(__name__)

DUPLICATE_THRESHOLD = 0.75
DUPLICATE_WINDOW_HOURS = 72
EXCERPT_CHARS = 400
# Far above any real news article; guards against a feed embedding something enormous.
MAX_SOURCE_TEXT_CHARS = 40_000


@dataclass
class FeedItem:
    title: str
    url: str
    published_at: datetime
    excerpt: str
    body: str
    image_url: str | None
    author: str | None


@dataclass
class CollectResult:
    source: str
    new: int = 0
    duplicates: int = 0
    skipped_old: int = 0
    status: str = "ok"


def _entry_datetime(entry) -> datetime | None:
    for key in ("published_parsed", "updated_parsed"):
        parsed = entry.get(key)
        if parsed:
            return datetime.fromtimestamp(calendar.timegm(parsed), tz=UTC)
    return None


def _entry_image(entry) -> str | None:
    for key in ("media_content", "media_thumbnail"):
        for media in entry.get(key) or []:
            if media.get("url"):
                return media["url"]
    for link in entry.get("links") or []:
        if link.get("rel") == "enclosure" and str(link.get("type", "")).startswith("image"):
            return link.get("href")
    return None


def parse_feed(content: bytes) -> list[FeedItem]:
    parsed = feedparser.parse(content)
    items: list[FeedItem] = []
    for entry in parsed.entries:
        title = clean_text(entry.get("title"))
        link = entry.get("link")
        if not title or not link:
            continue
        summary = clean_text(entry.get("summary"))
        body = "\n\n".join(
            html_to_paragraphs(c.get("value")) for c in entry.get("content") or []
        ).strip()
        items.append(
            FeedItem(
                title=truncate(title, 500),
                url=canonical_url(link),
                published_at=_entry_datetime(entry) or utcnow(),
                excerpt=truncate(summary or body, EXCERPT_CHARS),
                body=(body or summary)[:MAX_SOURCE_TEXT_CHARS],
                image_url=_entry_image(entry),
                author=truncate(clean_text(entry.get("author")), 200) or None,
            )
        )
    return items


def store_items(db: Session, source: Source, items: list[FeedItem]) -> CollectResult:
    settings = get_settings()
    result = CollectResult(source=source.slug)
    now = utcnow()
    oldest_allowed = now - timedelta(hours=settings.article_max_age_hours)

    hashes = {sha256(i.url): i for i in items}
    known = set(
        db.scalars(select(Article.url_hash).where(Article.url_hash.in_(hashes.keys()))).all()
    )
    recent = [
        (article_id, title_tokens(title))
        for article_id, title in db.execute(
            select(Article.id, Article.title).where(
                Article.published_at >= now - timedelta(hours=DUPLICATE_WINDOW_HOURS),
                Article.duplicate_of_id.is_(None),
            )
        )
    ]

    for url_hash, item in hashes.items():
        if url_hash in known:
            continue
        # Some feeds serve years-old items, and some stamp items in the future.
        if item.published_at < oldest_allowed:
            result.skipped_old += 1
            continue
        published_at = min(item.published_at, now)

        tokens = title_tokens(item.title)
        duplicate_of = next(
            (
                aid
                for aid, other in recent
                if title_similarity(tokens, other) >= DUPLICATE_THRESHOLD
            ),
            None,
        )
        article = Article(
            source_id=source.id,
            title=item.title,
            url=item.url,
            url_hash=url_hash,
            excerpt=item.excerpt,
            source_text="" if duplicate_of else item.body,
            image_url=item.image_url,
            author=item.author,
            published_at=published_at,
            category=source.default_category or "other",
            status="skipped" if duplicate_of else "pending",
            duplicate_of_id=duplicate_of,
            error="duplicate story" if duplicate_of else None,
        )
        db.add(article)
        db.flush()
        if duplicate_of:
            result.duplicates += 1
        else:
            result.new += 1
            recent.append((article.id, tokens))
    return result


def collect_source(db: Session, source: Source, client: httpx.Client) -> CollectResult:
    headers = {}
    if source.etag:
        headers["If-None-Match"] = source.etag
    if source.last_modified:
        headers["If-Modified-Since"] = source.last_modified

    source.last_fetched_at = utcnow()
    try:
        response = client.get(source.feed_url, headers=headers)
    except httpx.HTTPError as e:
        source.last_status = f"error: {type(e).__name__}"
        return CollectResult(source=source.slug, status=source.last_status)

    if response.status_code == 304:
        source.last_status = "not modified"
        return CollectResult(source=source.slug, status=source.last_status)
    if response.status_code != 200:
        source.last_status = f"error: HTTP {response.status_code}"
        return CollectResult(source=source.slug, status=source.last_status)

    items = parse_feed(response.content)
    if not items:
        source.last_status = "error: no entries in feed"
        return CollectResult(source=source.slug, status=source.last_status)

    result = store_items(db, source, items)
    source.etag = response.headers.get("etag")
    source.last_modified = response.headers.get("last-modified")
    source.last_status = f"ok: {result.new} new, {result.duplicates} duplicate"
    return result


def collect_all(db: Session) -> list[CollectResult]:
    settings = get_settings()
    results = []
    with httpx.Client(
        headers={"User-Agent": settings.http_user_agent}, follow_redirects=True, timeout=20
    ) as client:
        for source in db.scalars(select(Source).where(Source.enabled).order_by(Source.id)):
            result = collect_source(db, source, client)
            db.commit()
            log.info("collected %s: %s", source.slug, source.last_status)
            results.append(result)
    return results


_robots_cache: dict[str, RobotFileParser | None] = {}
# When each site was last asked for an article page, to space requests out.
_last_page_fetch: dict[str, float] = {}
DEFAULT_PAGE_DELAY_SECONDS = 2.0
MAX_PAGE_DELAY_SECONDS = 15.0


def _wait_politely(url: str, robots: RobotFileParser, user_agent: str) -> None:
    """Honour the site's Crawl-delay (or a modest default) between article pages."""
    host = urlsplit(url).netloc
    delay = float(robots.crawl_delay(user_agent) or DEFAULT_PAGE_DELAY_SECONDS)
    delay = min(delay, MAX_PAGE_DELAY_SECONDS)
    last = _last_page_fetch.get(host)
    if last is not None:
        remaining = delay - (time.monotonic() - last)
        if remaining > 0:
            time.sleep(remaining)
    _last_page_fetch[host] = time.monotonic()


def _robots_for(url: str, client: httpx.Client) -> RobotFileParser | None:
    parts = urlsplit(url)
    origin = f"{parts.scheme}://{parts.netloc}"
    if origin not in _robots_cache:
        parser = None
        try:
            response = client.get(f"{origin}/robots.txt")
            if response.status_code == 200:
                parser = RobotFileParser()
                parser.parse(response.text.splitlines())
        except httpx.HTTPError:
            pass
        _robots_cache[origin] = parser
    return _robots_cache[origin]


def fetch_article_text(url: str) -> str:
    """Text of an article page, or "" if it cannot or may not be fetched."""
    settings = get_settings()
    with httpx.Client(
        headers={"User-Agent": settings.http_user_agent}, follow_redirects=True, timeout=20
    ) as client:
        robots = _robots_for(url, client)
        # No readable robots.txt means no stated permission: do not fetch.
        if robots is None or not robots.can_fetch(settings.http_user_agent, url):
            return ""
        _wait_politely(url, robots, settings.http_user_agent)
        try:
            response = client.get(url)
        except httpx.HTTPError:
            return ""
    if response.status_code != 200:
        return ""
    from bs4 import BeautifulSoup

    soup = BeautifulSoup(response.text, "html.parser")
    container = soup.find("article") or soup.find("main") or soup.body
    return html_to_paragraphs(str(container))[:MAX_SOURCE_TEXT_CHARS] if container else ""
