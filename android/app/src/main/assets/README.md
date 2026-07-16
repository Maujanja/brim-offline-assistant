# Vosk model directory

The app looks for a Vosk model unpacked from `assets/model-sw/` (preferred) or
`assets/model-en-us/` (fallback). Because Vosk models are large (40–500 MB) they
are **not** committed to git. Download once and drop into the correct folder
before building:

```
# Small English model (~40 MB) — quickest to try
curl -L -o /tmp/m.zip https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip
unzip /tmp/m.zip -d android/app/src/main/assets/
mv android/app/src/main/assets/vosk-model-small-en-us-0.15 android/app/src/main/assets/model-en-us
```

For Swahili, use any community Vosk model and place it under `assets/model-sw/`
with the standard files (`am/`, `conf/`, `graph/`, `ivector/`).

The APK still builds without a model — the app just shows "Model haijapatikana"
until you add one.
