import logging

import anthropic
from pydantic import BaseModel

from app.ai.base import (
    AIConfigError,
    AIError,
    AIRefused,
    AIUsageInfo,
    ArticleEnrichment,
    SentenceExplanationResult,
)
from app.ai.prompts import ENRICH_SYSTEM, EXPLAIN_SYSTEM, TUTOR_SYSTEM

log = logging.getLogger(__name__)

# USD per million tokens (input, output). Unknown models are costed at the highest rate
# so the daily budget errs on the safe side.
PRICING = {
    "claude-opus-5-5": (4.0, 20.0),
    "claude-sonnet-5-5": (2.0, 10.0),
    "claude-haiku-4-5": (1.0, 5.0),
}


def estimate_cost(model: str, usage) -> float:
    in_rate, out_rate = PRICING.get(model, max(PRICING.values()))
    cache_write = getattr(usage, "cache_creation_input_tokens", 0) or 0
    cache_read = getattr(usage, "cache_read_input_tokens", 0) or 0
    billed_input = usage.input_tokens + cache_write * 1.25 + cache_read * 0.1
    return (billed_input * in_rate + usage.output_tokens * out_rate) / 1_000_000


class ClaudeProvider:
    def __init__(self, *, api_key: str, model: str, effort: str):
        # With no explicit key the SDK resolves credentials from the environment.
        self.client = anthropic.Anthropic(api_key=api_key or None, max_retries=3)
        self.model = model
        self.effort = effort

    def _options(self) -> dict:
        # Haiku 4.5 does not accept the effort parameter.
        if "haiku" in self.model:
            return {}
        return {"output_config": {"effort": self.effort}}

    def _usage(self, response) -> AIUsageInfo:
        u = response.usage
        return AIUsageInfo(
            model=self.model,
            input_tokens=u.input_tokens
            + (getattr(u, "cache_creation_input_tokens", 0) or 0)
            + (getattr(u, "cache_read_input_tokens", 0) or 0),
            output_tokens=u.output_tokens,
            cost_usd=estimate_cost(self.model, u),
        )

    def _call(self, *, system: str, messages: list[dict], max_tokens: int, schema=None):
        kwargs = {
            "model": self.model,
            "max_tokens": max_tokens,
            # The instructions are identical on every call, so they are the cached prefix.
            "system": [{"type": "text", "text": system, "cache_control": {"type": "ephemeral"}}],
            "messages": messages,
            **self._options(),
        }
        try:
            if schema is not None:
                response = self.client.messages.parse(output_format=schema, **kwargs)
            else:
                response = self.client.messages.create(**kwargs)
        except (
            anthropic.AuthenticationError,
            anthropic.PermissionDeniedError,
            anthropic.NotFoundError,
        ) as e:
            raise AIConfigError(
                f"Claude rejected the key or model {self.model!r}: {e.message}"
            ) from e
        except anthropic.RateLimitError as e:
            raise AIError(f"rate limited: {e.message}") from e
        except anthropic.APIStatusError as e:
            raise AIError(f"API error {e.status_code}: {e.message}") from e
        except anthropic.APIConnectionError as e:
            raise AIError("could not reach the Anthropic API") from e

        if response.stop_reason == "refusal":
            raise AIRefused("the model declined this content")
        if response.stop_reason == "max_tokens":
            raise AIError("response was cut off at max_tokens")
        return response

    def _structured[T: BaseModel](
        self, schema: type[T], *, system: str, user: str, max_tokens: int
    ) -> tuple[T, AIUsageInfo]:
        response = self._call(
            system=system,
            messages=[{"role": "user", "content": user}],
            max_tokens=max_tokens,
            schema=schema,
        )
        parsed = response.parsed_output
        if parsed is None:
            raise AIError("the model returned no structured output")
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
        messages = [
            {
                "role": "user",
                "content": f"<study_notes>\n{material}\n</study_notes>\n"
                f"<reader_level>{level}</reader_level>\n"
                "I will now ask questions about this article.",
            },
            {"role": "assistant", "content": "Ready. What would you like to know?"},
            *history,
            {"role": "user", "content": question},
        ]
        response = self._call(system=TUTOR_SYSTEM, messages=messages, max_tokens=8000)
        text = "".join(b.text for b in response.content if b.type == "text").strip()
        if not text:
            raise AIError("the model returned an empty answer")
        return text, self._usage(response)
