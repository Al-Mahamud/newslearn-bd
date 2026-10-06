from datetime import UTC, date, datetime

from sqlalchemy import (
    JSON,
    Boolean,
    Date,
    DateTime,
    Float,
    ForeignKey,
    Index,
    Integer,
    String,
    Text,
    UniqueConstraint,
)
from sqlalchemy.orm import Mapped, mapped_column, relationship
from sqlalchemy.types import TypeDecorator

from app.db import Base

CATEGORIES: dict[str, str] = {
    "bangladesh": "Bangladesh",
    "international": "International",
    "economy": "Economy",
    "science_technology": "Science & Technology",
    "environment": "Environment & Climate",
    "sports": "Sports",
    "other": "Other",
}

# Articles at or above this score appear under "Exam Important".
EXAM_IMPORTANT_THRESHOLD = 60

ENGLISH_LEVELS = ("beginner", "intermediate", "advanced")


def utcnow() -> datetime:
    return datetime.now(UTC)


class UTCDateTime(TypeDecorator):
    """Timezone-aware UTC datetimes on every backend (SQLite drops tzinfo)."""

    impl = DateTime(timezone=True)
    cache_ok = True

    def process_bind_param(self, value, dialect):
        if value is not None and value.tzinfo is None:
            value = value.replace(tzinfo=UTC)
        return value

    def process_result_value(self, value, dialect):
        if value is not None and value.tzinfo is None:
            value = value.replace(tzinfo=UTC)
        return value


class User(Base):
    __tablename__ = "users"

    id: Mapped[int] = mapped_column(primary_key=True)
    email: Mapped[str] = mapped_column(String(255), unique=True, index=True)
    password_hash: Mapped[str] = mapped_column(String(255))
    display_name: Mapped[str] = mapped_column(String(100), default="")
    english_level: Mapped[str] = mapped_column(String(20), default="intermediate")
    preferred_categories: Mapped[list] = mapped_column(JSON, default=list)
    # Daily targets shown as the goal ring on the app's Today screen.
    daily_article_goal: Mapped[int] = mapped_column(Integer, default=5, server_default="5")
    daily_word_goal: Mapped[int] = mapped_column(Integer, default=5, server_default="5")
    is_admin: Mapped[bool] = mapped_column(Boolean, default=False)
    created_at: Mapped[datetime] = mapped_column(UTCDateTime, default=utcnow)


class RefreshToken(Base):
    __tablename__ = "refresh_tokens"

    id: Mapped[int] = mapped_column(primary_key=True)
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"), index=True)
    token_hash: Mapped[str] = mapped_column(String(64), unique=True)
    expires_at: Mapped[datetime] = mapped_column(UTCDateTime)
    revoked_at: Mapped[datetime | None] = mapped_column(UTCDateTime, nullable=True)
    created_at: Mapped[datetime] = mapped_column(UTCDateTime, default=utcnow)


class Source(Base):
    __tablename__ = "sources"

    id: Mapped[int] = mapped_column(primary_key=True)
    slug: Mapped[str] = mapped_column(String(60), unique=True)
    name: Mapped[str] = mapped_column(String(120))
    feed_url: Mapped[str] = mapped_column(String(500))
    homepage: Mapped[str] = mapped_column(String(500), default="")
    default_category: Mapped[str | None] = mapped_column(String(30), nullable=True)
    enabled: Mapped[bool] = mapped_column(Boolean, default=True)
    # Off by default: only the text the publisher puts in its feed is used. Turn on per source
    # once you have confirmed the publisher's terms allow fetching article pages.
    fetch_full_text: Mapped[bool] = mapped_column(Boolean, default=False)
    etag: Mapped[str | None] = mapped_column(String(255), nullable=True)
    last_modified: Mapped[str | None] = mapped_column(String(255), nullable=True)
    last_fetched_at: Mapped[datetime | None] = mapped_column(UTCDateTime, nullable=True)
    last_status: Mapped[str] = mapped_column(String(255), default="")


