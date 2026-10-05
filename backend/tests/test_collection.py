from datetime import timedelta
from email.utils import format_datetime

from app.models import Article, utcnow
from app.services import collector
from app.services.textutil import canonical_url, clean_text, title_similarity, title_tokens


def feed(*items: tuple[str, str, float]) -> bytes:
    """An RSS document from (title, link, hours_ago) tuples."""
    body = "".join(
        f"<item><title><![CDATA[{title}]]></title><link>{link}</link>"
        f"<description>Summary of {link}</description>"
        f"<pubDate>{format_datetime(utcnow() - timedelta(hours=hours))}</pubDate></item>"
        for title, link, hours in items
    )
    return f'<?xml version="1.0"?><rss version="2.0"><channel>{body}</channel></rss>'.encode()


def test_canonical_url_drops_tracking_and_fragment():
    url = "HTTPS://Example.com/news/story/?utm_source=x&id=7&fbclid=abc#top"
    assert canonical_url(url) == "https://example.com/news/story?id=7"


def test_clean_text_strips_markup_and_entities():
    assert clean_text('<a href="/x">Taka &amp; trade</a>\n rise') == "Taka & trade rise"


def test_similar_headlines_score_high_and_unrelated_low():
    a = title_tokens("Inflation falls to 8.5% in September, says Bangladesh Bank")
    b = title_tokens("Bangladesh Bank says inflation falls to 8.5% in September")
    c = title_tokens("Tigers win the series against Sri Lanka")
    assert title_similarity(a, b) >= collector.DUPLICATE_THRESHOLD
    assert title_similarity(a, c) < 0.2


def test_parse_feed_cleans_html_titles():
    content = feed(('<a href="https://x.test/a">Budget passed</a>', "https://x.test/a", 1))
    [item] = collector.parse_feed(content)
    assert item.title == "Budget passed"
    assert item.excerpt == "Summary of https://x.test/a"


def test_store_items_skips_known_old_and_duplicate_stories(db, source):
    items = collector.parse_feed(
        feed(
            ("Parliament passes the national budget for 2027", "https://x.test/a", 1),
            ("National budget for 2027 passes in parliament", "https://y.test/b", 2),
            ("A story from long ago", "https://x.test/old", 24 * 400),
            ("Cyclone warning issued for coastal districts", "https://x.test/c?utm_source=rss", 3),
        )
    )
    result = collector.store_items(db, source, items)
    db.commit()
    assert (result.new, result.duplicates, result.skipped_old) == (2, 1, 1)

    duplicate = db.query(Article).filter_by(url="https://y.test/b").one()
    assert duplicate.status == "skipped" and duplicate.duplicate_of_id is not None
    assert db.query(Article).filter_by(url="https://x.test/c").one().status == "pending"

    # A second run over the same feed adds nothing.
    again = collector.store_items(db, source, items)
    assert (again.new, again.duplicates) == (0, 0)


def test_article_pages_are_spaced_out_by_the_sites_crawl_delay(monkeypatch):
    from urllib.robotparser import RobotFileParser

    robots = RobotFileParser()
    robots.parse(["User-agent: *", "Crawl-delay: 10", "Disallow: /private/"])
    clock = {"now": 100.0}
    sleeps: list[float] = []
    monkeypatch.setattr(collector.time, "monotonic", lambda: clock["now"])
    monkeypatch.setattr(collector.time, "sleep", sleeps.append)
    monkeypatch.setattr(collector, "_last_page_fetch", {})

    collector._wait_politely("https://paper.test/a", robots, "NewsLearnBD")  # first: no wait
    clock["now"] += 4
    collector._wait_politely("https://paper.test/b", robots, "NewsLearnBD")  # 6 s still owed
    collector._wait_politely("https://other.test/c", robots, "NewsLearnBD")  # different site
    assert sleeps == [6.0]


def test_hosted_postgres_urls_are_given_the_installed_driver():
    from app.config import Settings

    for url in (
        "postgres://u:p@host/db?sslmode=require",
        "postgresql://u:p@host/db?sslmode=require",
    ):
        assert Settings(database_url=url).database_url == (
            "postgresql+psycopg://u:p@host/db?sslmode=require"
        )
    assert Settings(database_url="sqlite:///./x.db").database_url == "sqlite:///./x.db"
