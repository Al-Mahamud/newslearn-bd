# NewsLearn BD — backend

FastAPI service that collects news from publisher RSS feeds, turns each article into study
material with one LLM call (summary, easy-English summary, Bangla summary, vocabulary, key
facts, exam relevance, MCQs), and serves it to the Android app.

The plan and phase breakdown are in [`../docs/BACKEND_PLAN.md`](../docs/BACKEND_PLAN.md).

## Run locally

```bash
python3 -m venv .venv && . .venv/bin/activate
pip install -e ".[dev]"
cp .env.example .env            # defaults work as-is: SQLite + mock AI

alembic upgrade head            # create the tables
python -m app.cli seed          # add the starter news sources
python -m app.cli collect       # fetch the feeds once
python -m app.cli process       # generate study material for pending articles
uvicorn app.main:app --reload   # http://localhost:8000/docs
```

With the scheduler on (the default) the server also collects every 30 minutes and
processes every 10, so the CLI steps are only needed for a first fill.

The first account you register becomes the admin. After that, set `REGISTRATION_OPEN=false`
if the server is reachable by anyone else.

## Real summaries

`AI_PROVIDER=mock` produces labelled placeholder text and costs nothing. For real output
put a provider and its key in `.env`:

```
AI_PROVIDER=gemini
GEMINI_API_KEY=your-key
AI_DAILY_BUDGET_USD=1.00        # processing pauses for the day once this is spent
```

Gemini uses `gemini-2.5-flash` unless `AI_MODEL` names another model. Claude works the
same way with `AI_PROVIDER=claude` and `ANTHROPIC_API_KEY` (default `claude-opus-5-5`).

Check it on two articles before letting the scheduler run:

```bash
python -m app.cli collect
python -m app.cli process --limit 2
```

Spend and pipeline state: `GET /api/v1/admin/status`. Costs are estimated from the price
table in `app/ai/gemini.py` / `app/ai/claude.py`; keep it in line with the provider's
current prices if you change model.

## Run with Docker (PostgreSQL)

```bash
cp .env.example .env   # set JWT_SECRET, ENVIRONMENT=production, AI settings
docker compose up --build
```

## Tests

```bash
pytest                                              # in-memory SQLite
TEST_DATABASE_URL=postgresql+psycopg://... pytest   # an empty PostgreSQL database
ruff check . && ruff format --check .
```

## Layout

```
app/
  main.py          FastAPI app, routers, lifespan
  config.py        settings (environment variables)
  models.py        SQLAlchemy models
  schemas.py       request/response models
  api/             route handlers, one module per area
  services/        collector, AI pipeline, quizzes, spaced repetition, cleanup
  ai/              provider interface, Gemini / Claude / mock providers, prompts
  scheduler.py     periodic jobs
  cli.py           python -m app.cli seed|collect|process|cleanup|worker|create-admin
migrations/        Alembic
tests/
```

## Sources and copyright

News is found through RSS feeds published by the outlets. The app stores a headline, a
short excerpt, the link, and its own generated study notes; it never serves article text.

Prothom Alo English puts whole articles in its feed. The other feeds carry a sentence or
two, so for those sources (`fetch_full_text=true`) the article's public page is read once
to write the notes. That fetch obeys robots.txt and each site's Crawl-delay, and the text
is deleted after 7 days. Switch it off per source with `PATCH /api/v1/admin/sources/{id}`.

The subscriber e-papers (epaper.prothomalo.com, epaper.thedailystar.net) are not used:
both forbid automated access in robots.txt, and the same articles are on the public sites.
