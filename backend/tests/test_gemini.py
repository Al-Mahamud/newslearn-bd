"""The Gemini provider, exercised through the real SDK against a stubbed HTTP transport."""

import json

import httpx
import pytest
from google import genai
from google.genai import types

from app.ai.base import AIConfigError, AIError, AIRateLimited, AIRefused, AIUnavailable
from app.ai.gemini import GeminiProvider

ENRICHMENT = {
    "category": "economy",
    "summary": "Inflation fell to 8.5% in September.",
    "easy_summary": "Prices rose more slowly in September.",
    "bangla_summary": "সেপ্টেম্বরে মূল্যস্ফীতি কমে ৮.৫ শতাংশ হয়েছে।",
    "vocabulary": [
        {
            "word": "sluggish",
            "part_of_speech": "adjective",
            "meaning_en": "slow",
            "meaning_bn": "ধীর",
            "example_sentence": "Sales were sluggish.",
            "context_sentence": "Sluggish credit growth limited spending.",
            "difficulty": "medium",
        }
    ],
    "facts": [{"kind": "number", "text": "8.5%", "detail": "September inflation"}],
    "exam_importance": 80,
    "exam_reason": "Inflation figures are common exam questions.",
    "questions": [
        {
            "question": "What was inflation in September?",
            "options": ["7.5%", "8.5%", "9.5%", "10.5%"],
            "correct_index": 1,
            "explanation": "The article gives 8.5%.",
        }
    ],
}


def reply(text: str, finish="STOP", **extra) -> dict:
    return {
        "candidates": [
            {"content": {"role": "model", "parts": [{"text": text}]}, "finishReason": finish}
        ],
        "usageMetadata": {
            "promptTokenCount": 1000,
            "candidatesTokenCount": 400,
            "thoughtsTokenCount": 100,
        },
        **extra,
    }


def provider(
    body: dict, status: int = 200, seen: list | None = None, model: str = "gemini-2.5-flash"
) -> GeminiProvider:
    def handler(request: httpx.Request) -> httpx.Response:
        if seen is not None:
            seen.append(json.loads(request.content))
        return httpx.Response(status, json=body)

    client = genai.Client(
        api_key="test-key",
        http_options=types.HttpOptions(
            httpx_client=httpx.Client(transport=httpx.MockTransport(handler))
        ),
    )
    return GeminiProvider(api_key="", model=model, client=client)


def test_enrichment_is_requested_as_json_and_parsed_with_usage():
    seen: list = []
    data, usage = provider(reply(json.dumps(ENRICHMENT)), seen=seen).enrich_article(
        title="Inflation eases", text="Inflation fell to 8.5%.", source="Test Times"
    )
    assert data.category == "economy" and data.vocabulary[0].meaning_bn == "ধীর"
    assert data.questions[0].correct_index == 1

    # 1,000 in at $0.30/M plus 500 out (answer + thinking) at $2.50/M
    assert (usage.model, usage.input_tokens, usage.output_tokens) == ("gemini-2.5-flash", 1000, 500)
    assert usage.cost_usd == pytest.approx(0.00155)

    request = seen[0]
    config = request["generationConfig"]
    assert config["responseMimeType"] == "application/json"
    assert "vocabulary" in json.dumps(config)  # our schema was sent
    assert "NewsLearn BD" in json.dumps(request["systemInstruction"])
    assert "Test Times" in json.dumps(request["contents"])


def test_tutor_sends_history_with_gemini_roles():
    seen: list = []
    answer, _ = provider(reply("Prices rose more slowly."), seen=seen).answer_question(
        material="Summary: inflation fell.",
        question="Why?",
        history=[{"role": "user", "content": "Hi"}, {"role": "assistant", "content": "Hello"}],
        level="beginner",
    )
    assert answer == "Prices rose more slowly."
    assert [c["role"] for c in seen[0]["contents"]] == ["user", "model", "user", "model", "user"]


def test_blocked_content_is_a_refusal():
    with pytest.raises(AIRefused):
        provider(reply("", finish="SAFETY")).explain_sentence(sentence="x", context="", level="b")
    blocked_prompt = {"promptFeedback": {"blockReason": "SAFETY"}}
    with pytest.raises(AIRefused):
        provider(blocked_prompt).explain_sentence(sentence="x", context="", level="b")


def test_truncated_or_malformed_answers_are_retryable_errors():
    for broken in (
        provider(reply(json.dumps(ENRICHMENT)[:40], finish="MAX_TOKENS")),
        provider(reply("not json")),
    ):
        with pytest.raises(AIError) as raised:
            broken.enrich_article(title="t", text="x", source="s")
        assert type(raised.value) is AIError


def test_overload_and_quota_are_reported_without_hidden_retries():
    seen: list = []
    busy = {"error": {"code": 503, "message": "high demand", "status": "UNAVAILABLE"}}
    with pytest.raises(AIUnavailable):
        provider(busy, 503, seen=seen).enrich_article(title="t", text="x", source="s")
    assert len(seen) == 1  # each retry would count against the per-minute quota

    quota = {"error": {"code": 429, "message": "quota exceeded", "status": "RESOURCE_EXHAUSTED"}}
    with pytest.raises(AIRateLimited):
        provider(quota, 429).enrich_article(title="t", text="x", source="s")


def test_a_bad_key_or_retired_model_is_a_configuration_error():
    retired = {
        "error": {"code": 404, "message": "model is no longer available", "status": "NOT_FOUND"}
    }
    bad_key = {
        "error": {"code": 400, "message": "API key not valid.", "status": "INVALID_ARGUMENT"}
    }
    for body, status in ((retired, 404), (bad_key, 400)):
        with pytest.raises(AIConfigError):
            provider(body, status).enrich_article(title="t", text="x", source="s")


def test_an_unlisted_model_is_costed_at_the_highest_known_rate():
    _, usage = provider(reply(json.dumps(ENRICHMENT)), model="gemini-9-new").enrich_article(
        title="t", text="x", source="s"
    )
    assert usage.cost_usd == pytest.approx((1000 * 1.25 + 500 * 10.0) / 1_000_000)


def test_a_configuration_error_stops_the_run_without_using_up_attempts(db, make_article):
    from app import ai
    from app.services import pipeline

    ai.set_provider(
        provider({"error": {"code": 404, "message": "gone", "status": "NOT_FOUND"}}, 404)
    )
    first, second = make_article(process=False), make_article(process=False)
    with pytest.raises(AIConfigError):
        pipeline.process_pending(db)
    db.rollback()
    for article in (first, second):
        db.refresh(article)
        assert (article.status, article.attempts) == ("pending", 0)
