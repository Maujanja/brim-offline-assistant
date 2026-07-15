# Brim Voice Assistant (Android)

Native Android Kotlin app — offline Swahili voice assistant with "Hey Brim" wake word.

## Build locally

```bash
cd android
gradle wrapper --gradle-version 8.7   # first time only
./gradlew assembleRelease
# APK: app/build/outputs/apk/release/app-release.apk
```

Requires JDK 17 and Android SDK (compileSdk 34). Signed with the debug keystore for
convenience; replace `signingConfig` in `app/build.gradle.kts` for production.

## On-device setup

For fully offline recognition, install the Swahili offline speech pack:
Settings → Google → Voice → Offline speech recognition → Download **Kiswahili**.
Install a Swahili TTS voice via Settings → System → Languages → Text-to-speech.

## CI

`.github/workflows/android-build.yml` builds a release APK on every push to `main`
and attaches it to a GitHub Release + workflow artifact `brim-voice-assistant`.

## Notes on Android limitations

Modern Android blocks third-party apps from silently toggling mobile data, airplane
mode, WiFi, and from ending active calls. Those commands open the relevant Settings
panel instead. Everything else runs directly.
