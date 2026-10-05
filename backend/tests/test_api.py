from datetime import timedelta

from app.config import get_settings
from app.models import UserWord, utcnow
from app.services import srs
from app.services.timeutil import local_today, week_start

API = "/api/v1"


# --- auth -----------------------------------------------------------------------------


def test_register_login_and_profile(client):
    creds = {"email": "Reader@Example.com", "password": "secret-pass"}
    r = client.post(f"{API}/auth/register", json=creds)
    assert r.status_code == 201
    assert r.json()["user"]["is_admin"] is True  # first account owns the install
    assert client.post(f"{API}/auth/register", json=creds).status_code == 409

    second = client.post(
        f"{API}/auth/register", json={"email": "two@example.com", "password": "secret-pass"}
    )
    assert second.json()["user"]["is_admin"] is False

    assert (
        client.post(f"{API}/auth/login", json={**creds, "password": "wrong-pass"}).status_code
        == 401
    )
    token = client.post(f"{API}/auth/login", json=creds).json()["access_token"]
    headers = {"Authorization": f"Bearer {token}"}
    assert client.get(f"{API}/me", headers=headers).json()["email"] == "reader@example.com"

    r = client.patch(
        f"{API}/me",
        headers=headers,
        json={"english_level": "advanced", "preferred_categories": ["economy"]},
    )
    assert r.json()["english_level"] == "advanced"
    bad = client.patch(f"{API}/me", headers=headers, json={"preferred_categories": ["nope"]})
    assert bad.status_code == 422


def test_protected_routes_reject_missing_and_invalid_tokens(client):
    assert client.get(f"{API}/me").status_code == 401
    assert client.get(f"{API}/me", headers={"Authorization": "Bearer junk"}).status_code == 401
    # Public routes still reject a token that was sent but is invalid.
    assert (
        client.get(f"{API}/articles", headers={"Authorization": "Bearer junk"}).status_code == 401
    )


def test_refresh_tokens_rotate_and_reuse_signs_out(client):
    first = client.post(
        f"{API}/auth/register", json={"email": "r@example.com", "password": "secret-pass"}
    ).json()
    second = client.post(f"{API}/auth/refresh", json={"refresh_token": first["refresh_token"]})
    assert second.status_code == 200
    # Replaying the used token fails and revokes the token that replaced it.
    replay = client.post(f"{API}/auth/refresh", json={"refresh_token": first["refresh_token"]})
    assert replay.status_code == 401
    after = client.post(
        f"{API}/auth/refresh", json={"refresh_token": second.json()["refresh_token"]}
    )
    assert after.status_code == 401


# --- feed and articles ----------------------------------------------------------------


def test_feed_is_public_paginated_and_hides_unprocessed_articles(client, make_article):
    for hours in range(1, 6):
        make_article(hours_ago=hours)
    make_article(process=False)

    page1 = client.get(f"{API}/articles", params={"limit": 2}).json()
    assert len(page1["items"]) == 2 and page1["next_cursor"]
    seen = [a["id"] for a in page1["items"]]
    cursor = page1["next_cursor"]
    while cursor:
        page = client.get(f"{API}/articles", params={"limit": 2, "cursor": cursor}).json()
        seen += [a["id"] for a in page["items"]]
        cursor = page["next_cursor"]
    assert len(seen) == len(set(seen)) == 5

    assert client.get(f"{API}/articles", params={"cursor": "garbage"}).status_code == 400


