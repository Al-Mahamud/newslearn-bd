# Backend plan

Python 3.12+ · FastAPI · SQLAlchemy 2 · PostgreSQL (SQLite for local development) · Alembic ·
APScheduler · Google Gen AI SDK or Anthropic SDK. Code is in [`backend/`](../backend); how to run it is in
[`backend/README.md`](../backend/README.md).

All seven phases below are implemented and covered by the test suite (35 tests, passing on
SQLite and PostgreSQL 18). What has **not** been exercised is listed under
[Not yet verified](#not-yet-verified).

## Architecture

```
RSS feeds ─► collector ─► articles (pending) ─► AI pipeline ─► articles (ready) ─► REST API ─► app
              │  clean, canonicalise URL,          │  one structured call per article:
              │  drop stale items,                 │  category, 3 summaries, vocabulary,
              │  detect duplicate stories          │  facts, exam relevance, MCQs
              └─ scheduler: every 30 min           └─ scheduler: every 10 min, daily budget cap
```

- **One process.** The scheduler runs inside the API process, so there is no queue, broker
  or second service to operate. `python -m app.cli worker` runs the jobs separately if the
  API ever needs more than one worker.
- **One model call per article.** Classification, summaries, vocabulary, facts and MCQs
  come back together as schema-validated JSON. Quizzes are then assembled from stored
  questions with no further AI cost.
- **Provider behind an interface.** `app/ai/base.py` defines the contract; `GeminiProvider`
  and `ClaudeProvider` implement it; `MockProvider` makes development and tests free and offline.

## Phases

### Phase 0 — Foundation
Project layout, settings from environment variables, database session handling, Alembic,
health check, Docker image and compose file, lint and test setup.

*Done when:* `alembic upgrade head` builds the schema on PostgreSQL and SQLite; `/health`
answers; `pytest` and `ruff` are clean.

### Phase 1 — News collection
- `sources` table: one row per feed, with enable switch, optional default category and a
  per-source `fetch_full_text` flag. It is on for the feeds that carry only a sentence or
  two (The Daily Star, The Business Standard, Dhaka Tribune): the public article page is
  read once, obeying robots.txt and Crawl-delay, which raises the text given to the model
  from about 150 characters to 1,000–3,000.
- Collector: conditional GET (ETag / Last-Modified), HTML stripped from titles and
  summaries, tracking parameters removed from URLs, items older than 72 hours dropped
  (one of the real feeds serves 2022 articles), future timestamps clamped.
- Duplicate stories across publishers detected by headline similarity within a 72-hour
  window and stored as `skipped`, pointing at the original.
- Six starter feeds: Prothom Alo English, The Daily Star (news, business), The Business
  Standard (top news, economy), Dhaka Tribune.

*Done when:* running the collector twice adds nothing the second time; a re-reported story
is stored once.

### Phase 2 — AI processing
- Article state machine: `pending → ready | failed | skipped`, three attempts, error kept.
- Output validated before storing: 3–8 vocabulary items de-duplicated, facts capped,
  malformed MCQs (not four distinct options, index out of range) discarded, scores clamped.
- Shared dictionary: `words` holds one entry per word; `article_words` links it to each
  article with that article's context sentence. `occurrences` is the "newspaper frequency".
- Cost control: every call is recorded in `ai_usage`; processing stops for the day at
  `AI_DAILY_BUDGET_USD`; newest articles go first so the budget is spent on today's news.
- Model, prompt version and timestamp stored on every article, so results from an old
  prompt can be found and reprocessed.

*Done when:* a pending article becomes `ready` with all fields; a provider outage leaves it
retryable; a refusal is not retried; the budget cap stops a run.

### Phase 3 — MVP API
- Accounts: register, login, refresh (rotating, single-use refresh tokens with reuse
  detection), logout, profile. Argon2 password hashing, 30-minute access tokens.
- Feed: `GET /articles` with cursor pagination and filters for category, exam-important,
  source, text search, saved, and for-you. Public; user state is added when signed in.
- Article detail with vocabulary, facts and the link to the original.
- Save / unsave article, mark as read, save / remove word, search articles and words.

*Done when:* the app's first milestone (feed → article → words → save → open original) can
be built against these endpoints alone.

### Phase 4 — Learning and current affairs
- `POST /explain`: simple English, Bangla, word breakdown, grammar note when useful, and
  an example. Cached per sentence and level; per-user daily limit.
- Vocabulary review with spaced repetition (today → 1 → 3 → 7 → 30 days); a forgotten
  word returns to the start.
- `GET /current-affairs/daily` and `/weekly`: news grouped by topic, most exam-relevant
  first, plus every fact of the period regrouped by kind for quick revision.

### Phase 5 — Quizzes and progress
- Daily quiz (10 questions) and weekly quiz (20), shared by all users, built on demand and
  by the evening job; questions drawn evenly across the period's articles.
- Per-article quiz, topic-wise practice sets, and timed mock exams.
- Answers hidden until submission; the result returns each question's answer, explanation
  and source article. Attempt history.
- `GET /me/progress`: streak, articles read, words saved / learned / due, average score,
  accuracy per topic, weakest topic, last seven days.

### Phase 6 — Personalisation and AI tutor
- English level (beginner / intermediate / advanced) drives sentence explanations, tutor
  tone, and which vocabulary an advanced reader is shown.
- Preferred categories power the for-you feed.
- `POST /articles/{id}/ask`: tutor answers from the article's study notes, with
  conversation history and a per-user daily limit.

### Phase 7 — Operations
- Admin API: manage sources, trigger collection / processing / cleanup, reprocess an
  article, pipeline counts and AI spend for the last seven days.
- Retention: feed text deleted after 7 days; articles deleted after 90 days unless saved
  or the source of a saved word; expired refresh tokens purged.
- Registration switch (`REGISTRATION_OPEN`) and a production guard that refuses to start
  with the development JWT secret.

## API summary

All routes are under `/api/v1`. Interactive docs: `/docs`.

| Area | Routes |
|---|---|
| Auth | `POST /auth/register` · `/auth/login` · `/auth/refresh` · `/auth/logout` · `GET/PATCH /me` |
| News | `GET /categories` · `GET /articles` · `GET /articles/{id}` · `GET /search` |
| Reader state | `POST /articles/{id}/read` · `PUT/DELETE /articles/{id}/save` |
| Vocabulary | `GET /vocabulary` · `GET /vocabulary/review` · `PUT/DELETE /vocabulary/{id}` · `POST /vocabulary/{id}/review` |
| Learning | `POST /explain` · `POST /articles/{id}/ask` · `GET /current-affairs/daily` · `/weekly` |
| Quizzes | `GET /quizzes/daily` · `/weekly` · `POST /quizzes` · `POST /articles/{id}/quiz` · `GET /quizzes/{id}` · `POST /quizzes/{id}/attempts` · `GET /me/quiz-attempts` · `/{id}` |
| Progress | `GET /me/progress` |
| Admin | `GET/POST /admin/sources` · `PATCH /admin/sources/{id}` · `POST /admin/collect` · `/process` · `/cleanup` · `POST /admin/articles/{id}/reprocess` · `GET /admin/status` |

## Data model

| Table | Purpose |
|---|---|
| `users`, `refresh_tokens` | accounts and sessions |
| `sources` | RSS feeds and their settings |
| `articles` | metadata, excerpt, generated summaries, category, exam score, pipeline state |
| `article_facts` | numbers, organisations, people, places, dates, other facts |
| `words`, `article_words` | shared dictionary and its per-article context |
| `user_words` | saved words with review box and due date |
| `saved_articles`, `reading_history` | reader state |
| `questions` | MCQs generated with each article |
| `quizzes`, `quiz_items`, `quiz_attempts` | quiz sets and results |
| `sentence_explanations` | cache for explained sentences |
| `ai_usage` | every model call: purpose, tokens, cost |

## Not yet verified

- **No real model has been called.** Development used the mock provider. The Gemini
  provider is tested through the real SDK against a stubbed HTTP transport (request shape,
  parsing, token and cost accounting, refusals, errors) but not against Google's servers;
  the Claude provider was checked against the installed SDK's signatures only. Watch the
  first real run: `python -m app.cli process --limit 2`, then read the two articles.
- **Prompt quality is untested.** Bangla fluency, vocabulary choice and MCQ quality need
  a human read of real output before trusting them for exam preparation.
- **Docker files have not been built** (Docker is not installed on the development machine).
- **Real per-article cost is unknown** until the first real run; `/admin/status` reports it.

## Later

- Batch API for overnight processing (half price) once volume justifies the delay.
- PostgreSQL full-text search in place of `ILIKE` when the archive grows.
- Push notification for the daily digest.
- Bangla-language sources, with the pipeline producing English study notes from them.
- A "report a mistake" endpoint so wrong facts or MCQs can be flagged and reprocessed.
