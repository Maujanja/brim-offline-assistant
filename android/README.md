# Brim Voice Assistant (Android) — Phase 1

Native Android (Kotlin) offline voice assistant. Phase 1: **push-to-talk** with
Vosk on-device speech recognition and Android TextToSpeech replies.

## Commands (Swahili)

| Amri | Kitendo |
| ---- | ------- |
| washa tochi | Washa tochi ya simu |
| zima tochi | Zima tochi |
| fungua whatsapp | Fungua WhatsApp |
| fungua kamera | Fungua kamera |
| saa ngapi | Soma saa ya sasa |

## Architecture

```
voice/          VoskSpeechRecognizer, CommandParser (+ grammar)
controllers/    FlashlightController, AppLauncher, TimeController
tts/            SpeechManager
ui/             MainActivity + activity_main.xml
service/        VoiceAssistantService (foreground, ready for Phase 2 wake word)
```

## Vosk model

Model files are large and are **not** committed. Follow
`app/src/main/assets/README.md` to drop a Vosk model into
`app/src/main/assets/model-sw/` or `model-en-us/` before building. The APK still
builds without one — the UI will just report "model haijapatikana".

## Build locally

```bash
cd android
gradle wrapper --gradle-version 8.7   # first time
./gradlew assembleDebug               # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease             # app/build/outputs/apk/release/app-release.apk
```

Requires JDK 17 and Android SDK (compileSdk 34, minSdk 24 → Android 7+).

## CI

`.github/workflows/android-build.yml` on every push to `main` and on
`workflow_dispatch`:

- generates a fresh Gradle wrapper
- generates a v1+v2 signing keystore
- builds **both** `app-debug.apk` and `app-release.apk`
- uploads them as `brim-voice-assistant-apks` and attaches them to a
  `build-<n>` GitHub Release

### If an APK refuses to install

Install failures on modern Android usually mean:

1. **Signature not v2** — fixed here: Gradle 8 + AGP 8.5 signs v1+v2+v3 by default and CI now uses a stable keystore.
2. **Play Protect blocks unknown sources** — tap "Install anyway" or disable Play Protect scanning for the install.
3. **Old APK with different signature already installed** — uninstall the previous version first (Settings → Apps → Brim Voice Assistant → Uninstall).
4. **Downgrade** — CI now uses `versionCode 2`; if you had `versionCode 1` installed, uninstall it first.

Try `app-debug.apk` first — it uses the AGP debug keystore which every Android
device accepts.

## Runtime requirements on the phone

- Android 7.0 (API 24) or newer
- Microphone permission
- Camera permission (for tochi)
- Swahili TTS voice data (Settings → System → Languages → Text-to-speech → install Swahili). If missing, the app opens the install screen automatically.
