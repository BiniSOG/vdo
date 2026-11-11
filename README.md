# VDO — Play · Stream · Download

A native Android video and audio player with a built-in browser and download manager.
Kotlin, Jetpack Compose, Media3 (ExoPlayer). Android 8.0+ (API 26).

## Features

**Player**
- Plays local files and web links: MP4, MKV, WebM, MP3, M4A, FLAC, HLS (`.m3u8`), DASH (`.mpd`) and more
- Gestures: swipe for brightness / volume / seek, double-tap to skip, tap to show controls
- Speed 0.25x–3x, fit / crop / stretch, rotate, screen lock
- Quality, audio-track and subtitle selection
- Picture-in-picture, background playback, "play as audio" for videos
- Lock-screen / notification controls, queue, repeat, shuffle, sleep timer
- Remembers where you stopped

**Downloads**
- Paste a link on Home, or open a page in the browser and tap the download button on detected media
- Plain files: split into byte ranges, fetched over several connections, resumed after interruption
- HLS and DASH streams: every segment is cached, then merged and remuxed into one MP4 / M4A / WebM
  (separate audio and video tracks are muxed together, AES-128 HLS segments are decrypted)
- Quality picker, pause / resume / retry, queue with configurable parallelism, Wi-Fi-only mode
- Runs in a foreground service with progress and completion notifications
- Finished files go to `Movies/VDO` or `Music/VDO`, or to a PIN-protected private folder

**Browser**
- Address bar with search, bookmarks, history, desktop mode, ad/tracker blocking, HTML5 fullscreen video
- Detects media from network requests and `<video>` / `<audio>` elements

**Library**
- Device videos and music with thumbnails, search, sort, folder filter and shuffle

## What it does not do

- It does not bypass DRM (Widevine, FairPlay, PlayReady, SAMPLE-AES). Such streams are reported as protected.
- It does not offer downloads on services whose terms forbid it (YouTube, Netflix, Spotify).
- Live streams can be played but not downloaded until they have ended.

Only download content you own or are allowed to save.

## Build

```
./gradlew :app:assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest    # parser + cache/merge/resume tests
```

Requires JDK 17+ and the Android SDK (platform 35). CI builds the APK on every push
and attaches it to the workflow run as `VDO-debug-apk`.

## Layout

| Path | Contents |
| --- | --- |
| `app/src/main/java/com/vdo/app/download` | Probe, HLS/DASH parsers, segment cache + merge, remuxer, queue, service |
| `app/src/main/java/com/vdo/app/player` | Playback service (Media3 session) and player screen |
| `app/src/main/java/com/vdo/app/browser` | WebView browser and media detection |
| `app/src/main/java/com/vdo/app/ui` | Home, Library, Downloads, Settings, theme |
| `branding/` | Source logo and icon artwork |
