import os

# TEST_DATABASE_URL runs the suite against another database, e.g. PostgreSQL.
os.environ.update(
    DATABASE_URL=os.environ.get("TEST_DATABASE_URL") or "sqlite:///:memory:",
    SCHEDULER_ENABLED="false",
    AI_PROVIDER="mock",
    AI_REQUESTS_PER_MINUTE="0",
    ENVIRONMENT="test",
)

from datetime import timedelta  # noqa: E402

import pytest  # noqa: E402
from fastapi.testclient import TestClient  # noqa: E402

from app import ai  # noqa: E402
from app.ai.mock import MockProvider  # noqa: E402
from app.db import Base, SessionLocal, engine  # noqa: E402
from app.main import app  # noqa: E402
from app.models import Article, Source, utcnow  # noqa: E402
from app.services import pipeline  # noqa: E402
from app.services.textutil import sha256  # noqa: E402


@pytest.fixture(autouse=True)
def fresh_database():
    Base.metadata.create_all(engine)
    ai.set_provider(MockProvider())
    yield
    Base.metadata.drop_all(engine)


@pytest.fixture
def db():
    with SessionLocal() as session:
        yield session


@pytest.fixture
def client():
    with TestClient(app) as c:
        yield c


@pytest.fixture
def auth(client):
    """Headers for a signed-in user (the first account, so also the admin)."""
    r = client.post(
        "/api/v1/auth/register", json={"email": "reader@example.com", "password": "secret-pass"}
    )
    assert r.status_code == 201, r.text
    return {"Authorization": f"Bearer {r.json()['access_token']}"}


@pytest.fixture
def source(db):
    src = Source(slug="test-times", name="Test Times", feed_url="https://example.com/rss")
    db.add(src)
    db.commit()
    return src


TEXT = (
    "Bangladesh Bank said inflation fell to 8.5% in September as sluggish credit growth "
    "limited spending. The government expects remittance inflows to strengthen reserves. "
    "Economists warned that austerity measures could slow infrastructure development."
)


@pytest.fixture
def make_article(db, source):
    counter = iter(range(1, 1000))

    def _make(title=None, *, text=TEXT, hours_ago=1, process=True, **fields) -> Article:
        n = next(counter)
        url = f"https://example.com/news/{n}"
        article = Article(
            source_id=source.id,
            title=title or f"Inflation eases as credit growth slows, report {n}",
            url=url,
            url_hash=sha256(url),
            excerpt=text[:200],
            source_text=text,
            published_at=utcnow() - timedelta(hours=hours_ago),
            **fields,
        )
        db.add(article)
        db.commit()
        if process:
            pipeline.process_article(db, article)
            db.commit()
        return article

    return _make
