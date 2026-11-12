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
- Music player with a live visualizer and a sound panel: equalizer with presets, bass boost, surround and volume boost

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

**Videos and Music**
- Everything on the device, with thumbnails, search, sort, grid or list, play all and shuffle
- Music grouped by folder, artist, genre, date or name; videos by folder, date or name

**Ads**
- One adaptive AdMob banner above the navigation bar and one on the browser start page. No full-screen ads,
  nothing over the player, and no space is taken until an ad has loaded.
- `app/src/main/res/values/strings.xml` ships Google's public test ids. Replace `admob_app_id` and
  `admob_banner_id` with your own before publishing, and add a consent prompt where the law requires one.

## What it does not do

- It does not bypass DRM (Widevine, FairPlay, PlayReady, SAMPLE-AES). Such streams are reported as protected.
- It does not offer downloads on services whose terms forbid it (YouTube, Netflix, Spotify).
- Live streams can be played but not downloaded until they have ended.

Only download content you own or are allowed to save.

## Build

```
./gradlew :app:assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
```

Requires JDK 17+ and the Android SDK (platform 35). CI builds the APK on every push
and attaches it to the workflow run as `VDO-debug-apk`.

## Tests

```
./gradlew :app:testDebugUnitTest
```

- `DownloadCoreTest` — playlist/manifest parsing and the cache, decrypt, merge and resume pipeline.
- `ScreensTest` — renders every screen with sample data to `app/build/outputs/roborazzi/*.png`.

`DeviceTest` runs on a device or emulator (`./gradlew :app:connectedDebugAndroidTest`). It downloads
an encrypted HLS stream and checks the result is one MP4 with video and audio, downloads a plain file,
uses the private folder, reads the media library, plays a video through the playback service and opens
every screen. It expects sample media on the device and a file server on the host at port 8765:

```
ffmpeg -f lavfi -i testsrc2=size=640x360:rate=24 -f lavfi -i sine=frequency=440 -t 20 \
       -c:v libx264 -pix_fmt yuv420p -c:a aac sample-clip.mp4
ffmpeg -f lavfi -i sine=frequency=330 -t 15 -metadata title="Evening Tone" \
       -metadata artist="Test Artist" -metadata genre="Ambient" tone1.mp3
ffmpeg -f lavfi -i sine=frequency=550 -t 15 -metadata title="Morning Tone" \
       -metadata artist="Second Artist" -metadata genre="Jazz" tone2.mp3
# HLS copy of sample-clip.mp4 with AES-128 in ./hls/index.m3u8, then:
python3 -m http.server 8765
adb push sample-clip.mp4 /sdcard/Movies/
adb push tone1.mp3 /sdcard/Music/Relax/ && adb push tone2.mp3 /sdcard/Music/
```

## Layout

| Path | Contents |
| --- | --- |
| `app/src/main/java/com/vdo/app/download` | Probe, HLS/DASH parsers, segment cache + merge, remuxer, queue, service |
| `app/src/main/java/com/vdo/app/player` | Playback service (Media3 session) and player screen |
| `app/src/main/java/com/vdo/app/browser` | WebView browser and media detection |
| `app/src/main/java/com/vdo/app/ui` | Home, Videos/Music, Downloads, Settings, theme |
| `branding/` | Source logo and icon artwork |
