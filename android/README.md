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

Requires JDK 17 and Android SDK (compileSdk 34, minSdk 21 → Android 5+).
The APK now uses 16 KB-safe Android packaging checks for newer Android 15+
phones while still supporting Android 5+.

## CI

`.github/workflows/android-build.yml` on every push to `main` and on
`workflow_dispatch`:

- downloads and unpacks the official Vosk English model into assets
- restores a cached CI signing keystore, generating it once if needed
- builds two universal installable APKs:
  - `Brim-Voice-Assistant.apk` — official package `com.brimedge.voiceassistant`
  - `Brim-Voice-Assistant-Clean-Install.apk` — fallback package
    `com.brimedge.voiceassistant.clean` for phones blocked by old test-build
    signature conflicts
- verifies APK metadata, 16 KB native-library zip alignment, ELF alignment and
  signatures before upload
- uploads it as `Brim-Voice-Assistant-installable-apk` and attaches it to a
  `build-<n>` GitHub Release. Downloading from **Releases** gives the APK file
  directly; downloading from **Actions artifacts** gives a ZIP that must be
  extracted first.

### If an APK refuses to install

1. **Old APK with a different signature is already installed** — uninstall
   the previous version first (Settings → Apps → Brim Voice Assistant).
2. **Play Protect blocks unknown sources** — tap "Install anyway" or
   temporarily disable Play Protect scanning.
3. **Downgrade** — CI uses `versionCode 30`+; uninstall older builds first.
4. **Older CI build had a different signature** — uninstall the old Brim Voice
   Assistant once, then install the new APK. If Android still refuses because
   an old copy is hidden in another user/profile, install
   `Brim-Voice-Assistant-Clean-Install.apk`; it uses a fresh package name and
   bypasses that signature conflict.
5. **Android 15 native-library rejection** — use build `1.0.5` / `versionCode 20`
   or newer. Older builds used a Vosk native library that was not 16 KB aligned
   on 64-bit Android 15 devices and could fail at the installer stage.

Install `Brim-Voice-Assistant.apk` first. If it still says "App not installed",
install `Brim-Voice-Assistant-Clean-Install.apk`. If you download from GitHub
Actions, GitHub may give you a ZIP artifact first — extract it, then install the
APK inside. The Releases page provides the APK files directly.

## Runtime requirements on the phone

- Android 5.0 (API 21) or newer (tested on Android 10, 11, 12, 13, 14)
- Microphone permission
- Camera permission (for the flashlight)
- A TextToSpeech voice — Android ships one by default; the app opens the
  install screen automatically if voice data is missing.
