"""Writes real API responses to files for the Android app's contract test.

Run from backend/:  PYTHONPATH=. python scripts/export_fixtures.py ../android/app/src/test/resources/fixtures
"""
import json, os, sys
os.environ.update(DATABASE_URL="sqlite:///:memory:", SCHEDULER_ENABLED="false", AI_PROVIDER="mock")
from datetime import timedelta
from fastapi.testclient import TestClient
from app.db import Base, SessionLocal, engine
from app.main import app
from app.models import Article, Source, utcnow
from app.services import pipeline
from app.services.textutil import sha256

out = sys.argv[1]
Base.metadata.create_all(engine)
TEXT = ("Bangladesh Bank said inflation fell to 8.5% in September as sluggish credit growth "
        "limited spending. The government expects remittance inflows to strengthen reserves. "
        "Economists warned that austerity measures could slow infrastructure development.")
with SessionLocal() as db:
    src = Source(slug="t", name="Test Times", feed_url="https://example.com/rss"); db.add(src); db.commit()
    for n in range(3):
        url = f"https://example.com/{n}"
        a = Article(source_id=src.id, title=f"Inflation eases, report {n}", url=url, url_hash=sha256(url),
                    excerpt=TEXT[:100], source_text=TEXT, image_url="https://example.com/i.jpg" if n else None,
                    published_at=utcnow() - timedelta(minutes=n))
        db.add(a); db.commit(); pipeline.process_article(db, a); db.commit()

def save(name, response):
    assert response.status_code < 300, (name, response.status_code, response.text)
    data = response.json()
    if name == "token":  # no real credentials in the repository
        data["access_token"], data["refresh_token"] = "test-access-token", "test-refresh-token"
    open(os.path.join(out, name + ".json"), "w", encoding="utf-8").write(json.dumps(data, ensure_ascii=False, indent=1))

with TestClient(app) as c:
    A = "/api/v1"
    r = c.post(f"{A}/auth/register", json={"email": "a@example.com", "password": "secret-pass"}); save("token", r)
    h = {"Authorization": "Bearer " + r.json()["access_token"]}
    save("user", c.get(f"{A}/me", headers=h))
    save("feed", c.get(f"{A}/articles?limit=2", headers=h))
    save("article", c.get(f"{A}/articles/1", headers=h))
    wid = c.get(f"{A}/articles/1").json()["vocabulary"][0]["id"]
    save("user_word", c.put(f"{A}/vocabulary/{wid}", headers=h, json={"article_id": 1}))
    save("vocabulary", c.get(f"{A}/vocabulary", headers=h))
    save("explain", c.post(f"{A}/explain", headers=h, json={"sentence": "Sluggish credit growth limited spending."}))
    save("tutor", c.post(f"{A}/articles/1/ask", headers=h, json={"question": "Why?"}))
    save("digest", c.get(f"{A}/current-affairs/weekly"))
    save("search", c.get(f"{A}/search?q=inflation", headers=h))
    q = c.get(f"{A}/quizzes/daily", headers=h)
    if q.status_code == 404: q = c.post(f"{A}/quizzes", headers=h, json={"kind": "mock"})
    save("quiz", q)
    save("quiz_timed", c.post(f"{A}/quizzes", headers=h, json={"kind": "mock"}))
    qq = q.json()
    save("check", c.post(f"{A}/quizzes/{qq['id']}/check", headers=h, json={"question_id": qq["questions"][0]["id"], "selected_index": 0}))
    save("check_wrong", c.post(f"{A}/quizzes/{qq['id']}/check", headers=h, json={"question_id": qq["questions"][0]["id"], "selected_index": 2}))
    save("attempt", c.post(f"{A}/quizzes/{qq['id']}/attempts", headers=h, json={"answers": [{"question_id": qq["questions"][0]["id"], "selected_index": 0}], "duration_seconds": 5}))
    save("attempts", c.get(f"{A}/me/quiz-attempts", headers=h))
    c.post(f"{A}/articles/1/read", headers=h)
    save("progress", c.get(f"{A}/me/progress", headers=h))
    save("categories", c.get(f"{A}/categories"))
