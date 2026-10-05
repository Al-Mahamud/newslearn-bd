from datetime import timedelta

from fastapi import APIRouter, HTTPException, status
from sqlalchemy import func, select
from sqlalchemy.orm import Session

from app.config import get_settings
from app.deps import CurrentUser, DbSession
from app.models import CATEGORIES, RefreshToken, User, utcnow
from app.schemas import (
    LoginRequest,
    RefreshRequest,
    RegisterRequest,
    TokenResponse,
    UpdateProfileRequest,
    UserOut,
)
from app.security import (
    create_access_token,
    hash_password,
    hash_token,
    new_refresh_token,
    verify_password,
)

router = APIRouter(tags=["auth"])

# Verified against when the email is unknown, so both failures take the same time.
_DUMMY_HASH = hash_password("not-a-real-password")


def _issue_tokens(db: Session, user: User) -> TokenResponse:
    settings = get_settings()
    token, token_hash = new_refresh_token()
    db.add(
        RefreshToken(
            user_id=user.id,
            token_hash=token_hash,
            expires_at=utcnow() + timedelta(days=settings.refresh_token_days),
        )
    )
    db.commit()
    return TokenResponse(
        access_token=create_access_token(user.id),
        refresh_token=token,
        expires_in=settings.access_token_minutes * 60,
        user=UserOut.model_validate(user),
    )


@router.post("/auth/register", response_model=TokenResponse, status_code=status.HTTP_201_CREATED)
def register(body: RegisterRequest, db: DbSession):
    if not get_settings().registration_open:
        raise HTTPException(status.HTTP_403_FORBIDDEN, "Registration is closed")
    email = body.email.lower()
    if db.scalar(select(User.id).where(User.email == email)):
        raise HTTPException(status.HTTP_409_CONFLICT, "An account with this email already exists")
    # The first account on a fresh install owns it and can manage sources.
    is_first = (db.scalar(select(func.count()).select_from(User)) or 0) == 0
    user = User(
        email=email,
        password_hash=hash_password(body.password),
        display_name=body.display_name.strip(),
        is_admin=is_first,
    )
    db.add(user)
    db.flush()
    return _issue_tokens(db, user)


@router.post("/auth/login", response_model=TokenResponse)
def login(body: LoginRequest, db: DbSession):
    user = db.scalar(select(User).where(User.email == body.email.lower()))
    valid = verify_password(body.password, user.password_hash if user else _DUMMY_HASH)
    if user is None or not valid:
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "Incorrect email or password")
    return _issue_tokens(db, user)


@router.post("/auth/refresh", response_model=TokenResponse)
def refresh(body: RefreshRequest, db: DbSession):
    """Exchange a refresh token for a new pair. Each refresh token works once."""
    stored = db.scalar(
        select(RefreshToken).where(RefreshToken.token_hash == hash_token(body.refresh_token))
    )
    now = utcnow()
    if stored is None or stored.expires_at <= now:
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "Invalid or expired refresh token")
    if stored.revoked_at is not None:
        # A used token came back: assume it leaked and sign the account out everywhere.
        for token in db.scalars(
            select(RefreshToken).where(
                RefreshToken.user_id == stored.user_id, RefreshToken.revoked_at.is_(None)
            )
        ):
            token.revoked_at = now
        db.commit()
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "Invalid or expired refresh token")
    stored.revoked_at = now
    user = db.get(User, stored.user_id)
    return _issue_tokens(db, user)


@router.post("/auth/logout", status_code=status.HTTP_204_NO_CONTENT)
def logout(body: RefreshRequest, db: DbSession):
    stored = db.scalar(
        select(RefreshToken).where(RefreshToken.token_hash == hash_token(body.refresh_token))
    )
    if stored and stored.revoked_at is None:
        stored.revoked_at = utcnow()
        db.commit()


@router.get("/me", response_model=UserOut)
def me(user: CurrentUser):
    return user


@router.patch("/me", response_model=UserOut)
def update_me(body: UpdateProfileRequest, user: CurrentUser, db: DbSession):
    if body.display_name is not None:
        user.display_name = body.display_name.strip()
    if body.english_level is not None:
        user.english_level = body.english_level
    if body.preferred_categories is not None:
        unknown = set(body.preferred_categories) - CATEGORIES.keys()
        if unknown:
            raise HTTPException(422, f"Unknown categories: {sorted(unknown)}")
        user.preferred_categories = list(dict.fromkeys(body.preferred_categories))
    db.commit()
    return user