class Article(Base):
    __tablename__ = "articles"

    id: Mapped[int] = mapped_column(primary_key=True)
    source_id: Mapped[int] = mapped_column(ForeignKey("sources.id"), index=True)
    title: Mapped[str] = mapped_column(String(500))
    url: Mapped[str] = mapped_column(String(1000))
    url_hash: Mapped[str] = mapped_column(String(64), unique=True)
    # Short feed excerpt shown in the feed; the full article is never stored or served.
    excerpt: Mapped[str] = mapped_column(Text, default="")
    # Feed-provided body kept only until AI processing, then cleared.
    source_text: Mapped[str] = mapped_column(Text, default="")
    image_url: Mapped[str | None] = mapped_column(String(1000), nullable=True)
    author: Mapped[str | None] = mapped_column(String(200), nullable=True)
    published_at: Mapped[datetime] = mapped_column(UTCDateTime, index=True)
    created_at: Mapped[datetime] = mapped_column(UTCDateTime, default=utcnow)

    # pending -> ready | failed | skipped (duplicate or too little text)
    status: Mapped[str] = mapped_column(String(20), default="pending", index=True)
    duplicate_of_id: Mapped[int | None] = mapped_column(ForeignKey("articles.id"), nullable=True)
    attempts: Mapped[int] = mapped_column(Integer, default=0)
    error: Mapped[str | None] = mapped_column(Text, nullable=True)

    category: Mapped[str] = mapped_column(String(30), default="other", index=True)
    summary: Mapped[str] = mapped_column(Text, default="")
    easy_summary: Mapped[str] = mapped_column(Text, default="")
    bangla_summary: Mapped[str] = mapped_column(Text, default="")
    exam_importance: Mapped[int] = mapped_column(Integer, default=0, index=True)
    exam_reason: Mapped[str] = mapped_column(Text, default="")
    ai_model: Mapped[str | None] = mapped_column(String(60), nullable=True)
    prompt_version: Mapped[str | None] = mapped_column(String(20), nullable=True)
    processed_at: Mapped[datetime | None] = mapped_column(UTCDateTime, nullable=True)

    source: Mapped[Source] = relationship(lazy="joined")
    facts: Mapped[list["ArticleFact"]] = relationship(
        back_populates="article", cascade="all, delete-orphan", order_by="ArticleFact.id"
    )
    words: Mapped[list["ArticleWord"]] = relationship(
        back_populates="article", cascade="all, delete-orphan", order_by="ArticleWord.position"
    )
    questions: Mapped[list["Question"]] = relationship(
        back_populates="article", cascade="all, delete-orphan", order_by="Question.id"
    )

    __table_args__ = (Index("ix_articles_feed", "status", "published_at"),)


class ArticleFact(Base):
    __tablename__ = "article_facts"

    id: Mapped[int] = mapped_column(primary_key=True)
    article_id: Mapped[int] = mapped_column(
        ForeignKey("articles.id", ondelete="CASCADE"), index=True
    )
    # fact | number | organization | person | place | date
    kind: Mapped[str] = mapped_column(String(20))
    text: Mapped[str] = mapped_column(String(500))
    detail: Mapped[str] = mapped_column(String(500), default="")

    article: Mapped[Article] = relationship(back_populates="facts")


class Word(Base):
    """One dictionary entry per word, shared by every article that uses it."""

    __tablename__ = "words"

    id: Mapped[int] = mapped_column(primary_key=True)
    lemma: Mapped[str] = mapped_column(String(120), unique=True)
    part_of_speech: Mapped[str] = mapped_column(String(30), default="")
    meaning_en: Mapped[str] = mapped_column(String(500))
    meaning_bn: Mapped[str] = mapped_column(String(500))
    example_sentence: Mapped[str] = mapped_column(String(600), default="")
    difficulty: Mapped[int] = mapped_column(Integer, default=2)  # 1 easy .. 3 hard
    # Up to four near-synonyms a learner could meet in the same contexts.
    synonyms: Mapped[list] = mapped_column(JSON, default=list, server_default="[]")
    # How many articles used this word: the "newspaper frequency" signal.
    occurrences: Mapped[int] = mapped_column(Integer, default=0)
    created_at: Mapped[datetime] = mapped_column(UTCDateTime, default=utcnow)


