# Vosk speech models

**End users don't need to do anything.** The GitHub Actions workflow downloads
the official Vosk English small model (`vosk-model-small-en-us-0.15`, ~40 MB)
at build time and unpacks it into `assets/model-en/`. The resulting APK ships
with the model bundled, so speech recognition works fully offline the moment
the app is installed.

## Layout

```
assets/
  model-en/          <- English (downloaded by CI, git-ignored)
  model-sw/          <- Swahili (Phase 2, add later)
  model-<code>/      <- Future languages
```

`LanguageManager` in `voice/` picks which folder to load from — the recognizer
itself is language-agnostic. To add a new language later, drop a Vosk model
under `assets/model-<code>/` (or extend the CI workflow to download it) and
register a new `LanguageProfile` in `LanguageManager`.

## Local builds

If you build locally without CI, either:

1. Run the download step yourself:
   ```bash
   curl -L -o /tmp/m.zip https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip
   unzip /tmp/m.zip -d android/app/src/main/assets/
   mv android/app/src/main/assets/vosk-model-small-en-us-0.15 android/app/src/main/assets/model-en
   ```
2. Or just push to `main` and let the workflow produce the APK.

The APK still builds without a model — the app will simply report that the
speech model is missing.