def test_feed_filters(client, auth, make_article):
    economy = make_article()
    sports = make_article(
        "Tigers win the cricket match", text="The cricket match ended with a thrilling wicket. " * 3
    )
    assert sports.category == "sports"

    def ids(**params):
        r = client.get(f"{API}/articles", params=params, headers=auth)
        assert r.status_code == 200, r.text
        return [a["id"] for a in r.json()["items"]]

    assert ids(category="sports") == [sports.id]
    assert ids(exam="true") == [economy.id]
    assert ids(q="cricket") == [sports.id]
    assert set(ids(source="test-times")) == {sports.id, economy.id}
    assert ids(source="nobody") == []
    assert client.get(f"{API}/articles", params={"category": "nope"}).status_code == 400

    client.patch(f"{API}/me", headers=auth, json={"preferred_categories": ["sports"]})
    assert ids(for_you="true") == [sports.id]

    assert ids(saved="true") == []
    client.put(f"{API}/articles/{economy.id}/save", headers=auth)
    assert ids(saved="true") == [economy.id]
    assert client.get(f"{API}/articles", params={"saved": "true"}).status_code == 401


def test_article_detail_save_and_read_state(client, auth, make_article):
    article = make_article()
    pending = make_article(process=False)
    assert client.get(f"{API}/articles/{pending.id}").status_code == 404

    detail = client.get(f"{API}/articles/{article.id}").json()
    assert detail["easy_summary"] and detail["bangla_summary"] and detail["vocabulary"]
    assert detail["facts"] and detail["question_count"] == 1
    assert detail["saved"] is False and detail["read"] is False
    assert "source_text" not in detail  # publisher text is never served

    assert client.put(f"{API}/articles/{article.id}/save", headers=auth).status_code == 204
    assert client.put(f"{API}/articles/{article.id}/save", headers=auth).status_code == 204
    assert client.post(f"{API}/articles/{article.id}/read", headers=auth).status_code == 204
    detail = client.get(f"{API}/articles/{article.id}", headers=auth).json()
    assert detail["saved"] is True and detail["read"] is True

    assert client.delete(f"{API}/articles/{article.id}/save", headers=auth).status_code == 204
    assert client.get(f"{API}/articles/{article.id}", headers=auth).json()["saved"] is False


def test_search_finds_articles_and_words(client, make_article):
    article = make_article()
    word = article.words[0].word.lemma
    result = client.get(f"{API}/search", params={"q": word[:5]}).json()
    assert word in [w["word"] for w in result["words"]]
    result = client.get(f"{API}/search", params={"q": "inflation"}).json()
    assert [a["id"] for a in result["articles"]] == [article.id]


# --- vocabulary -----------------------------------------------------------------------


def test_vocabulary_save_review_and_learn(client, auth, db, make_article):
    article = make_article()
    word_id = article.words[0].word_id

    r = client.put(f"{API}/vocabulary/{word_id}", headers=auth, json={"article_id": article.id})
    assert r.status_code == 200
    saved = r.json()
    assert saved["box"] == 0 and saved["learned"] is False and saved["word"]["context_sentence"]
    assert client.put(f"{API}/vocabulary/99999", headers=auth).status_code == 404

    detail = client.get(f"{API}/articles/{article.id}", headers=auth).json()
    assert [w["saved"] for w in detail["vocabulary"] if w["id"] == word_id] == [True]

    due = client.get(f"{API}/vocabulary/review", headers=auth).json()
    assert [d["word"]["id"] for d in due] == [word_id]

    r = client.post(f"{API}/vocabulary/{word_id}/review", headers=auth, json={"remembered": True})
    assert r.json()["box"] == 1
    assert client.get(f"{API}/vocabulary/review", headers=auth).json() == []

    for _ in range(3):
        r = client.post(
            f"{API}/vocabulary/{word_id}/review", headers=auth, json={"remembered": True}
        )
    assert r.json()["learned"] is True
    assert (
        len(client.get(f"{API}/vocabulary", params={"status": "learned"}, headers=auth).json()) == 1
    )

    r = client.post(f"{API}/vocabulary/{word_id}/review", headers=auth, json={"remembered": False})
    assert r.json()["box"] == 0 and r.json()["learned"] is False
    assert len(client.get(f"{API}/vocabulary/review", headers=auth).json()) == 1

    assert client.delete(f"{API}/vocabulary/{word_id}", headers=auth).status_code == 204
    assert client.get(f"{API}/vocabulary", headers=auth).json() == []


