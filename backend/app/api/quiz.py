from datetime import date

from fastapi import APIRouter, HTTPException, Query, status
from sqlalchemy import select

from app.api.articles import get_ready_article
from app.deps import CurrentUser, DbSession
from app.models import CATEGORIES, Question, Quiz, QuizAttempt, User
from app.schemas import (
    AnswerReview,
    AttemptResult,
    AttemptSummary,
    CheckAnswerRequest,
    CheckAnswerResponse,
    CreateQuizRequest,
    QuestionOut,
    QuizOut,
    SubmitQuizRequest,
)
from app.services import quiz as quiz_service
from app.services.timeutil import local_today

router = APIRouter(tags=["quizzes"])


def _percent(score: int, total: int) -> int:
    return round(100 * score / total) if total else 0


def _quiz_out(quiz: Quiz) -> QuizOut:
    # Correct answers are only revealed after an attempt is submitted.
    return QuizOut(
        id=quiz.id,
        kind=quiz.kind,
        instant_feedback=quiz.kind != "mock",
        title=quiz.title,
        category=quiz.category,
        for_date=quiz.for_date,
        time_limit_seconds=quiz.time_limit_seconds,
        questions=[
            QuestionOut(
                id=item.question.id,
                text=item.question.text,
                options=item.question.options,
                category=item.question.category,
                article_id=item.question.article_id,
            )
            for item in quiz.items
        ],
    )


def _get_quiz(db, quiz_id: int, user: User) -> Quiz:
    quiz = db.get(Quiz, quiz_id)
    if quiz is None or quiz.user_id not in (None, user.id):
        raise HTTPException(status.HTTP_404_NOT_FOUND, "Quiz not found")
    return quiz


def _attempt_result(db, attempt: QuizAttempt) -> AttemptResult:
    ids = [a["question_id"] for a in attempt.answers]
    questions = {q.id: q for q in db.scalars(select(Question).where(Question.id.in_(ids)))}
    review = [
        AnswerReview(
            question_id=q.id,
            text=q.text,
            options=q.options,
            selected_index=a["selected_index"],
            correct_index=q.correct_index,
            correct=a["correct"],
            explanation=q.explanation,
            article_id=q.article_id,
        )
        for a in attempt.answers
        if (q := questions.get(a["question_id"]))
    ]
    return AttemptResult(
        attempt_id=attempt.id,
        quiz_id=attempt.quiz_id,
        quiz_title=attempt.quiz.title,
        score=attempt.score,
        total=attempt.total,
        percent=_percent(attempt.score, attempt.total),
        duration_seconds=attempt.duration_seconds,
        submitted_at=attempt.submitted_at,
        review=review,
    )


@router.get("/quizzes/daily", response_model=QuizOut)
def daily_quiz(
    db: DbSession,
    user: CurrentUser,
    day: date | None = Query(None, alias="date", description="Defaults to today"),
):
    quiz = quiz_service.get_or_create_daily(db, day or local_today())
    if quiz is None:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "No questions for this day yet")
    return _quiz_out(quiz)


@router.get("/quizzes/weekly", response_model=QuizOut)
def weekly_quiz(
    db: DbSession,
    user: CurrentUser,
    day: date | None = Query(None, alias="date", description="Any day in the week wanted"),
):
    quiz = quiz_service.get_or_create_weekly(db, day or local_today())
    if quiz is None:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "No questions for this week yet")
    return _quiz_out(quiz)


@router.post("/quizzes", response_model=QuizOut, status_code=status.HTTP_201_CREATED)
def create_quiz(body: CreateQuizRequest, db: DbSession, user: CurrentUser):
    """Start a topic-wise practice set or a timed mock exam."""
    if body.category and body.category not in CATEGORIES:
        raise HTTPException(status.HTTP_400_BAD_REQUEST, "Unknown category")
    quiz = quiz_service.create_custom(
        db,
        user_id=user.id,
        kind=body.kind,
        category=body.category,
        count=body.count,
        timed=body.timed,
    )
    if quiz is None:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "No questions available for this topic yet")
    return _quiz_out(quiz)


@router.post(
    "/articles/{article_id}/quiz", response_model=QuizOut, status_code=status.HTTP_201_CREATED
)
def article_quiz(article_id: int, db: DbSession, user: CurrentUser):
    article = get_ready_article(db, article_id)
    quiz = quiz_service.create_for_article(db, user_id=user.id, article=article)
    if quiz is None:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "This article has no questions")
    return _quiz_out(quiz)


@router.get("/quizzes/{quiz_id}", response_model=QuizOut)
def get_quiz(quiz_id: int, db: DbSession, user: CurrentUser):
    return _quiz_out(_get_quiz(db, quiz_id, user))


@router.post("/quizzes/{quiz_id}/check", response_model=CheckAnswerResponse)
def check_answer(quiz_id: int, body: CheckAnswerRequest, db: DbSession, user: CurrentUser):
    """Says at once whether one answer is right. Not available in a mock exam."""
    quiz = _get_quiz(db, quiz_id, user)
    if quiz.kind == "mock":
        raise HTTPException(
            status.HTTP_409_CONFLICT, "A mock exam shows its answers only at the end"
        )
    question = next((i.question for i in quiz.items if i.question_id == body.question_id), None)
    if question is None:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "That question is not in this quiz")
    return CheckAnswerResponse(
        question_id=question.id,
        correct=body.selected_index == question.correct_index,
        correct_index=question.correct_index,
        explanation=question.explanation,
        article_id=question.article_id,
    )


@router.post("/quizzes/{quiz_id}/attempts", response_model=AttemptResult)
def submit_quiz(quiz_id: int, body: SubmitQuizRequest, db: DbSession, user: CurrentUser):
    quiz = _get_quiz(db, quiz_id, user)
    selected = {a.question_id: a.selected_index for a in body.answers}
    attempt = quiz_service.grade(
        db, quiz=quiz, user_id=user.id, selected=selected, duration=body.duration_seconds
    )
    return _attempt_result(db, attempt)


@router.get("/me/quiz-attempts", response_model=list[AttemptSummary])
def my_attempts(
    db: DbSession,
    user: CurrentUser,
    limit: int = Query(30, ge=1, le=100),
    offset: int = Query(0, ge=0),
):
    attempts = db.scalars(
        select(QuizAttempt)
        .where(QuizAttempt.user_id == user.id)
        .order_by(QuizAttempt.submitted_at.desc())
        .limit(limit)
        .offset(offset)
    ).all()
    return [
        AttemptSummary(
            attempt_id=a.id,
            quiz_id=a.quiz_id,
            quiz_title=a.quiz.title,
            kind=a.quiz.kind,
            score=a.score,
            total=a.total,
            percent=_percent(a.score, a.total),
            submitted_at=a.submitted_at,
        )
        for a in attempts
    ]


@router.get("/me/quiz-attempts/{attempt_id}", response_model=AttemptResult)
def get_attempt(attempt_id: int, db: DbSession, user: CurrentUser):
    attempt = db.get(QuizAttempt, attempt_id)
    if attempt is None or attempt.user_id != user.id:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "Attempt not found")
    return _attempt_result(db, attempt)
