# Brim Voice Assistant (Android) — Phase 1

Native Android (Kotlin) offline voice assistant. Phase 1: **push-to-talk**
English speech recognition powered by Vosk, with replies through Android's
built-in TextToSpeech. Zero setup for the end user — the CI build bundles the
speech model into the APK.

## Commands (English)

| Command | Action |
| ------- | ------ |
| turn on the flashlight / torch | Turn the phone flashlight on |
| turn off the flashlight / torch | Turn the flashlight off |
| open whatsapp | Launch WhatsApp |
| open the camera | Launch the camera |
| what time is it | Speak the current time |

Alternate phrasings ("switch on the light", "launch camera", "tell me the
time") also resolve — see `voice/LanguageManager.kt`.

## Architecture

```
voice/          VoskSpeechRecognizer (language-agnostic)
                LanguageManager + LanguageProfile (registry of offline languages)
                CommandParser (pattern-based, driven by the active profile)
controllers/    FlashlightController, AppLauncher, TimeController
tts/            SpeechManager (locale from LanguageManager)
ui/             MainActivity + activity_main.xml
service/        VoiceAssistantService (foreground, ready for wake-word phase)
```

### Multi-language design

The recognizer, parser and TTS never hardcode a language. Each supported
language is a `LanguageProfile` (asset folder, locale, grammar, command
patterns) registered with `LanguageManager`. Roadmap:

- **Phase 1 (now)** — English (`model-en`).
- **Phase 2** — Swahili (`model-sw`), added by registering a second profile.
- **Phase 3** — Bilingual English + Swahili commands via a merged profile.
- **Phase 4** — Offline "Hey Brim" wake-word detection.

Adding a language later is a two-step change: drop a Vosk model in
`assets/model-<code>/` (or add a CI download step) and register a new
`LanguageProfile`. No changes to the recognizer are needed.

## Vosk model — zero manual setup

The GitHub Actions workflow downloads the official Vosk English small model
(`vosk-model-small-en-us-0.15`, ~40 MB) and unpacks it into
`app/src/main/assets/model-en/` before Gradle runs, so the produced APK
already contains the model. Users just install the APK and go.

For local builds without CI, see `app/src/main/assets/README.md`.

## Build locally

```bash
cd android
gradle wrapper --gradle-version 8.7   # first time
./gradlew assembleDebug               # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease             # app/build/outputs/apk/release/app-release.apk
```

Requires JDK 17 and Android SDK (compileSdk 34, minSdk 24 → Android 7+).
Verified against Android 10 – 14.

## CI

`.github/workflows/android-build.yml` on every push to `main` and on
`workflow_dispatch`:

- generates a fresh Gradle wrapper
- downloads and unpacks the official Vosk English model into assets
- generates a v1+v2+v3 signing keystore
- builds both `app-debug.apk` and `app-release.apk`
- uploads them as `brim-voice-assistant-apks` and attaches them to a
  `build-<n>` GitHub Release

### If an APK refuses to install

1. **Old APK with a different signature is already installed** — uninstall
   the previous version first (Settings → Apps → Brim Voice Assistant).
2. **Play Protect blocks unknown sources** — tap "Install anyway" or
   temporarily disable Play Protect scanning.
3. **Downgrade** — CI uses `versionCode 2`+; uninstall older builds first.

Try `app-debug.apk` first — it uses the AGP debug keystore, which every
Android device accepts.

## Runtime requirements on the phone

- Android 7.0 (API 24) or newer (tested on Android 10, 11, 12, 13, 14)
- Microphone permission
- Camera permission (for the flashlight)
- A TextToSpeech voice — Android ships one by default; the app opens the
  install screen automatically if voice data is missing.
