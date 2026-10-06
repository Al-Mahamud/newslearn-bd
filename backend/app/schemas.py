from datetime import date, datetime
from typing import Literal

from pydantic import BaseModel, ConfigDict, EmailStr, Field, model_validator

EnglishLevel = Literal["beginner", "intermediate", "advanced"]


class ORM(BaseModel):
    model_config = ConfigDict(from_attributes=True)


# --- auth -----------------------------------------------------------------------------


class RegisterRequest(BaseModel):
    email: EmailStr
    password: str = Field(min_length=8, max_length=128)
    display_name: str = Field(default="", max_length=100)


class LoginRequest(BaseModel):
    email: EmailStr
    password: str = Field(max_length=128)


class RefreshRequest(BaseModel):
    refresh_token: str


class UserOut(ORM):
    id: int
    email: str
    display_name: str
    english_level: EnglishLevel
    preferred_categories: list[str]
    daily_article_goal: int
    daily_word_goal: int
    is_admin: bool


class TokenResponse(BaseModel):
    access_token: str
    refresh_token: str
    token_type: str = "bearer"
    expires_in: int
    user: UserOut


class UpdateProfileRequest(BaseModel):
    display_name: str | None = Field(default=None, max_length=100)
    english_level: EnglishLevel | None = None
    preferred_categories: list[str] | None = None
    daily_article_goal: int | None = Field(default=None, ge=1, le=20)
    daily_word_goal: int | None = Field(default=None, ge=1, le=30)


# --- articles -------------------------------------------------------------------------


class CategoryOut(BaseModel):
    slug: str
    label: str


class ArticleCard(BaseModel):
    id: int
    title: str
    source: str
    url: str
    image_url: str | None
    published_at: datetime
    category: str
    summary: str
    exam_importance: int
    exam_important: bool
    word_count: int
    saved: bool = False
    read: bool = False


class ArticlePage(BaseModel):
    items: list[ArticleCard]
    next_cursor: str | None


class WordOut(BaseModel):
    id: int
    word: str
    part_of_speech: str
    meaning_en: str
    meaning_bn: str
    example_sentence: str
    difficulty: int
    context_sentence: str = ""
    saved: bool = False
    # The reader marked it "I know it": do not highlight or teach it.
    known: bool = False
    synonyms: list[str] = []
    # How many articles have used this word.
    seen_in: int = 0


class FactOut(ORM):
    kind: str
    text: str
    detail: str


class ArticleDetail(ArticleCard):
    author: str | None
    easy_summary: str
    bangla_summary: str
    exam_reason: str
    vocabulary: list[WordOut]
    facts: list[FactOut]
    question_count: int
    ai_generated: bool = True


# --- vocabulary -----------------------------------------------------------------------


class SaveWordRequest(BaseModel):
    article_id: int | None = None


class UserWordOut(BaseModel):
    word: WordOut
    article_id: int | None
    saved_at: datetime
    box: int
    learned: bool
    due_at: datetime
    review_count: int


class ReviewRequest(BaseModel):
    """Send `rating`. `remembered` is the older two-answer form and is still accepted."""

    rating: Literal["forgot", "hard", "good"] | None = None
    remembered: bool | None = None

    @model_validator(mode="after")
    def _one_is_given(self):
        if self.rating is None and self.remembered is None:
            raise ValueError("rating is required")
        return self

    @property
    def resolved(self) -> str:
        return self.rating or ("good" if self.remembered else "forgot")


# --- learning -------------------------------------------------------------------------


class ExplainRequest(BaseModel):
    sentence: str = Field(min_length=3, max_length=1000)
    article_id: int | None = None


class PhraseOut(BaseModel):
    text: str
    meaning_en: str
    meaning_bn: str


class ExplainResponse(BaseModel):
    sentence: str
    simple_english: str
    bangla: str
    words: list[PhraseOut]
    grammar_note: str
    example: str
    cached: bool


class TutorTurn(BaseModel):
    role: Literal["user", "assistant"]
    content: str = Field(min_length=1, max_length=4000)


class TutorRequest(BaseModel):
    question: str = Field(min_length=1, max_length=1000)
    history: list[TutorTurn] = Field(default_factory=list, max_length=20)


