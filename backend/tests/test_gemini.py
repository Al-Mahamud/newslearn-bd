"""The Gemini provider, exercised through the real SDK against a stubbed HTTP transport."""

import json

import httpx
import pytest
from google import genai
from google.genai import types

from app.ai.base import AIError, AIRefused
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


def provider(body: dict, status: int = 200, seen: list | None = None) -> GeminiProvider:
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
    return GeminiProvider(api_key="", client=client)


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


def test_truncated_malformed_and_failed_calls_are_retryable_errors():
    def enrich(p):
        return p.enrich_article(title="t", text="x", source="s")

    for broken in (
        provider(reply(json.dumps(ENRICHMENT)[:40], finish="MAX_TOKENS")),
        provider(reply("not json")),
        provider(
            {"error": {"code": 400, "message": "API key not valid", "status": "INVALID_ARGUMENT"}},
            400,
        ),
    ):
        with pytest.raises(AIError) as raised:
            enrich(broken)
        assert not isinstance(raised.value, AIRefused)
