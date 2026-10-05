from app import ai
from app.ai.base import AIError, AIRefused, AIUsageInfo, MCQItem
from app.ai.mock import MockProvider
from app.models import AIUsage, Word
from app.services import maintenance, pipeline


def test_processing_stores_summaries_words_facts_and_questions(db, make_article):
    article = make_article()
    assert article.status == "ready"
    assert article.summary and article.easy_summary and article.bangla_summary
    assert article.category == "economy"
    assert 1 <= len(article.words) <= pipeline.MAX_VOCABULARY
    assert any(f.kind == "number" and f.text == "8.5%" for f in article.facts)
    assert len(article.questions) == 1
    assert article.prompt_version and article.processed_at


def test_words_are_shared_between_articles_and_counted_once_per_article(db, make_article):
    first = make_article()
    make_article()
    lemma = first.words[0].word.lemma
    assert db.query(Word).filter_by(lemma=lemma).count() == 1
    assert db.query(Word).filter_by(lemma=lemma).one().occurrences == 2

    # Reprocessing an article must not count its words a second time.
    pipeline.reset_for_reprocessing(first)
    pipeline.process_article(db, first)
    db.commit()
    assert db.query(Word).filter_by(lemma=lemma).one().occurrences == 2


def test_short_text_is_skipped_without_calling_the_model(db, make_article):
    article = make_article(text="Too short.")
    assert article.status == "skipped"
    assert db.query(AIUsage).count() == 0


class Failing(MockProvider):
    def __init__(self, error):
        self.error = error

    def enrich_article(self, **kwargs):
        raise self.error


def test_provider_errors_are_retried_then_marked_failed(db, make_article):
    ai.set_provider(Failing(AIError("boom")))
    article = make_article(process=False)
    for expected in ["pending"] * (pipeline.MAX_ATTEMPTS - 1) + ["failed"]:
        pipeline.process_pending(db)
        db.refresh(article)
        assert article.status == expected
    assert article.error == "boom"


def test_refused_content_is_skipped_not_retried(db, make_article):
    ai.set_provider(Failing(AIRefused("declined")))
    article = make_article(process=False)
    pipeline.process_pending(db)
    db.refresh(article)
    assert (article.status, article.attempts) == ("skipped", 1)


class Costly(MockProvider):
    def enrich_article(self, **kwargs):
        data, _ = super().enrich_article(**kwargs)
        return data, AIUsageInfo(model="test", input_tokens=1000, output_tokens=500, cost_usd=0.6)


def test_processing_stops_when_the_daily_budget_is_spent(db, make_article):
    ai.set_provider(Costly())  # default budget is $1.00: two articles fit, the third does not
    for _ in range(3):
        make_article(process=False)
    result = pipeline.process_pending(db)
    assert result.ready == 2 and result.stopped_for_budget


class BadQuestions(MockProvider):
    def enrich_article(self, **kwargs):
        data, usage = super().enrich_article(**kwargs)
        good = data.questions[0]
        data.questions = [
            MCQItem(
                question="Three options?", options=["a", "b", "c"], correct_index=0, explanation=""
            ),
            MCQItem(
                question="Repeated?", options=["a", "a", "b", "c"], correct_index=0, explanation=""
            ),
            MCQItem(
                question="Out of range?",
                options=["a", "b", "c", "d"],
                correct_index=4,
                explanation="",
            ),
            good,
        ]
        data.exam_importance = 250
        return data, usage


def test_malformed_model_output_is_filtered(db, make_article):
    ai.set_provider(BadQuestions())
    article = make_article()
    assert len(article.questions) == 1
    assert article.exam_importance == 100


def test_cleanup_removes_old_articles_but_keeps_saved_ones(db, make_article, client, auth):
    old = make_article(hours_ago=24 * 200)
    kept = make_article(hours_ago=24 * 200)
    recent = make_article()
    assert client.put(f"/api/v1/articles/{kept.id}/save", headers=auth).status_code == 204
    old_id, kept_id, recent_id = old.id, kept.id, recent.id

    result = maintenance.cleanup(db)
    assert result["articles_deleted"] == 1
    db.expire_all()
    from app.models import Article

    assert db.get(Article, old_id) is None
    assert db.get(Article, kept_id) is not None and db.get(Article, recent_id) is not None


def test_a_spent_quota_ends_the_run_and_costs_no_attempts(db, make_article):
    from app.ai.base import AIRateLimited

    ai.set_provider(Failing(AIRateLimited("quota reached")))
    articles = [make_article(process=False) for _ in range(3)]
    result = pipeline.process_pending(db)
    assert result.stopped_for_rate_limit and (result.ready, result.failed) == (0, 0)
    for article in articles:
        db.refresh(article)
        assert (article.status, article.attempts) == ("pending", 0)


def test_calls_are_paced_to_the_per_minute_limit(db, make_article, monkeypatch):
    from app.config import get_settings

    clock = {"now": 1000.0}
    sleeps: list[float] = []

    def sleep(seconds):
        sleeps.append(seconds)
        clock["now"] += seconds

    monkeypatch.setattr(pipeline.time, "monotonic", lambda: clock["now"])
    monkeypatch.setattr(pipeline.time, "sleep", sleep)
    monkeypatch.setattr(pipeline, "_last_call", 0.0)
    monkeypatch.setattr(get_settings(), "ai_requests_per_minute", 4)
    for _ in range(3):
        make_article(process=False)
    assert pipeline.process_pending(db).ready == 3
    assert sleeps == [15.0, 15.0]  # no wait before the first call


def test_an_overloaded_provider_defers_articles_then_ends_the_run(db, make_article):
    from app.ai.base import AIUnavailable

    ai.set_provider(Failing(AIUnavailable("high demand")))
    articles = [make_article(process=False) for _ in range(5)]
    result = pipeline.process_pending(db)
    assert result.deferred == pipeline.MAX_CONSECUTIVE_OUTAGES and result.failed == 0
    for article in articles:
        db.refresh(article)
        assert (article.status, article.attempts) == ("pending", 0)
