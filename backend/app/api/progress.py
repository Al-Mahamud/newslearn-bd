from datetime import timedelta

from fastapi import APIRouter
from sqlalchemy import func, select

from app.deps import CurrentUser, DbSession
from app.models import (
    CATEGORIES,
    QuizAttempt,
    ReadingHistory,
    SavedArticle,
    UserWord,
    utcnow,
)
from app.schemas import DayActivity, ProgressResponse, TopicAccuracy
from app.services import srs
from app.services.timeutil import local_date, local_today

router = APIRouter(tags=["progress"])

# Topics with fewer answers than this are too noisy to call a weakness.
MIN_ANSWERS_FOR_WEAKEST = 5


@router.get("/me/progress", response_model=ProgressResponse)
def my_progress(db: DbSession, user: CurrentUser):
    today = local_today()

    read_days = dict(
        db.execute(
            select(ReadingHistory.read_on, func.count())
            .where(ReadingHistory.user_id == user.id)
            .group_by(ReadingHistory.read_on)
        ).all()
    )
    attempts = db.scalars(select(QuizAttempt).where(QuizAttempt.user_id == user.id)).all()
    quiz_days: dict = {}
    topic_totals: dict[str, list[int]] = {}
    for attempt in attempts:
        day = local_date(attempt.submitted_at)
        quiz_days[day] = quiz_days.get(day, 0) + 1
        for answer in attempt.answers:
            totals = topic_totals.setdefault(answer["category"], [0, 0])
            totals[0] += 1
            totals[1] += bool(answer["correct"])

    # A streak counts back from today; a day not yet studied does not break it.
    active = set(read_days) | set(quiz_days)
    streak = 0
    cursor = today if today in active else today - timedelta(days=1)
    while cursor in active:
        streak += 1
        cursor -= timedelta(days=1)

    # Words marked "I know it" were never studied here, so they are left out of every count.
    words = db.execute(
        select(UserWord.box, UserWord.due_at, UserWord.saved_at).where(
            UserWord.user_id == user.id, UserWord.known.is_(False)
        )
    ).all()
    now = utcnow()

    topics = [
        TopicAccuracy(
            category=slug,
            label=CATEGORIES.get(slug, slug),
            answered=answered,
            correct=correct,
            percent=round(100 * correct / answered),
        )
        for slug, (answered, correct) in sorted(topic_totals.items())
        if answered
    ]
    rated = [t for t in topics if t.answered >= MIN_ANSWERS_FOR_WEAKEST]
    total_score = sum(a.score for a in attempts)
    total_questions = sum(a.total for a in attempts)

    return ProgressResponse(
        streak_days=streak,
        articles_read=sum(read_days.values()),
        articles_read_today=read_days.get(today, 0),
        saved_articles=db.scalar(
            select(func.count()).select_from(SavedArticle).where(SavedArticle.user_id == user.id)
        )
        or 0,
        words_saved=len(words),
        words_learned=sum(1 for box, _, _ in words if box >= srs.LEARNED_BOX),
        words_due=sum(1 for _, due_at, _ in words if due_at <= now),
        words_saved_today=sum(1 for _, _, saved_at in words if local_date(saved_at) == today),
        quizzes_today=quiz_days.get(today, 0),
        daily_article_goal=user.daily_article_goal,
        daily_word_goal=user.daily_word_goal,
        quizzes_taken=len(attempts),
        average_score_percent=round(100 * total_score / total_questions) if total_questions else 0,
        topics=topics,
        weakest_topic=min(rated, key=lambda t: t.percent).category if rated else None,
        last_7_days=[
            DayActivity(
                day=day,
                articles_read=read_days.get(day, 0),
                quizzes_taken=quiz_days.get(day, 0),
            )
            for day in (today - timedelta(days=offset) for offset in range(6, -1, -1))
        ],
    )
