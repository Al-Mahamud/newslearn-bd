"""Command line tasks: python -m app.cli <command>"""

import argparse
import getpass
import logging
import sys

from sqlalchemy import select

from app.db import session_scope
from app.models import User
from app.security import hash_password


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="python -m app.cli")
    sub = parser.add_subparsers(dest="command", required=True)
    sub.add_parser("seed", help="add the starter news sources")
    sub.add_parser("collect", help="fetch all enabled feeds once")
    process = sub.add_parser("process", help="run AI processing on pending articles")
    process.add_argument("--limit", type=int, default=None)
    sub.add_parser("cleanup", help="apply retention rules")
    sub.add_parser("sample", help="print the newest processed article's study notes")
    ingest = sub.add_parser("ingest", help="store study notes written by the analyst agent")
    ingest.add_argument("file", help="JSON file; see docs/ANALYST_AGENT.md")
    ingest.add_argument("--dry-run", action="store_true", help="validate without saving")
    recent = sub.add_parser("recent", help="list recent article titles as JSON")
    recent.add_argument("--days", type=int, default=3)
    sub.add_parser("worker", help="run the scheduler without the API")
    admin = sub.add_parser("create-admin", help="create an admin account, or promote one")
    admin.add_argument("email")
    args = parser.parse_args(argv)

    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")

    if args.command == "worker":
        from apscheduler.schedulers.blocking import BlockingScheduler

        from app import scheduler
        from app.config import get_settings

        blocking = scheduler.configure(BlockingScheduler(timezone=get_settings().tz))
        blocking.add_job(scheduler.collect_job, id="collect-on-start")
        try:
            blocking.start()
        except KeyboardInterrupt:
            pass
        return 0

    with session_scope() as db:
        if args.command == "seed":
            from app.seed import seed_sources

            print(f"added {seed_sources(db)} source(s)")
        elif args.command == "collect":
            from app.services import collector

            for r in collector.collect_all(db):
                print(f"{r.source}: {r.status} new={r.new} dup={r.duplicates} old={r.skipped_old}")
        elif args.command == "process":
            from app.ai.base import AIConfigError
            from app.services import pipeline

            try:
                print(pipeline.process_pending(db, args.limit))
            except AIConfigError as e:
                # Exit non-zero so a scheduled run shows as failed instead of quietly doing nothing.
                print(f"AI is misconfigured, nothing was processed: {e}", file=sys.stderr)
                return 1
        elif args.command == "sample":
            from app.models import Article

            article = db.scalar(
                select(Article)
                .where(Article.status == "ready")
                .order_by(Article.processed_at.desc())
                .limit(1)
            )
            if article is None:
                print("no processed articles yet")
                return 0
            print(
                f"{article.title}\n{article.source.name} | {article.category} | "
                f"exam {article.exam_importance}/100 | model {article.ai_model}\n"
            )
            print(f"SUMMARY\n{article.summary}\n\nEASY\n{article.easy_summary}\n")
            print(f"BANGLA\n{article.bangla_summary}\n\nWHY IT MATTERS\n{article.exam_reason}\n")
            print("WORDS")
            for link in article.words:
                w = link.word
                print(f"  {w.lemma} ({w.part_of_speech}): {w.meaning_en} | {w.meaning_bn}")
            print("\nFACTS")
            for fact in article.facts:
                print(f"  [{fact.kind}] {fact.text} - {fact.detail}")
            print("\nQUESTIONS")
            for q in article.questions:
                print(f"  {q.text}")
                for i, option in enumerate(q.options):
                    print(f"    {'*' if i == q.correct_index else ' '} {option}")
        elif args.command == "ingest":
            import json
            from pathlib import Path

            from pydantic import ValidationError

            from app.services import ingest as ingest_service

            try:
                batch = ingest_service.load_batch(Path(args.file))
            except (OSError, json.JSONDecodeError) as e:
                print(f"cannot read {args.file}: {e}", file=sys.stderr)
                return 1
            except ValidationError as e:
                print("the file does not match the expected format:", file=sys.stderr)
                for line in ingest_service.describe_errors(e):
                    print(f"  {line}", file=sys.stderr)
                return 1
            report = ingest_service.ingest(db, batch, dry_run=args.dry_run)
            for label, titles in (
                ("added", report.added),
                ("updated", report.updated),
                ("already covered, not stored", report.already_covered),
                ("REJECTED", report.rejected),
            ):
                for title in titles:
                    print(f"{label}: {title}")
            saved = "would be saved (dry run)" if args.dry_run else "saved"
            print(f"{len(report.added) + len(report.updated)} {saved}")
            return 0 if report.ok else 1
        elif args.command == "recent":
            import json

            from app.services import ingest as ingest_service

            print(
                json.dumps(
                    ingest_service.recent_titles(db, args.days), ensure_ascii=False, indent=1
                )
            )
        elif args.command == "cleanup":
            from app.services import maintenance

            print(maintenance.cleanup(db))
        elif args.command == "create-admin":
            email = args.email.lower()
            user = db.scalar(select(User).where(User.email == email))
            if user:
                user.is_admin = True
                print(f"{email} is now an admin")
            else:
                password = getpass.getpass("Password (min 8 characters): ")
                if len(password) < 8:
                    print("password too short", file=sys.stderr)
                    return 1
                db.add(User(email=email, password_hash=hash_password(password), is_admin=True))
                print(f"created admin {email}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