class ArticleWord(Base):
    __tablename__ = "article_words"

    article_id: Mapped[int] = mapped_column(
        ForeignKey("articles.id", ondelete="CASCADE"), primary_key=True
    )
    word_id: Mapped[int] = mapped_column(
        ForeignKey("words.id", ondelete="CASCADE"), primary_key=True, index=True
    )
    position: Mapped[int] = mapped_column(Integer, default=0)
    # The sentence from this article's summary in which the word is used.
    context_sentence: Mapped[str] = mapped_column(String(600), default="")

    article: Mapped[Article] = relationship(back_populates="words")
    word: Mapped[Word] = relationship(lazy="joined")


class UserWord(Base):
    __tablename__ = "user_words"

    user_id: Mapped[int] = mapped_column(
        ForeignKey("users.id", ondelete="CASCADE"), primary_key=True
    )
    word_id: Mapped[int] = mapped_column(
        ForeignKey("words.id", ondelete="CASCADE"), primary_key=True
    )
    article_id: Mapped[int | None] = mapped_column(
        ForeignKey("articles.id", ondelete="SET NULL"), nullable=True
    )
    saved_at: Mapped[datetime] = mapped_column(UTCDateTime, default=utcnow)
    # Leitner box 0..5; box 5 means learned.
    box: Mapped[int] = mapped_column(Integer, default=0)
    due_at: Mapped[datetime] = mapped_column(UTCDateTime, default=utcnow, index=True)
    review_count: Mapped[int] = mapped_column(Integer, default=0)
    correct_count: Mapped[int] = mapped_column(Integer, default=0)
    last_reviewed_at: Mapped[datetime | None] = mapped_column(UTCDateTime, nullable=True)
    # "I know it": the reader already knows this word, so it is neither highlighted nor
    # reviewed, and does not count as a word they learned here.
    known: Mapped[bool] = mapped_column(Boolean, default=False, server_default="false")

    word: Mapped[Word] = relationship(lazy="joined")


class SavedArticle(Base):
    __tablename__ = "saved_articles"

    user_id: Mapped[int] = mapped_column(
        ForeignKey("users.id", ondelete="CASCADE"), primary_key=True
    )
    article_id: Mapped[int] = mapped_column(
        ForeignKey("articles.id", ondelete="CASCADE"), primary_key=True
    )
    saved_at: Mapped[datetime] = mapped_column(UTCDateTime, default=utcnow)


class ReadingHistory(Base):
    __tablename__ = "reading_history"

    user_id: Mapped[int] = mapped_column(
        ForeignKey("users.id", ondelete="CASCADE"), primary_key=True
    )
    article_id: Mapped[int] = mapped_column(
        ForeignKey("articles.id", ondelete="CASCADE"), primary_key=True
    )
    read_at: Mapped[datetime] = mapped_column(UTCDateTime, default=utcnow)
    # Local (Asia/Dhaka) calendar day, used for streaks.
    read_on: Mapped[date] = mapped_column(Date, index=True)


class Question(Base):
    __tablename__ = "questions"

    id: Mapped[int] = mapped_column(primary_key=True)
    article_id: Mapped[int] = mapped_column(
        ForeignKey("articles.id", ondelete="CASCADE"), index=True
    )
    category: Mapped[str] = mapped_column(String(30), index=True)
    text: Mapped[str] = mapped_column(String(600))
    options: Mapped[list] = mapped_column(JSON)
    correct_index: Mapped[int] = mapped_column(Integer)
    explanation: Mapped[str] = mapped_column(String(800), default="")
    created_at: Mapped[datetime] = mapped_column(UTCDateTime, default=utcnow)

    article: Mapped[Article] = relationship(back_populates="questions")


