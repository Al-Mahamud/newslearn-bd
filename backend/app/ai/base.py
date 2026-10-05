"""Provider-neutral contract for everything the app asks a language model to do."""

from dataclasses import dataclass
from typing import Literal, Protocol

from pydantic import BaseModel, Field

Category = Literal[
    "bangladesh",
    "international",
    "economy",
    "science_technology",
    "environment",
    "sports",
    "other",
]
FactKind = Literal["fact", "number", "organization", "person", "place", "date"]
Difficulty = Literal["easy", "medium", "hard"]
DIFFICULTY_SCORE = {"easy": 1, "medium": 2, "hard": 3}


class VocabularyItem(BaseModel):
    word: str = Field(description="Dictionary form of the word or short phrase, lowercase")
    part_of_speech: str
    meaning_en: str = Field(description="Plain-English meaning as used in this article")
    meaning_bn: str = Field(description="Bangla meaning, written in Bangla script")
    example_sentence: str = Field(description="A new, simple sentence using the word")
    context_sentence: str = Field(description="The sentence from the article using the word")
    difficulty: Difficulty


class FactItem(BaseModel):
    kind: FactKind
    text: str = Field(description="The fact, figure or name")
    detail: str = Field(description="One short line of context; empty if none is needed")


class MCQItem(BaseModel):
    question: str
    options: list[str] = Field(description="Exactly four answer options")
    correct_index: int = Field(description="Zero-based index of the correct option")
    explanation: str


class ArticleEnrichment(BaseModel):
    category: Category
    summary: str
    easy_summary: str
    bangla_summary: str
    vocabulary: list[VocabularyItem]
    facts: list[FactItem]
    exam_importance: int = Field(description="0-100")
    exam_reason: str
    questions: list[MCQItem]


class PhraseMeaning(BaseModel):
    text: str
    meaning_en: str
    meaning_bn: str


class SentenceExplanationResult(BaseModel):
    simple_english: str
    bangla: str
    words: list[PhraseMeaning]
    grammar_note: str = Field(description="Empty when the structure needs no explanation")
    example: str


@dataclass
class AIUsageInfo:
    model: str
    input_tokens: int = 0
    output_tokens: int = 0
    cost_usd: float = 0.0


class AIError(Exception):
    """The provider call failed; worth retrying later."""


class AIRateLimited(AIError):
    """The provider's quota is used up for now; wait before sending anything else."""


class AIConfigError(AIError):
    """The key or model name is wrong; every call will fail until the settings change."""


class AIRefused(AIError):
    """The provider declined this content; retrying will not help."""


class AIProvider(Protocol):
    def enrich_article(
        self, *, title: str, text: str, source: str
    ) -> tuple[ArticleEnrichment, AIUsageInfo]: ...

    def explain_sentence(
        self, *, sentence: str, context: str, level: str
    ) -> tuple[SentenceExplanationResult, AIUsageInfo]: ...

    def answer_question(
        self, *, material: str, question: str, history: list[dict], level: str
    ) -> tuple[str, AIUsageInfo]: ...
