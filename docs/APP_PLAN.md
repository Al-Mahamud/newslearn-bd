# Android app plan

Kotlin · Jetpack Compose (Material 3) · MVVM · Coroutines + Flow · Retrofit + OkHttp ·
kotlinx.serialization · Room · DataStore · Coil. Code is in [`android/`](../android).

Mirrors the backend plan: each phase is usable on its own and depends only on backend
phases already built.

Phases 0–4 are implemented. The project builds (debug and minified release), passes lint
with no errors, and passes a contract test that decodes real backend responses. See
[Not yet verified](#not-yet-verified) for what that does not cover.

## Architecture

```
Compose screen ─► ViewModel (StateFlow<UiState>) ─► Repository (returns Result<T>) ─► Retrofit API
                                                         └────────────────────────► Room cache
```

- **Screens** render state and forward events; no logic, no API calls.
- **ViewModels** own state and expose `StateFlow`; one-off events go through `SharedFlow`.
- **Repositories** never throw: every call returns `Result<T>` with a readable message.
- **Manual dependency injection** through one `AppContainer` owned by the `Application`.
  Small enough not to need Hilt, and one less annotation processor.
- **Auth** is handled in OkHttp: an interceptor adds the access token, an `Authenticator`
  refreshes it once on a 401 and retries, and signs the user out if the refresh fails.
- **Offline**: the feed and every opened article are written to Room; the cache is shown
  when the network is unavailable.

## Phases

### Phase 0 — Foundation
Gradle project with a version catalog, theme, navigation shell with the bottom bar,
network client, token storage, `AppContainer`, shared loading / error / empty components.

### Phase 1 — MVP (needs backend phases 0–3)
The plan's first milestone.
- Sign in / register.
- Home: category chips (including Exam Important and For You), feed with images,
  infinite scroll, pull to refresh, search.
- Article screen: Summary / Easy English / বাংলা tabs, vocabulary cards with Bangla
  meaning, example and pronunciation, key facts, why it matters, open original.
- Save article, save word; Saved screen with Articles and Words tabs.
- Offline cache of feed and opened articles.

### Phase 2 — Learning (backend phase 4)
- Explain a sentence: tap any sentence in a summary, or type / paste one.
- Vocabulary review: flashcards with "I knew it" / "I forgot" driving the review schedule.
- Current affairs: daily and weekly digest by topic, plus the quick-revision list of
  numbers, people, organisations, places and dates.

### Phase 3 — Quizzes (backend phase 5)
- Daily and weekly quiz, quiz from an article, topic practice, timed mock exam.
- Result screen with the right answer, explanation and link to the article for each
  question. Attempt history.

### Phase 4 — Progress, profile and tutor (backend phase 6)
- Progress: streak, articles read, words learned and due, average score, accuracy per
  topic, weakest topic, last seven days.
- Profile: English level, preferred categories, sign out.
- Ask the tutor about the open article.

### Phase 5 — Polish (later)
Daily-digest notification, home-screen widget for word of the day, dark-mode tuning,
Bangla UI strings, accessibility pass, release signing and Play listing.

## Screens and routes

| Route | Screen |
|---|---|
| `auth` | Sign in / register |
| `home` | Feed, categories, search |
| `article/{id}` | Article learning screen (+ explain sheet, tutor sheet) |
| `learn` | Hub: review due words, current affairs, explain a sentence |
| `review` | Flashcard review |
| `digest/{period}` | Daily / weekly current affairs |
| `quiz` | Hub: daily, weekly, practice, mock, history |
| `quiz/{id}` | Take a quiz, then see the result |
| `saved` | Saved articles and words |
| `profile` | Progress and settings |

## Configuration

The API address is a build setting. The default, `http://10.0.2.2:8000/`, reaches a backend
running on the same computer as the Android emulator. For a phone or a deployed server set
`newslearn.apiBaseUrl=https://…/` in `android/local.properties`.

## Redesign (versions 0.2.0 and 0.3.0)

The look and the five main screens were rebuilt from the design canvas: deep green with an
amber accent, Bricolage Grotesque for headlines, Hind Siliguri for body text (one face for
Bangla and English).

- **Today**: a summary panel (articles read against a fixed goal of 5, words due, quiz
  done), then the day's top picks ranked by exam score with the reason each was chosen;
  topic tabs including ICT.
- **Article**: vocabulary highlighted inside the text and tappable, a word panel, a
  listening player with slow speed and progress, text-size control, a two-column word grid.
- **Word review**: a full-screen flashcard showing each word's stage and when it returns.
- **Quiz**: one question per screen with a progress strip and timer; answers and
  explanations after finishing.
- **Progress**: the week's streak, totals, accuracy by topic, and practice on the weakest.

Version 0.3.0 added the parts that needed backend support:

- **Daily goal you set yourself**: targets for articles and new words (Progress screen);
  the Today ring gives a third each to articles, new words and the day's quiz.
- **Answers as you go**: daily, weekly, topic and article quizzes check each answer when
  you tap "Check answer" and show the explanation. Mock exams still reveal answers only
  at the end.
- **Word panel**: similar words, how many articles have used the word, and "I know it",
  which stops a word being highlighted or reviewed.
- **Review**: a third answer, "Hard", which brings the word back tomorrow without moving
  it forward.

`ScreensTest` draws each of these screens on the JVM with responses captured from the real
backend and walks its main paths: open a pick, tap a word and save it or mark it known,
review a word, check quiz answers one by one, change the daily goal.

## Not yet verified

- **The app has never been seen on a screen.** The development machine has no hardware
  virtualisation, so no emulator could be started. The screens are compiled and exercised
  by JVM tests with real data, which shows they draw and respond without crashing, but
  nobody has looked at them: spacing, text wrapping, colours and Bangla rendering are
  unchecked. Expect visual fixes on the first real run.
- **Token refresh, offline fallback and the quiz timer** are the logic most worth
  exercising by hand: leave the app open past 30 minutes, switch on airplane mode on the
  feed and on an opened article, and let a mock exam run out.
- **Bangla text rendering** depends on the phone's fonts; check it on a real device.
- Library versions are a known-compatible set from late 2024. Lint reports newer versions
  of all of them; upgrading (AGP 9, current Compose) is a separate, testable step.
- Tokens are stored in app-private DataStore, unencrypted. Acceptable for a personal app;
  move them to the Android Keystore before a public release.
