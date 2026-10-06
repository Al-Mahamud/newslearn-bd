"""Command line tasks: python -m app.cli <command>"""

import argparse
import getpass
import logging
import sys

from sqlalchemy import select

from app.db import session_scope
from app.models import User
from app.security import hash_password


def _send(db, args) -> int:
    import re
    from datetime import datetime
    from pathlib import Path

    from sqlalchemy.engine import make_url

    from app.config import get_settings
    from app.services import ingest as ingest_service

    settings = get_settings()
    url = make_url(settings.database_url)
    if url.get_backend_name() == "sqlite" and not args.local:
        print(
            "No online database is configured, so nothing would reach the app.\n"
            "Create backend/.env containing one line:\n"
            "  DATABASE_URL=postgresql://...   (your Neon connection string)\n"
            "(Use --local to send to the local test database on purpose.)",
            file=sys.stderr,
        )
        return 2

    data_dir = Path(args.dir) if args.dir else ingest_service.DATA_DIR
    dated = sorted(
        p for p in data_dir.glob("*") if p.is_dir() and re.fullmatch(r"\d{4}-\d{2}-\d{2}", p.name)
    )
    if args.all:
        folders = dated
    else:
        day = args.date or datetime.now(settings.tz).strftime("%Y-%m-%d")
        folders = [p for p in dated if p.name == day]
        if not folders:
            print(f"No folder for {day} in {data_dir}", file=sys.stderr)
            if dated:
                names = ", ".join(p.name for p in dated[-7:])
                print(f"Folders that exist: {names}  (send one with --date)", file=sys.stderr)
            return 1

    print(
        f"Database: {url.host or url.database}"
        + ("  [dry run: nothing is saved]" if args.dry_run else "")
    )
    failed = False
    for folder in folders:
        result = ingest_service.send_folder(db, folder, dry_run=args.dry_run)
        print(f"\n{folder.name}: {result.files} file(s)")
        if not result.files:
            print("  no .json files in this folder")
            failed = failed or not args.all
            continue
        for line in result.lines:
            print(line)
        verb = "would be saved" if args.dry_run else "saved"
        print(f"  => {result.saved} article(s) {verb}, {result.already_covered} already in the app")
        if result.problems:
            failed = True
            print("  PROBLEMS (these were not saved):")
            for line in result.problems:
                print(f"    {line}")
    return 1 if failed else 0


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
    send = sub.add_parser("send", help="send the analysis agent's files to the database")
    send.add_argument("--date", help="folder to send, YYYY-MM-DD (default: today)")
    send.add_argument("--all", action="store_true", help="send every dated folder")
    send.add_argument("--dry-run", action="store_true", help="check the files without saving")
    send.add_argument("--local", action="store_true", help="allow a local SQLite database")
    send.add_argument("--dir", help="data folder (default: <project>/agent-data)")
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
        elif args.command == "send":
            return _send(db, args)
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
