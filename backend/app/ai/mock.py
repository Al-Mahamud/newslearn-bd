"""Offline stand-in for the model, used in development and tests.

It needs no API key and makes no network calls. Output is derived mechanically from the
input and clearly labelled, so it can never be mistaken for real study material.
"""

import re

from app.ai.base import (
    AIUsageInfo,
    ArticleEnrichment,
    FactItem,
    MCQItem,
    PhraseMeaning,
    SentenceExplanationResult,
    VocabularyItem,
)

MODEL = "mock"
TAG = "[mock]"

_KEYWORDS = {
    "economy": (
        "inflation",
        "gdp",
        "bank",
        "export",
        "import",
        "remittance",
        "budget",
        "imf",
        "economy",
        "economic",
        "investment",
        "taka",
        "revenue",
        "trade",
    ),
    "sports": ("cricket", "football", "match", "tournament", "wicket", "goal", "olympic"),
    "environment": ("climate", "flood", "cyclone", "pollution", "emission", "forest", "river"),
    "science_technology": (
        "technology",
        "satellite",
        "software",
        "research",
        "scientist",
        "ai",
        "internet",
        "digital",
    ),
    "international": (
        "un",
        "united nations",
        "summit",
        "foreign",
        "bilateral",
        "war",
        "india",
        "china",
        "usa",
        "myanmar",
    ),
    "bangladesh": (
        "dhaka",
        "bangladesh",
        "government",
        "minister",
        "parliament",
        "upazila",
        "election",
    ),
}


def _sentences(text: str) -> list[str]:
    return [s.strip() for s in re.split(r"(?<=[.!?])\s+", text) if s.strip()]


def _classify(text: str) -> str:
    words = set(re.findall(r"[a-z]+", text.lower()))
    for category, keys in _KEYWORDS.items():
        if words & set(keys):
            return category
    return "other"


class MockProvider:
    def enrich_article(
        self, *, title: str, text: str, source: str
    ) -> tuple[ArticleEnrichment, AIUsageInfo]:
        sentences = _sentences(text) or [title]
        lead = " ".join(sentences[:2])

        seen: set[str] = set()
        vocabulary = []
        for sentence in sentences:
            for word in re.findall(r"\b[a-z]{9,}\b", sentence):
                if word in seen:
                    continue
                seen.add(word)
                vocabulary.append(
                    VocabularyItem(
                        word=word,
                        part_of_speech="noun",
                        meaning_en=f"{TAG} meaning of '{word}'",
                        meaning_bn=f"{TAG} '{word}' শব্দের অর্থ",
                        example_sentence=f"{TAG} An example sentence using {word}.",
                        context_sentence=sentence[:500],
                        difficulty="medium",
                        synonyms=[f"{TAG} similar to {word}"],
                    )
                )
        vocabulary = vocabulary[:5]

        facts = [
            FactItem(kind="number", text=n, detail=f"{TAG} figure mentioned in the article")
            for n in dict.fromkeys(re.findall(r"\b\d[\d,.]*%?", text))
        ][:3]
        facts.append(FactItem(kind="organization", text=source, detail="Publisher"))

        question = MCQItem(
            question=f'{TAG} Which publisher reported: "{title[:120]}"?',
            options=[source, "Example Times", "Sample Herald", "Placeholder Post"],
            correct_index=0,
            explanation=f"The article was published by {source}.",
        )
        category = _classify(f"{title} {text}")
        return (
            ArticleEnrichment(
                category=category,
                summary=f"{TAG} {lead}",
                easy_summary=f"{TAG} In simple words: {sentences[0]}",
                bangla_summary=f"{TAG} বাংলা সারাংশ: {title}",
                vocabulary=vocabulary,
                facts=facts,
                exam_importance=70 if category in ("economy", "international") else 40,
                exam_reason=f"{TAG} Placeholder relevance note.",
                questions=[question],
            ),
            AIUsageInfo(model=MODEL),
        )

    def explain_sentence(
        self, *, sentence: str, context: str, level: str
    ) -> tuple[SentenceExplanationResult, AIUsageInfo]:
        hard = list(dict.fromkeys(re.findall(r"\b[A-Za-z]{9,}\b", sentence)))[:4]
        return (
            SentenceExplanationResult(
                simple_english=f"{TAG} Simple meaning ({level}): {sentence}",
                bangla=f"{TAG} বাংলা অর্থ: {sentence}",
                words=[
                    PhraseMeaning(
                        text=w, meaning_en=f"{TAG} meaning of '{w}'", meaning_bn=f"{TAG} '{w}' অর্থ"
                    )
                    for w in hard
                ],
                grammar_note="",
                example=f"{TAG} A simpler example sentence.",
            ),
            AIUsageInfo(model=MODEL),
        )

    def answer_question(
        self, *, material: str, question: str, history: list[dict], level: str
    ) -> tuple[str, AIUsageInfo]:
        first_line = material.strip().splitlines()[0] if material.strip() else ""
        return (
            f"{TAG} You asked: {question.strip()} — the notes say: {first_line}",
            AIUsageInfo(model=MODEL),
        )
