FROM python:3.13-slim

ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
WORKDIR /srv

COPY pyproject.toml ./
COPY app ./app
RUN pip install --no-cache-dir .

COPY alembic.ini ./
COPY migrations ./migrations

RUN useradd --system --no-create-home newslearn
USER newslearn

EXPOSE 8000
# PORT is set by the hosting platform; 8000 otherwise.
# One worker: the scheduler runs inside the API process (see app/scheduler.py).
CMD ["sh", "-c", "alembic upgrade head && python -m app.cli seed && uvicorn app.main:app --host 0.0.0.0 --port ${PORT:-8000}"]
