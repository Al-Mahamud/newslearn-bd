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
            from app.services import pipeline

            print(pipeline.process_pending(db, args.limit))
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