class Quiz(Base):
    __tablename__ = "quizzes"

    id: Mapped[int] = mapped_column(primary_key=True)
    # daily | weekly are shared by everyone; article | practice | mock belong to one user.
    kind: Mapped[str] = mapped_column(String(20))
    title: Mapped[str] = mapped_column(String(200))
    user_id: Mapped[int | None] = mapped_column(
        ForeignKey("users.id", ondelete="CASCADE"), nullable=True, index=True
    )
    category: Mapped[str | None] = mapped_column(String(30), nullable=True)
    for_date: Mapped[date | None] = mapped_column(Date, nullable=True)
    time_limit_seconds: Mapped[int | None] = mapped_column(Integer, nullable=True)
    created_at: Mapped[datetime] = mapped_column(UTCDateTime, default=utcnow)

    items: Mapped[list["QuizItem"]] = relationship(
        cascade="all, delete-orphan", order_by="QuizItem.position"
    )

    __table_args__ = (Index("ix_quizzes_shared", "kind", "for_date"),)


class QuizItem(Base):
    __tablename__ = "quiz_items"

    quiz_id: Mapped[int] = mapped_column(
        ForeignKey("quizzes.id", ondelete="CASCADE"), primary_key=True
    )
    question_id: Mapped[int] = mapped_column(
        ForeignKey("questions.id", ondelete="CASCADE"), primary_key=True
    )
    position: Mapped[int] = mapped_column(Integer)

    question: Mapped[Question] = relationship(lazy="joined")


class QuizAttempt(Base):
    __tablename__ = "quiz_attempts"

    id: Mapped[int] = mapped_column(primary_key=True)
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"), index=True)
    quiz_id: Mapped[int] = mapped_column(ForeignKey("quizzes.id", ondelete="CASCADE"), index=True)
    score: Mapped[int] = mapped_column(Integer)
    total: Mapped[int] = mapped_column(Integer)
    duration_seconds: Mapped[int | None] = mapped_column(Integer, nullable=True)
    # [{question_id, category, selected_index, correct}]
    answers: Mapped[list] = mapped_column(JSON)
    submitted_at: Mapped[datetime] = mapped_column(UTCDateTime, default=utcnow)

    quiz: Mapped[Quiz] = relationship(lazy="joined")


class SentenceExplanation(Base):
    """Cache: the same sentence at the same level is only sent to the model once."""

    __tablename__ = "sentence_explanations"

    id: Mapped[int] = mapped_column(primary_key=True)
    text_hash: Mapped[str] = mapped_column(String(64))
    level: Mapped[str] = mapped_column(String(20))
    sentence: Mapped[str] = mapped_column(Text)
    result: Mapped[dict] = mapped_column(JSON)
    created_at: Mapped[datetime] = mapped_column(UTCDateTime, default=utcnow)

    __table_args__ = (UniqueConstraint("text_hash", "level"),)


class AIUsage(Base):
    __tablename__ = "ai_usage"

    id: Mapped[int] = mapped_column(primary_key=True)
    day: Mapped[date] = mapped_column(Date, index=True)
    purpose: Mapped[str] = mapped_column(String(30))  # enrich | explain | tutor
    user_id: Mapped[int | None] = mapped_column(Integer, nullable=True)
    model: Mapped[str] = mapped_column(String(60))
    input_tokens: Mapped[int] = mapped_column(Integer, default=0)
    output_tokens: Mapped[int] = mapped_column(Integer, default=0)
    cost_usd: Mapped[float] = mapped_column(Float, default=0.0)
    created_at: Mapped[datetime] = mapped_column(UTCDateTime, default=utcnow)

    __table_args__ = (Index("ix_ai_usage_user_day", "user_id", "day", "purpose"),)
