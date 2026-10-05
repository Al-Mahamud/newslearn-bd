# NewsLearn BD — project plan

Source: `NewsLearn_BD_App_Development_Plan.pdf`. This document records what was changed or
added to that plan and how the work is split. Details live in two plans:

- [Backend plan](BACKEND_PLAN.md) — 7 phases, implemented in [`backend/`](../backend)
- [App plan](APP_PLAN.md) — 6 phases, implemented in [`android/`](../android)

The backend is built first because the app's screens are thin views over its API; each app
phase consumes the backend phase of the same number or lower.

## Improvements made to the original plan

### Cost and reliability of the AI pipeline
| Original | Change | Why |
|---|---|---|
| Separate steps: classify → summarise → simplify → extract vocabulary → facts → MCQs | **One structured call per article** returning all of it as schema-validated JSON | One call instead of six cuts cost and latency, and the outputs agree with each other |
| "Optional MCQs", generated on demand | MCQs generated with the article and stored | Daily, weekly, topic and mock quizzes then cost nothing to build |
| "Cache AI results" | Article state machine (`pending → ready / failed / skipped`), 3 attempts, model + prompt version stored per article | Nothing is processed twice; output from an old prompt can be found and redone |
| "Don't send every article to an expensive model" | Hard **daily budget in dollars**, newest articles first, every call logged with tokens and cost | A bad day cannot produce a surprise bill; spend is visible at `/admin/status` |
| — | Mock AI provider | The whole system runs and is tested with no key and no cost |
| — | Output validation (malformed MCQs dropped, counts capped, scores clamped) and prompts that forbid facts not in the text | The reader is tested on this material; an invented number does real harm |

### Data model
| Original | Change | Why |
|---|---|---|
| `Vocabulary` rows per article | Shared `words` dictionary + `article_words` link with per-article context | "sluggish" is stored once, however many articles use it; gives the "newspaper frequency" signal the plan asks for and makes review work across articles |
| `UserVocabulary(learned, review_count, last_reviewed)` | Adds review box and `due_at` from day one | Spaced repetition (planned "later") needs no migration |
| "Exam Important" as a category | An **exam-importance score (0–100) with a one-line reason**, alongside the topic category | An economy story can also be exam-important; a score lets digests and quizzes rank |
| Facts as text | `article_facts` typed as number / organisation / person / place / date / fact | Enables the revision view ("all of this week's numbers") |

### News sources and copyright
| Original | Change | Why |
|---|---|---|
| "The Daily Star and Prothom Alo" | Six verified working RSS feeds from four publishers | The Daily Star front-page feed is stale (serves 2022 items); section feeds work |
| "Respect terms; don't assume scraping is permitted" | Public pages only, and only where robots.txt allows; page fetching is a per-source switch that honours Crawl-delay; text deleted after 7 days; article text never served by the API; subscriber e-papers not used | Makes the policy a property of the code rather than a note |
| "Duplicate check" | URL canonicalisation plus headline-similarity matching across publishers | The same story from three papers is processed and shown once |
| — | Items older than 72 hours ignored; HTML stripped from feed titles | Both problems occur in the real feeds |

### Product
| Original | Change | Why |
|---|---|---|
| Sentence explanation | Cached per sentence and reading level, with a per-user daily limit | Repeat lookups are free and instant |
| English level in Phase 3 | Stored on the account from the start; used by explanations, tutor and vocabulary | Cheap now, awkward to retrofit |
| Bottom nav `Home · Learn · Saved · Profile` vs. sections `Feed · Learn · Saved · Search · Progress` | `Home · Learn · Quiz · Saved · Profile`; search on Home; progress in Profile | Resolves the mismatch and gives quizzes a home |
| JWT | Short-lived access token plus rotating single-use refresh token with reuse detection | The app stays signed in without a long-lived bearer token |
| — | "Today" defined in Asia/Dhaka for digests, quizzes and streaks | A server in UTC would otherwise roll the day over at 6 am |
| — | Streak and weakest-topic in progress | Gives a reason to return daily and tells the reader what to practise |
| — | Offline reading: the app caches the feed and opened articles | Commutes and patchy data |
| — | Word pronunciation with the phone's text-to-speech | Free, and a learner needs to hear the word |
| — | Every AI-written block labelled as such, next to the link to the original | Summaries can be wrong; the reader should know what to trust |

### Deliberately left out for now
Recommendation engine, push notifications, Batch-API processing, full-text search index,
Bangla-language sources, iOS. Each is listed under "Later" in the relevant plan.

## Order of work

1. Backend phases 0–7 (done).
2. App phases 0–5.
3. First real AI run: add an API key, process a handful of articles, read the Bangla and
   the MCQs critically, and adjust the prompt in `backend/app/ai/prompts.py`.
4. Deploy the backend somewhere the phone can reach.
