import random
from datetime import date

from sqlalchemy import select
from sqlalchemy.orm import Session

from app.models import CATEGORIES, Article, Question, Quiz, QuizAttempt, QuizItem
from app.services.timeutil import day_bounds_utc, week_start

DAILY_QUESTIONS = 10
WEEKLY_QUESTIONS = 20
MOCK_QUESTIONS = 30
SECONDS_PER_QUESTION = 45


def _questions_between(db: Session, start: date, days: int, limit: int) -> list[Question]:
    """Questions from the period's articles, most exam-relevant first, spread across articles."""
    begin, end = day_bounds_utc(start, days)
    rows = db.scalars(
        select(Question)
        .join(Article, Question.article_id == Article.id)
        .where(Article.status == "ready", Article.published_at >= begin, Article.published_at < end)
        .order_by(Article.exam_importance.desc(), Article.id, Question.id)
    ).all()
    by_article: dict[int, list[Question]] = {}
    for q in rows:
        by_article.setdefault(q.article_id, []).append(q)
    picked: list[Question] = []
    # Round-robin: every article contributes one question before any contributes two.
    while len(picked) < limit and any(by_article.values()):
        for questions in by_article.values():
            if questions and len(picked) < limit:
                picked.append(questions.pop(0))
    return picked


def _create(db: Session, questions: list[Question], **fields) -> Quiz:
    quiz = Quiz(**fields)
    quiz.items = [QuizItem(question_id=q.id, position=i) for i, q in enumerate(questions)]
    db.add(quiz)
    db.commit()
    return quiz


def _shared(db: Session, kind: str, for_date: date) -> Quiz | None:
    return db.scalar(
        select(Quiz)
        .where(Quiz.kind == kind, Quiz.for_date == for_date, Quiz.user_id.is_(None))
        .order_by(Quiz.id)
        .limit(1)
    )


def get_or_create_daily(db: Session, day: date) -> Quiz | None:
    quiz = _shared(db, "daily", day)
    if quiz:
        return quiz
    questions = _questions_between(db, day, 1, DAILY_QUESTIONS)
    if not questions:
        return None
    return _create(db, questions, kind="daily", title=f"Daily quiz — {day:%d %b %Y}", for_date=day)


def get_or_create_weekly(db: Session, day: date) -> Quiz | None:
    start = week_start(day)
    quiz = _shared(db, "weekly", start)
    if quiz:
        return quiz
    questions = _questions_between(db, start, 7, WEEKLY_QUESTIONS)
    if not questions:
        return None
    return _create(
        db,
        questions,
        kind="weekly",
        title=f"Weekly current affairs — week of {start:%d %b %Y}",
        for_date=start,
    )


def create_custom(
    db: Session,
    *,
    user_id: int,
    kind: str,
    category: str | None,
    count: int | None,
    timed: bool,
) -> Quiz | None:
    """A practice set (optionally one topic) or a timed mock exam across all topics."""
    count = count or (MOCK_QUESTIONS if kind == "mock" else DAILY_QUESTIONS)
    query = select(Question.id)
    if category:
        query = query.where(Question.category == category)
    ids = list(db.scalars(query).all())
    if not ids:
        return None
    chosen = random.sample(ids, min(count, len(ids)))
    questions = db.scalars(select(Question).where(Question.id.in_(chosen))).all()
    random.shuffle(questions)
    label = CATEGORIES.get(category or "", "All topics")
    timed = timed or kind == "mock"
    return _create(
        db,
        list(questions),
        kind=kind,
        title="Mock exam" if kind == "mock" else f"Practice — {label}",
        user_id=user_id,
        category=category,
        time_limit_seconds=len(questions) * SECONDS_PER_QUESTION if timed else None,
    )


def create_for_article(db: Session, *, user_id: int, article: Article) -> Quiz | None:
    if not article.questions:
        return None
    return _create(
        db,
        list(article.questions),
        kind="article",
        title=article.title[:200],
        user_id=user_id,
        category=article.category,
    )


def grade(
    db: Session, *, quiz: Quiz, user_id: int, selected: dict[int, int | None], duration: int | None
) -> QuizAttempt:
    answers = []
    score = 0
    for item in quiz.items:
        q = item.question
        choice = selected.get(q.id)
        correct = choice is not None and choice == q.correct_index
        score += correct
        answers.append(
            {
                "question_id": q.id,
                "category": q.category,
                "selected_index": choice,
                "correct": correct,
            }
        )
    attempt = QuizAttempt(
        user_id=user_id,
        quiz_id=quiz.id,
        score=score,
        total=len(quiz.items),
        duration_seconds=duration,
        answers=answers,
    )
    db.add(attempt)
    db.commit()
    return attempt
