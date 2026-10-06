"""Accepts study notes written outside this service (the local analyst agent).

The agent reads the newspaper and does the analysis itself; this module only validates
what it produced and stores it through the same code path as the built-in pipeline, so
agent-written and pipeline-written articles are indistinguishable to the app.
"""

import json
import re
from dataclasses import dataclass, field
from datetime import date, datetime, time, timedelta
from pathlib import Path

from pydantic import BaseModel, Field, ValidationError
from sqlalchemy import select
from sqlalchemy.orm import Session

from app.ai.base import ArticleEnrichment
from app.config import get_settings
from app.models import Article, Source, utcnow
from app.services import collector, pipeline
from app.services.textutil import canonical_url, sha256, title_similarity, title_tokens, truncate
from app.services.timeutil import local_date


class AgentArticle(BaseModel):
    source: str = Field(min_length=2, max_length=120, description="Newspaper name")
    title: str = Field(min_length=5, max_length=500)
    published_on: date
    url: str | None = Field(default=None, description="Public link to the article, if any")
    page: str | None = Field(default=None, max_length=40, description="Print page, e.g. 'B1'")
    image_url: str | None = None
    excerpt: str = Field(default="", description="One or two sentences; not the article text")
    analysis: ArticleEnrichment


class AgentBatch(BaseModel):
    model: str = Field(default="agent", max_length=40, description="Model that did the analysis")
    articles: list[AgentArticle]


@dataclass
class IngestReport:
    added: list[str] = field(default_factory=list)
    updated: list[str] = field(default_factory=list)
    already_covered: list[str] = field(default_factory=list)
    rejected: list[str] = field(default_factory=list)

    @property
    def ok(self) -> bool:
        return not self.rejected


def _slug(text: str) -> str:
    return re.sub(r"[^a-z0-9]+", "-", text.lower()).strip("-")[:60]


def _source_for(db: Session, name: str) -> Source:
    """Agent articles live under their own source so the feed collector never touches them."""
    slug = f"agent-{_slug(name)}"
    source = db.scalar(select(Source).where(Source.slug == slug))
    if source is None:
        # Disabled: there is no feed to collect; the agent supplies the articles.
        source = Source(slug=slug, name=name, feed_url="agent://local", enabled=False)
        db.add(source)
        db.flush()
    return source


def _published_at(day: date) -> datetime:
    """Noon local time on the print date, or now if that is still in the future."""
    moment = datetime.combine(day, time(12, 0), tzinfo=get_settings().tz)
    return min(moment, utcnow())


def load_batch(path: Path) -> AgentBatch:
    """Reads a data file. Accepts the full shape, a bare list of articles, or one article."""
    data = json.loads(path.read_text(encoding="utf-8"))
    if isinstance(data, list):
        data = {"articles": data}
    elif isinstance(data, dict) and "articles" not in data and "analysis" in data:
        data = {"articles": [data]}
    return AgentBatch.model_validate(data)


def describe_errors(error: ValidationError) -> list[str]:
    return [f"{'.'.join(str(part) for part in e['loc'])}: {e['msg']}" for e in error.errors()]


def ingest(db: Session, batch: AgentBatch, *, dry_run: bool = False) -> IngestReport:
    report = IngestReport()
    model = truncate(f"agent:{batch.model}", 60)
    window_start = utcnow() - timedelta(hours=collector.DUPLICATE_WINDOW_HOURS)

    for item in batch.articles:
        if not item.analysis.summary.strip() or not item.analysis.bangla_summary.strip():
            report.rejected.append(f"{item.title}: summary and bangla_summary are required")
            continue

        source = _source_for(db, item.source)
        # Print articles often have no link; give each a stable address of its own.
        url = (
            canonical_url(item.url)
            if item.url
            else f"agent://{_slug(item.source)}/{item.published_on}/{_slug(item.title)}"
        )
        url_hash = sha256(url)
        article = db.scalar(select(Article).where(Article.url_hash == url_hash))

        if article is None:
            tokens = title_tokens(item.title)
            twins = [
                other
                for other in db.scalars(
                    select(Article).where(
                        Article.published_at >= window_start, Article.duplicate_of_id.is_(None)
                    )
                )
                if title_similarity(tokens, title_tokens(other.title))
                >= collector.DUPLICATE_THRESHOLD
            ]
            if any(twin.status == "ready" for twin in twins):
                report.already_covered.append(item.title)
                continue
            article = Article(
                source_id=source.id,
                source=source,
                title=truncate(item.title, 500),
                url=url,
                url_hash=url_hash,
                published_at=_published_at(item.published_on),
            )
            db.add(article)
            db.flush()
            # The same story waiting in the feed queue no longer needs its own model call.
            for twin in twins:
                if twin.status == "pending":
                    twin.status, twin.duplicate_of_id = "skipped", article.id
                    twin.error = "covered by the analyst agent"
            report.added.append(item.title)
        else:
            report.updated.append(item.title)

        article.excerpt = truncate(item.excerpt, collector.EXCERPT_CHARS)
        article.image_url = item.image_url or article.image_url
        article.author = f"Page {item.page}" if item.page else article.author
        pipeline.apply_enrichment(db, article, item.analysis, model)

    if dry_run:
        db.rollback()
    else:
        db.commit()
    return report


def recent_titles(db: Session, days: int = 3) -> list[dict]:
    """What the app already has, so the agent can skip stories that are covered."""
    rows = db.execute(
        select(Article.title, Article.status, Article.published_at)
        .where(
            Article.published_at >= utcnow() - timedelta(days=days),
            Article.duplicate_of_id.is_(None),
        )
        .order_by(Article.published_at.desc())
    )
    return [
        {"title": title, "status": status, "published": local_date(published).isoformat()}
        for title, status, published in rows
    ]


# Where the analysis agent leaves its files: <project>/agent-data/<YYYY-MM-DD>/*.json
DATA_DIR = Path(__file__).resolve().parents[3] / "agent-data"
SENT_MARKER = "SENT.txt"


@dataclass
class SendResult:
    files: int = 0
    saved: int = 0
    already_covered: int = 0
    problems: list[str] = field(default_factory=list)
    lines: list[str] = field(default_factory=list)


def send_folder(db: Session, folder: Path, *, dry_run: bool = False) -> SendResult:
    """Stores every JSON file in one day's folder. A bad file does not stop the others."""
    result = SendResult()
    for path in sorted(folder.glob("*.json")):
        result.files += 1
        try:
            batch = load_batch(path)
        except (OSError, json.JSONDecodeError) as e:
            result.problems.append(f"{path.name}: cannot be read as JSON ({e})")
            continue
        except ValidationError as e:
            result.problems.append(f"{path.name}: does not match the format")
            result.problems += [f"    {line}" for line in describe_errors(e)]
            continue
        report = ingest(db, batch, dry_run=dry_run)
        result.saved += len(report.added) + len(report.updated)
        result.already_covered += len(report.already_covered)
        result.lines += [f"  new      {t}" for t in report.added]
        result.lines += [f"  updated  {t}" for t in report.updated]
        result.lines += [
            f"  skipped  {t} (the app already has this story)" for t in report.already_covered
        ]
        result.problems += [f"{path.name}: {r}" for r in report.rejected]

    if not dry_run and result.files:
        stamp = datetime.now(get_settings().tz).strftime("%Y-%m-%d %H:%M")
        summary = [f"Sent {stamp}: {result.saved} saved, {result.already_covered} already covered"]
        summary += result.lines
        if result.problems:
            summary += ["", "Problems:"] + result.problems
        (folder / SENT_MARKER).write_text("\n".join(summary) + "\n", encoding="utf-8")
    return result
