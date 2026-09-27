# Prompter

An Android teleprompter that scrolls with your voice, offline, in English and Spanish.
Speech recognition runs on the phone with [Vosk](https://alphacephei.com/vosk/android); the app has no internet permission.

## Status

Build step 2 of the spec: a **recognition test screen** that shows what the phone hears, live.
Test it in English and Spanish before the prompter itself is built.

## Installing on your phone

Every push that touches `teleprompter/` builds a new APK with GitHub Actions and publishes it to the
**prompter-latest** release of this repo.

1. On your Android phone, open the repo's **Releases** page and tap **Prompter.apk**.
2. When Android asks, allow your browser to install unknown apps, then tap **Install**.
3. Open **Prompter**, pick English or Español, tap **Start listening** and allow the microphone.

The first launch copies the speech models onto the phone, which can take up to a minute.
Later builds install over the old one, because every build is signed with the same debug key (`app/debug.keystore`).

## Building locally

Requires JDK 17 and the Android SDK (Android Studio installs both).

```bash
./scripts/fetch-models.sh   # downloads the English and Spanish models into app/src/main/assets (about 80 MB)
./gradlew assembleDebug     # APK lands in app/build/outputs/apk/debug/
```
