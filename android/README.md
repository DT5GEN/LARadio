# Android app — Santa Monica Radio

Kotlin + Jetpack Compose + Media3 (ExoPlayer). Package `app.smradio`, minSdk 26.

## Open and run
1. Install **Android Studio** (current stable). Open the folder `android/` (File → Open).
2. Wait for Gradle sync (first time downloads Gradle 8.11.1 and libraries — a few minutes).
   If Studio offers "Upgrade Android Gradle Plugin" — you can accept; note the new versions in `docs/PROGRESS.md`.
3. Phone: enable Developer options → USB debugging, connect Pixel, press **Run ▶**.
   Or build an APK: Build → Build Bundle(s)/APK(s) → Build APK(s) (`app/build/outputs/apk/debug/app-debug.apk`).

## How it works (phase 2)
- `data/Catalog.kt` — loads `assets/stations.json` (copy bundled at build time) and refreshes it from
  `https://dt5gen.github.io/LARadio/stations.json` (published by GitHub Actions). Newest copy wins.
- `playback/PlaybackService.kt` — background playback, notification, Bluetooth/steering buttons,
  Android Auto browse tree (Genres → Stations), stream fallback (error or no sound within 8 s → next stream).
- `ui/` — Compose screens: genres, stations, favorites, mini player with prev/next.

## Update the bundled catalog
```
cd ../catalog
npm run build:offline        # no network needed, writes dist/stations.json
copy dist\stations.json ..\android\app\src\main\assets\stations.json
```
