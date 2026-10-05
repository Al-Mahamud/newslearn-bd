# NewsLearn BD — Android app

Kotlin + Jetpack Compose client for the NewsLearn BD backend. The plan and phase breakdown
are in [`../docs/APP_PLAN.md`](../docs/APP_PLAN.md).

## Run

1. Start the backend (see [`../backend/README.md`](../backend/README.md)) so it listens on
   port 8000.
2. Open this `android/` folder in Android Studio and run the `app` configuration on an
   emulator. The default API address, `http://10.0.2.2:8000/`, is the emulator's name for
   the computer it runs on.
3. Create an account on the sign-in screen. The first account on a fresh backend is its admin.

To use a real phone or a deployed backend, add to `local.properties`:

```properties
newslearn.apiBaseUrl=https://your-server.example/
```

Plain `http://` is only allowed for `10.0.2.2` and `localhost`; anything else must be HTTPS.

## Command line

```bash
export JAVA_HOME=~/.jdks/jdk-17 ANDROID_HOME=~/Android/Sdk
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest    # API contract test
./gradlew lintDebug
```

## Layout

```
app/src/main/java/com/newslearn/bd/
  NewsLearnApp.kt     Application and AppContainer (manual dependency injection)
  data/remote/        API models, Retrofit service, OkHttp client with token refresh
  data/local/         session storage (DataStore) and offline cache (Room)
  data/repo/          repositories; every call returns Result<T>
  ui/                 one package per screen: Composables plus their ViewModel
app/src/test/         contract test against responses captured from the backend
```

## Keeping the app and the API in step

`ApiContractTest` decodes real responses stored in `app/src/test/resources/fixtures`.
After changing a response shape in the backend, regenerate them and re-run the test:

```bash
cd ../backend
PYTHONPATH=. python scripts/export_fixtures.py ../android/app/src/test/resources/fixtures
```
