# Putting the backend online

GitHub stores the code and can run scheduled jobs, but it cannot run a web server. So the
backend is split across three free services, all driven from this repository:

```
GitHub Actions (hourly)  ──collect + AI──►  PostgreSQL (Neon)  ◄──reads──  API (Render)  ◄──  phone
```

| Piece | Where | Why there |
|---|---|---|
| Database | [Neon](https://neon.tech) free PostgreSQL | Stays available; does not expire |
| API | [Render](https://render.com) free web service, deployed from this repo | Gives an HTTPS address the app can use |
| News collection + AI | GitHub Actions, `.github/workflows/news-pipeline.yml` | Runs on time even while the API host is asleep |

## Steps

1. **Database.** Create a Neon project and copy its connection string
   (`postgresql://…?sslmode=require`).

2. **Pipeline.** In the GitHub repository: *Settings → Secrets and variables → Actions →
   New repository secret*. Add `DATABASE_URL` (the Neon string) and `GEMINI_API_KEY`.
   Then *Actions → News pipeline → Run workflow*. The first run creates the tables, adds
   the news sources, collects and processes up to 20 articles.

3. **API.** In Render: *New → Blueprint*, choose this repository. It reads `render.yaml`
   and asks for `DATABASE_URL` and `GEMINI_API_KEY`; paste the same two values. When the
   deploy finishes, open `https://<your-service>.onrender.com/health`.

4. **App.** Put the address in `android/local.properties` and rebuild:
   ```properties
   newslearn.apiBaseUrl=https://<your-service>.onrender.com/
   ```

5. **Lock it.** Register your account in the app (the first account is the admin), then
   add `REGISTRATION_OPEN=false` to the Render service's environment.

## What to expect on the free tiers

- **Slow first request.** Render's free service sleeps after 15 minutes without traffic;
  the next request takes up to a minute while it wakes. A paid instance removes this.
- **Hourly news.** The pipeline runs once an hour. In a private repository GitHub gives
  2,000 Actions minutes a month, which hourly runs fit inside; a public repository has no
  limit and could run more often (edit the `cron` line).
- **Schedule can pause.** GitHub disables scheduled workflows after 60 days with no
  repository activity; re-enable it from the Actions tab.
- **AI cost** is capped per day by `AI_DAILY_BUDGET_USD` (default $1.00). Change it under
  *Settings → Secrets and variables → Actions → Variables*.

## Not yet verified

None of this has been deployed. The workflow files and blueprint are valid YAML and the
commands in them are the ones tested locally, but the first run on GitHub, Neon and Render
may need small corrections. The Docker image has not been built.
