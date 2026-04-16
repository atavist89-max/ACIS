# ACIS — Sanctions Screening App

An Android app for on-device sanctions screening using **Gemma 4 E2B** via Google's [LiteRT-LM](https://ai.google.dev/edge/litert/models/litert_lm) engine. It queries a local OpenSanctions database and entities dataset, then uses the LLM to generate factual, data-only risk assessments.

![Build](https://github.com/atavist89-max/ACIS/actions/workflows/build.yml/badge.svg)

## What it does

- Loads a list of entities (`Person` / `Company`) from a local `entities.ftm.json` file.
- Lets you select an entity from a dropdown (first 100 shown).
- Retrieves the full entity record and queries a local SQLite sanctions database (`opensanctions.sqlite`).
- Sends the entity data + sanctions matches to the on-device LLM with strict instructions to analyze **only the provided data**.
- Displays a concise, factual risk assessment.

## Data Requirements

Place the following files on device:

```
/storage/emulated/0/Download/GhostModels/gemma-4-e2b.litertlm
/storage/emulated/0/Download/GhostModels/CounterpartyProject/sanctions_data/opensanctions.sqlite
/storage/emulated/0/Download/GhostModels/CounterpartyProject/sanctions_data/entities.ftm.json
```

The app verifies each file exists and meets minimum size checks before use.

## System Requirements

- **Android device** running API 36+ (Android 16+).
- **JDK 21** to build (required by `litertlm-android:0.10.0`).
- **Android SDK 36**.
- **Storage permission** (`All files access` on Android 11+) so the app can read the model and data files from external storage.

## Tech Stack

- **Language:** Kotlin 2.1.20
- **UI:** Jetpack Compose (Material 3)
- **Inference Engine:** `com.google.ai.edge.litertlm:litertlm-android:0.10.0`
- **Local Database:** SQLite (Android built-in)
- **Build:** Gradle 8.6, Android Gradle Plugin 8.3.0

## Build

```bash
./gradlew assembleDebug
```

APK output:
```
app/build/outputs/apk/debug/app-debug.apk
```

## Install & Run

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

Launch the app, grant storage permission if prompted, then:
1. Select an entity from the dropdown.
2. Tap **Screen Entity**.
3. Wait for the analysis to complete.

| Status Light | Meaning |
|--------------|---------|
| Gray         | Idle / waiting for selection |
| Yellow       | Loading data / querying DB / running LLM |
| Green        | Analysis complete |
| Red          | Error occurred |

## LLM Configuration

- **Temperature:** `0.5` (factual, deterministic output)
- **Top-K:** `40`
- **Top-P:** `0.9`
- **Backend:** GPU (falls back to CPU if unavailable)
- **Max tokens:** `2048`

The prompt explicitly instructs the model not to use external knowledge and to base the entire analysis strictly on the provided local data.

## Debugging

Tap **View Logs** in the app to see a timestamped event log, or use Android Studio / `adb logcat` with the `BugLogger` tag. All major operations (permission checks, file loading, DB queries, LLM initialization, and inference) are logged.

## Project Structure

```
app/src/main/java/com/llmtest/BugLogger.kt      # Internal file-based logger
app/src/main/java/com/llmtest/EntityData.kt     # Entity data class
app/src/main/java/com/llmtest/GhostPaths.kt     # Paths to model + data files
app/src/main/java/com/llmtest/MainActivity.kt   # UI + screening logic
app/src/main/AndroidManifest.xml                # Permissions & native libs
app/build.gradle                                # App module build config
build.gradle                                    # Root project plugins
settings.gradle                                 # Repositories & modules
```

## CI

A GitHub Actions workflow (`.github/workflows/build.yml`) builds the debug APK on every push and pull request to `main`.

## License

This is a personal test / debugging project. No production warranty implied.
