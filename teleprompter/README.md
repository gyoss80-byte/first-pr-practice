# Prompter

An Android teleprompter that scrolls with your voice, offline, in English and Spanish.
Speech recognition runs on the phone with [Vosk](https://alphacephei.com/vosk/android); the app has no internet permission.

## What it does

- **Scripts:** a list of saved scripts (new, duplicate, delete, import a `.txt` file). Two sample scripts, one English and one Spanish, are there on first launch.
- **Editor:** title, English/Spanish, and the script text. Put stage notes in brackets, like `[pause]`; they show in blue and are never matched.
- **Prompter:** full screen, black background, and the screen stays on. The next word to read is highlighted on the cue line, and words already read are dimmed. Tap any word to jump there. The controls hide after 3 seconds; tap the screen to bring them back.
- **Pace:** while you read, a pill shows your words per minute and turns into "Slow down" when you go more than 10% over your target. Each script shows an estimated read time, which switches to your own measured pace (tracked separately for English and Spanish) once you've read a few scripts.
- **Settings:** font size, line spacing, side margins, colors, cue line position, mirror mode, countdown, matching sensitivity, and an auto-scroll mode that doesn't use the microphone.

The voice-following logic lives in `tracker/` (`ScriptTracker`), a plain Kotlin module with unit tests:

```bash
./gradlew :tracker:test
```

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