class TutorResponse(BaseModel):
    answer: str


class DigestArticle(BaseModel):
    id: int
    title: str
    source: str
    summary: str
    exam_importance: int
    exam_reason: str
    facts: list[FactOut]


class DigestGroup(BaseModel):
    category: str
    label: str
    articles: list[DigestArticle]


class DigestResponse(BaseModel):
    period: Literal["daily", "weekly"]
    start: date
    end: date
    article_count: int
    groups: list[DigestGroup]
    # Every fact of the period regrouped by kind, for quick revision.
    revision: dict[str, list[FactOut]]


# --- quizzes --------------------------------------------------------------------------


class QuestionOut(BaseModel):
    id: int
    text: str
    options: list[str]
    category: str
    article_id: int


class QuizOut(BaseModel):
    id: int
    kind: str
    # False for mock exams: there the answers stay hidden until the end.
    instant_feedback: bool
    title: str
    category: str | None
    for_date: date | None
    time_limit_seconds: int | None
    questions: list[QuestionOut]


class CreateQuizRequest(BaseModel):
    kind: Literal["practice", "mock"] = "practice"
    category: str | None = None
    count: int | None = Field(default=None, ge=1, le=50)
    timed: bool = False


class AnswerIn(BaseModel):
    question_id: int
    selected_index: int | None = Field(default=None, ge=0, le=3)


class SubmitQuizRequest(BaseModel):
    answers: list[AnswerIn]
    duration_seconds: int | None = Field(default=None, ge=0)


class CheckAnswerRequest(BaseModel):
    question_id: int
    selected_index: int = Field(ge=0, le=3)


class CheckAnswerResponse(BaseModel):
    question_id: int
    correct: bool
    correct_index: int
    explanation: str
    article_id: int


class AnswerReview(BaseModel):
    question_id: int
    text: str
    options: list[str]
    selected_index: int | None
    correct_index: int
    correct: bool
    explanation: str
    article_id: int


class AttemptResult(BaseModel):
    attempt_id: int
    quiz_id: int
    quiz_title: str
    score: int
    total: int
    percent: int
    duration_seconds: int | None
    submitted_at: datetime
    review: list[AnswerReview]


class AttemptSummary(BaseModel):
    attempt_id: int
    quiz_id: int
    quiz_title: str
    kind: str
    score: int
    total: int
    percent: int
    submitted_at: datetime


# --- progress -------------------------------------------------------------------------


class TopicAccuracy(BaseModel):
    category: str
    label: str
    answered: int
    correct: int
    percent: int


class DayActivity(BaseModel):
    day: date
    articles_read: int
    quizzes_taken: int


class ProgressResponse(BaseModel):
    streak_days: int
    articles_read: int
    articles_read_today: int
    saved_articles: int
    words_saved: int
    words_learned: int
    words_due: int
    words_saved_today: int
    quizzes_today: int
    daily_article_goal: int
    daily_word_goal: int
    quizzes_taken: int
    average_score_percent: int
    topics: list[TopicAccuracy]
    weakest_topic: str | None
    last_7_days: list[DayActivity]


# --- admin ----------------------------------------------------------------------------


class SourceOut(ORM):
    id: int
    slug: str
    name: str
    feed_url: str
    homepage: str
    default_category: str | None
    enabled: bool
    fetch_full_text: bool
    last_fetched_at: datetime | None
    last_status: str


class SourceCreate(BaseModel):
    slug: str = Field(pattern=r"^[a-z0-9-]{2,60}$")
    name: str = Field(min_length=1, max_length=120)
    feed_url: str = Field(pattern=r"^https?://", max_length=500)
    homepage: str = Field(default="", max_length=500)
    default_category: str | None = None
    enabled: bool = True
    fetch_full_text: bool = False


class SourceUpdate(BaseModel):
    name: str | None = Field(default=None, max_length=120)
    feed_url: str | None = Field(default=None, pattern=r"^https?://", max_length=500)
    default_category: str | None = None
    enabled: bool | None = None
    fetch_full_text: bool | None = None


class SearchResponse(BaseModel):
    articles: list[ArticleCard]
    words: list[WordOut]
