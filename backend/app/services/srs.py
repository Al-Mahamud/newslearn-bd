"""Spaced repetition for saved vocabulary (Leitner boxes).

A remembered word moves up one box and comes back after a longer gap:
today -> tomorrow -> 3 days -> 7 days -> 30 days. A forgotten word returns to box 0.
"""

from datetime import datetime, timedelta

from app.models import UserWord

# Days until the next review once a word reaches the given box.
INTERVAL_DAYS = {1: 1, 2: 3, 3: 7, 4: 30, 5: 90}
MAX_BOX = 5
# A word that has survived the 7-day gap counts as learned.
LEARNED_BOX = 4


def is_learned(entry: UserWord) -> bool:
    return entry.box >= LEARNED_BOX


def apply_review(entry: UserWord, remembered: bool, now: datetime) -> None:
    entry.review_count += 1
    entry.last_reviewed_at = now
    if remembered:
        entry.correct_count += 1
        entry.box = min(entry.box + 1, MAX_BOX)
        entry.due_at = now + timedelta(days=INTERVAL_DAYS[entry.box])
    else:
        entry.box = 0
        entry.due_at = now
