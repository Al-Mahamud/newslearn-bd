import logging

from google import genai
from google.genai import errors, types
from pydantic import BaseModel

from app.ai.base import (
    AIConfigError,
    AIError,
    AIRateLimited,
    AIRefused,
    AIUnavailable,
    AIUsageInfo,
    ArticleEnrichment,
    SentenceExplanationResult,
)
from app.ai.prompts import ENRICH_SYSTEM, EXPLAIN_SYSTEM, TUTOR_SYSTEM

log = logging.getLogger(__name__)

DEFAULT_MODEL = "gemini-3.8-flash"

# USD per million tokens (input, output), used only to estimate spend for the daily budget.
# A model missing from this table (including the default, whose price was not known when
# this was written) is costed at the highest rate listed, so the budget stops early rather
# than late. Add the real price of the model you use from Google's price list.
PRICING = {
    "gemini-2.5-flash-lite": (0.10, 0.40),
    "gemini-2.5-flash": (0.30, 2.50),
    "gemini-2.5-pro": (1.25, 10.00),
}

# Finish reasons that mean the model would not produce this content.
_BLOCKED = {
    types.FinishReason.SAFETY,
    types.FinishReason.RECITATION,
    types.FinishReason.BLOCKLIST,
    types.FinishReason.PROHIBITED_CONTENT,
    types.FinishReason.SPII,
}
# The SDK does not retry: every retry counts against the per-minute quota, and the
# pipeline already tries an article again on a later run.


class GeminiProvider:
    def __init__(self, *, api_key: str, model: str = "", client: genai.Client | None = None):
        self.model = model or DEFAULT_MODEL
        self.client = client or genai.Client(
            api_key=api_key or None,  # falls back to GEMINI_API_KEY / GOOGLE_API_KEY
            http_options=types.HttpOptions(
                timeout=120_000, retry_options=types.HttpRetryOptions(attempts=1)
            ),
        )

    def _usage(self, response) -> AIUsageInfo:
        meta = response.usage_metadata
        input_tokens = (meta.prompt_token_count or 0) if meta else 0
        # Thinking tokens are billed as output.
        output_tokens = (
            (meta.candidates_token_count or 0) + (meta.thoughts_token_count or 0) if meta else 0
        )
        in_rate, out_rate = PRICING.get(self.model, max(PRICING.values()))
        return AIUsageInfo(
            model=self.model,
            input_tokens=input_tokens,
            output_tokens=output_tokens,
            cost_usd=(input_tokens * in_rate + output_tokens * out_rate) / 1_000_000,
        )

    def _call(self, *, system: str, contents, max_tokens: int, schema: type[BaseModel] | None):
        config = types.GenerateContentConfig(
            system_instruction=system,
            max_output_tokens=max_tokens,
            # No tools are offered, so there is nothing for the SDK to call automatically.
            automatic_function_calling=types.AutomaticFunctionCallingConfig(disable=True),
        )
        if schema is not None:
            config.response_mime_type = "application/json"
            config.response_schema = schema
        try:
            response = self.client.models.generate_content(
                model=self.model, contents=contents, config=config
            )
        except errors.ClientError as e:
            if e.code == 429:
                raise AIRateLimited(
                    "Gemini quota reached: " + (e.message or "").split("\n")[0]
                ) from e
            if e.code in (401, 403, 404) or "API key" in (e.message or ""):
                # A bad key or an unknown/retired model: nothing about the article is wrong.
                raise AIConfigError(
                    f"Gemini rejected the key or model {self.model!r} ({e.code}): {e.message}"
                ) from e
            raise AIError(f"Gemini rejected the request ({e.code}): {e.message}") from e
        except errors.APIError as e:
            raise AIUnavailable(f"Gemini is unavailable ({e.code}): {e.message}") from e
        except Exception as e:  # network failures surface as httpx errors
            raise AIUnavailable(f"could not reach the Gemini API: {type(e).__name__}") from e

        feedback = response.prompt_feedback
        if feedback is not None and feedback.block_reason:
            raise AIRefused(f"the model declined this content ({feedback.block_reason.name})")
        candidate = response.candidates[0] if response.candidates else None
        if candidate is None:
            raise AIError("the model returned no answer")
        if candidate.finish_reason in _BLOCKED:
            raise AIRefused(f"the model declined this content ({candidate.finish_reason.name})")
        if candidate.finish_reason == types.FinishReason.MAX_TOKENS:
            raise AIError("response was cut off at max_output_tokens")
        return response

    def _structured[T: BaseModel](
        self, schema: type[T], *, system: str, user: str, max_tokens: int
    ) -> tuple[T, AIUsageInfo]:
        response = self._call(system=system, contents=user, max_tokens=max_tokens, schema=schema)
        parsed = response.parsed
        if not isinstance(parsed, schema):
            raise AIError("the model returned output that does not match the expected format")
        return parsed, self._usage(response)

    def enrich_article(
        self, *, title: str, text: str, source: str
    ) -> tuple[ArticleEnrichment, AIUsageInfo]:
        user = (
            f"<publisher>{source}</publisher>\n<title>{title}</title>\n"
            f"<article_text>\n{text}\n</article_text>"
        )
        return self._structured(
            ArticleEnrichment, system=ENRICH_SYSTEM, user=user, max_tokens=16000
        )

    def explain_sentence(
        self, *, sentence: str, context: str, level: str
    ) -> tuple[SentenceExplanationResult, AIUsageInfo]:
        user = f"<reader_level>{level}</reader_level>\n"
        if context:
            user += f"<article_context>{context}</article_context>\n"
        user += f"<sentence>{sentence}</sentence>"
        return self._structured(
            SentenceExplanationResult, system=EXPLAIN_SYSTEM, user=user, max_tokens=8000
        )

    def answer_question(
        self, *, material: str, question: str, history: list[dict], level: str
    ) -> tuple[str, AIUsageInfo]:
        def turn(role: str, text: str) -> types.Content:
            return types.Content(role=role, parts=[types.Part.from_text(text=text)])

        contents = [
            turn(
                "user",
                f"<study_notes>\n{material}\n</study_notes>\n"
                f"<reader_level>{level}</reader_level>\n"
                "I will now ask questions about this article.",
            ),
            turn("model", "Ready. What would you like to know?"),
            # Gemini calls the assistant's side of the conversation "model".
            *(turn("user" if t["role"] == "user" else "model", t["content"]) for t in history),
            turn("user", question),
        ]
        response = self._call(system=TUTOR_SYSTEM, contents=contents, max_tokens=8000, schema=None)
        text = (response.text or "").strip()
        if not text:
            raise AIError("the model returned an empty answer")
        return text, self._usage(response)
