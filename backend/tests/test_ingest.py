import json

from app.cli import main
from app.models import Article
from app.services import ingest as ingest_service
from app.services.timeutil import local_today

API = "/api/v1"

ANALYSIS = {
    "category": "science_technology",
    "summary": "The government approved a national AI policy to guide public-sector use.",
    "easy_summary": "The government made rules for using AI in its offices.",
    "bangla_summary": "সরকারি খাতে কৃত্রিম বুদ্ধিমত্তা ব্যবহারের জন্য জাতীয় নীতিমালা অনুমোদন করা হয়েছে।",
    "vocabulary": [
        {
            "word": "framework",
            "part_of_speech": "noun",
            "meaning_en": "a set of rules or ideas used to plan something",
            "meaning_bn": "কাঠামো",
            "example_sentence": "The framework explains how schools should use computers.",
            "context_sentence": "The policy sets a framework for public-sector AI.",
            "difficulty": "medium",
        }
    ],
    "facts": [{"kind": "organization", "text": "ICT Division", "detail": "Drafted the policy"}],
    "exam_importance": 90,
    "exam_reason": "National ICT policy is a core topic for ICT cadre and BCS exams.",
    "questions": [
        {
            "question": "Which body drafted the national AI policy?",
            "options": ["ICT Division", "BTRC", "Bangladesh Bank", "NBR"],
            "correct_index": 0,
            "explanation": "The article says the ICT Division drafted it.",
        }
    ],
}


def batch(*titles, **overrides) -> ingest_service.AgentBatch:
    return ingest_service.AgentBatch.model_validate(
        {
            "model": "gemini-3.8-pro",
            "articles": [
                {
                    "source": "The Daily Star",
                    "title": title,
                    "published_on": str(local_today()),
                    "page": "3",
                    "excerpt": "Cabinet approves the policy.",
                    "analysis": ANALYSIS,
                    **overrides,
                }
                for title in titles
            ],
        }
    )


def test_agent_articles_appear_in_the_app_like_any_other(db, client):
    report = ingest_service.ingest(db, batch("Cabinet approves national AI policy"))
    assert report.added == ["Cabinet approves national AI policy"] and report.ok

    [card] = client.get(f"{API}/articles").json()["items"]
    assert card["source"] == "The Daily Star" and card["exam_important"] is True
    detail = client.get(f"{API}/articles/{card['id']}").json()
    assert detail["vocabulary"][0]["meaning_bn"] == "কাঠামো"
    assert detail["question_count"] == 1 and detail["author"] == "Page 3"

    article = db.get(Article, card["id"])
    assert article.ai_model == "agent:gemini-3.8-pro"
    assert article.source_text == ""  # the agent sends notes, never the article itself
    assert article.source.enabled is False  # nothing for the feed collector to fetch


def test_sending_the_same_article_again_updates_it(db):
    ingest_service.ingest(db, batch("Cabinet approves national AI policy"))
    again = ingest_service.ingest(
        db, batch("Cabinet approves national AI policy", excerpt="Corrected excerpt.")
    )
    assert again.updated == ["Cabinet approves national AI policy"] and not again.added
    assert db.query(Article).count() == 1
    assert db.query(Article).one().excerpt == "Corrected excerpt."


def test_a_story_already_analysed_is_not_stored_twice(db, make_article):
    make_article("Inflation eases as credit growth slows in September")
    report = ingest_service.ingest(db, batch("Inflation eases as credit growth slows, September"))
    assert report.already_covered and not report.added
    assert db.query(Article).count() == 1


def test_a_story_still_waiting_for_the_pipeline_is_taken_over(db, make_article):
    waiting = make_article("Cabinet approves the national AI policy", process=False)
    report = ingest_service.ingest(db, batch("Cabinet approves national AI policy"))
    assert report.added
    db.refresh(waiting)
    assert waiting.status == "skipped" and waiting.duplicate_of_id is not None


def test_dry_run_saves_nothing(db):
    report = ingest_service.ingest(db, batch("Cabinet approves national AI policy"), dry_run=True)
    assert report.added and db.query(Article).count() == 0


def test_cli_reports_format_problems_and_refuses_the_file(tmp_path, capsys, db):
    bad = tmp_path / "bad.json"
    bad.write_text(json.dumps({"articles": [{"source": "The Daily Star", "title": "No analysis"}]}))
    assert main(["ingest", str(bad)]) == 1
    errors = capsys.readouterr().err
    assert "articles.0.analysis" in errors and "articles.0.published_on" in errors

    good = tmp_path / "good.json"
    good.write_text(batch("Cabinet approves national AI policy").model_dump_json())
    assert main(["ingest", str(good)]) == 0
    assert "added: Cabinet approves national AI policy" in capsys.readouterr().out

    assert main(["recent"]) == 0
    assert json.loads(capsys.readouterr().out)[0]["status"] == "ready"