def test_review_intervals_follow_the_schedule():
    now = utcnow()
    entry = UserWord(user_id=1, word_id=1, box=0, due_at=now, review_count=0, correct_count=0)
    gaps = []
    for _ in range(4):
        srs.apply_review(entry, True, now)
        gaps.append((entry.due_at - now).days)
    assert gaps == [1, 3, 7, 30]


# --- explanations and tutor -----------------------------------------------------------


def test_explain_is_cached_and_rate_limited(client, auth, monkeypatch):
    body = {"sentence": "Weak economic activity and sluggish credit growth would limit inflation."}
    assert client.post(f"{API}/explain", json=body).status_code == 401

    first = client.post(f"{API}/explain", json=body, headers=auth).json()
    assert first["cached"] is False and first["bangla"] and first["words"]
    again = client.post(f"{API}/explain", json=body, headers=auth).json()
    assert again["cached"] is True and again["simple_english"] == first["simple_english"]

    monkeypatch.setattr(get_settings(), "explain_daily_limit", 1)
    other = {"sentence": "A different sentence that has not been explained before."}
    assert client.post(f"{API}/explain", json=other, headers=auth).status_code == 429
    # Cached answers cost nothing, so they are still served at the limit.
    assert client.post(f"{API}/explain", json=body, headers=auth).status_code == 200


def test_tutor_answers_from_article_notes(client, auth, make_article):
    article = make_article()
    r = client.post(
        f"{API}/articles/{article.id}/ask",
        headers=auth,
        json={
            "question": "What happened to inflation?",
            "history": [
                {"role": "user", "content": "Hi"},
                {"role": "assistant", "content": "Hello"},
            ],
        },
    )
    assert r.status_code == 200 and article.title in r.json()["answer"]

    bad = client.post(
        f"{API}/articles/{article.id}/ask",
        headers=auth,
        json={
            "question": "x",
            "history": [
                {"role": "user", "content": "a"},
                {"role": "user", "content": "b"},
                {"role": "assistant", "content": "c"},
            ],
        },
    )
    assert bad.status_code == 422


# --- current affairs ------------------------------------------------------------------


def test_current_affairs_digests_group_by_topic(client, make_article):
    make_article()
    make_article("Tigers win the cricket match", text="The cricket match ended in a thriller. " * 3)
    make_article(hours_ago=24 * 30)

    daily = client.get(f"{API}/current-affairs/daily").json()
    # Articles made "1 hour ago" can fall on yesterday just after local midnight.
    yesterday = client.get(
        f"{API}/current-affairs/daily", params={"date": str(local_today() - timedelta(days=1))}
    ).json()
    assert daily["article_count"] + yesterday["article_count"] == 2
    busiest = max(daily, yesterday, key=lambda d: d["article_count"])
    assert busiest["groups"][0]["label"] and "number" in busiest["revision"]

    weekly = client.get(f"{API}/current-affairs/weekly").json()
    assert weekly["period"] == "weekly" and weekly["start"] == str(week_start(local_today()))
    assert weekly["end"] == str(week_start(local_today()) + timedelta(days=6))


# --- quizzes and progress -------------------------------------------------------------


