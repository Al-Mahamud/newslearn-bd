"""Starter sources. All are public RSS feeds published by the outlets themselves.

Prothom Alo English puts the whole article in its feed. The other outlets' feeds carry one
or two sentences, so `fetch_full_text` is on for them: the article's public page is read
once (robots.txt allows it; each site's Crawl-delay is honoured), used to write the study
notes, and deleted after 7 days. It is never shown in the app. Turn it off per source with
PATCH /api/v1/admin/sources/{id} if a publisher's terms change.
"""

from sqlalchemy import select
from sqlalchemy.orm import Session

from app.models import Source

DEFAULT_SOURCES = [
    {
        "slug": "prothomalo-en",
        "name": "Prothom Alo English",
        "feed_url": "https://en.prothomalo.com/feed/",
        "homepage": "https://en.prothomalo.com",
    },
    {
        "slug": "dailystar-news",
        "fetch_full_text": True,
        "name": "The Daily Star",
        "feed_url": "https://www.thedailystar.net/news/rss.xml",
        "homepage": "https://www.thedailystar.net",
    },
    {
        "slug": "dailystar-business",
        "fetch_full_text": True,
        "name": "The Daily Star",
        "feed_url": "https://www.thedailystar.net/business/rss.xml",
        "homepage": "https://www.thedailystar.net",
        "default_category": "economy",
    },
    {
        "slug": "tbs-top",
        "fetch_full_text": True,
        "name": "The Business Standard",
        "feed_url": "https://www.tbsnews.net/top-news/rss.xml",
        "homepage": "https://www.tbsnews.net",
    },
    {
        "slug": "tbs-economy",
        "fetch_full_text": True,
        "name": "The Business Standard",
        "feed_url": "https://www.tbsnews.net/economy/rss.xml",
        "homepage": "https://www.tbsnews.net",
        "default_category": "economy",
    },
    {
        "slug": "dhakatribune",
        "fetch_full_text": True,
        "name": "Dhaka Tribune",
        "feed_url": "https://www.dhakatribune.com/feed/",
        "homepage": "https://www.dhakatribune.com",
    },
]


def seed_sources(db: Session) -> int:
    existing = set(db.scalars(select(Source.slug)))
    added = 0
    for data in DEFAULT_SOURCES:
        if data["slug"] not in existing:
            db.add(Source(**data))
            added += 1
    db.commit()
    return added