def test_quiz_flow_and_progress(client, auth, make_article):
    articles = [make_article(hours_ago=0) for _ in range(3)]

    assert client.get(f"{API}/quizzes/daily").status_code == 401
    quiz = client.get(f"{API}/quizzes/daily", headers=auth).json()
    assert quiz["kind"] == "daily" and len(quiz["questions"]) == 3
    assert "correct_index" not in quiz["questions"][0]
    # The daily quiz is created once and shared.
    assert client.get(f"{API}/quizzes/daily", headers=auth).json()["id"] == quiz["id"]

    answers = [{"question_id": q["id"], "selected_index": 0} for q in quiz["questions"]]
    answers[0]["selected_index"] = 1
    answers[1]["selected_index"] = None
    result = client.post(
        f"{API}/quizzes/{quiz['id']}/attempts",
        headers=auth,
        json={"answers": answers, "duration_seconds": 42},
    ).json()
    assert (result["score"], result["total"], result["percent"]) == (1, 3, 33)
    assert [r["correct"] for r in result["review"]] == [False, False, True]
    assert result["review"][0]["correct_index"] == 0 and result["review"][0]["explanation"]

    history = client.get(f"{API}/me/quiz-attempts", headers=auth).json()
    assert [h["attempt_id"] for h in history] == [result["attempt_id"]]
    again = client.get(f"{API}/me/quiz-attempts/{result['attempt_id']}", headers=auth).json()
    assert again["score"] == 1

    client.post(f"{API}/articles/{articles[0].id}/read", headers=auth)
    progress = client.get(f"{API}/me/progress", headers=auth).json()
    assert progress["streak_days"] == 1
    assert progress["articles_read"] == progress["articles_read_today"] == 1
    assert progress["quizzes_taken"] == 1 and progress["average_score_percent"] == 33
    assert progress["topics"] == [
        {"category": "economy", "label": "Economy", "answered": 3, "correct": 1, "percent": 33}
    ]
    assert len(progress["last_7_days"]) == 7 and progress["last_7_days"][-1]["quizzes_taken"] == 1


def test_practice_mock_and_article_quizzes(client, auth, make_article):
    article = make_article()
    make_article()

    assert (
        client.post(f"{API}/quizzes", headers=auth, json={"category": "sports"}).status_code == 404
    )
    assert client.post(f"{API}/quizzes", headers=auth, json={"category": "nope"}).status_code == 400

    practice = client.post(
        f"{API}/quizzes", headers=auth, json={"category": "economy", "count": 1}
    ).json()
    assert practice["kind"] == "practice" and len(practice["questions"]) == 1
    assert practice["time_limit_seconds"] is None

    mock = client.post(f"{API}/quizzes", headers=auth, json={"kind": "mock"}).json()
    assert len(mock["questions"]) == 2 and mock["time_limit_seconds"] == 90

    r = client.post(f"{API}/articles/{article.id}/quiz", headers=auth)
    assert r.status_code == 201 and len(r.json()["questions"]) == 1

    # Personal quizzes are private to the user who created them.
    other = client.post(
        f"{API}/auth/register", json={"email": "other@example.com", "password": "secret-pass"}
    ).json()
    other_headers = {"Authorization": f"Bearer {other['access_token']}"}
    assert client.get(f"{API}/quizzes/{mock['id']}", headers=other_headers).status_code == 404


# --- admin ----------------------------------------------------------------------------


def test_admin_routes_require_admin(client, auth, source):
    other = client.post(
        f"{API}/auth/register", json={"email": "other@example.com", "password": "secret-pass"}
    ).json()
    other_headers = {"Authorization": f"Bearer {other['access_token']}"}
    assert client.get(f"{API}/admin/sources", headers=other_headers).status_code == 403
    assert client.get(f"{API}/admin/sources").status_code == 401

    assert [s["slug"] for s in client.get(f"{API}/admin/sources", headers=auth).json()] == [
        "test-times"
    ]
    new = {"slug": "new-paper", "name": "New Paper", "feed_url": "https://new.test/rss"}
    created = client.post(f"{API}/admin/sources", headers=auth, json=new)
    assert created.status_code == 201 and created.json()["fetch_full_text"] is False
    assert client.post(f"{API}/admin/sources", headers=auth, json=new).status_code == 409

    patched = client.patch(
        f"{API}/admin/sources/{created.json()['id']}", headers=auth, json={"enabled": False}
    )
    assert patched.json()["enabled"] is False


def test_admin_process_and_status(client, auth, make_article):
    make_article(process=False)
    result = client.post(f"{API}/admin/process", headers=auth).json()
    assert result["ready"] == 1
    status = client.get(f"{API}/admin/status", headers=auth).json()
    assert status["articles"] == {"ready": 1} and status["usage"][0]["calls"] == 1


def test_health(client):
    assert client.get("/health").json() == {"status": "ok"}
