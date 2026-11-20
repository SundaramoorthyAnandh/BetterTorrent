# BetterTorrent

Android app: paste a magnet link or open a `.torrent` file, then **stream it right away** or **download it** to your library.

Downloads work like [Seedr](https://www.seedr.cc): torrents land in the app's own storage (your private "cloud" folder), every file can be played while it downloads, and finished files can be saved to the phone's `Download/BetterTorrent` folder. The difference is that the torrent engine (libtorrent) runs **on your phone**, so there is no server and no quota.

## Install

1. Download [`dist/BetterTorrent-1.2.1.apk`](dist/BetterTorrent-1.2.1.apk) on your phone.
2. Open it. Android asks you to allow installs from your browser or file manager. Allow it, then tap **Install**.

Requires Android 10 or newer (arm64, armv7 and x86_64).

If you installed 2.0.0 (signed with a different key), uninstall it first. Versions 1.2.0 and 1.3.0 update in place.

If the app ever crashes, the next launch offers **Share report** with the error details. Nothing is sent automatically.

## Features

- [x] **Paste magnet link** or a bare info-hash, or **open a .torrent** file
- [x] **Stream while downloading**: sequential download plus piece deadlines, and a built-in Media3/ExoPlayer player with seeking that keeps the screen on while playing
- [x] **Seedr-style library**: folder browser, storage bar, live progress, Play / Open with / Save to phone Downloads / Share / Delete
- [x] **Pick files in a torrent**: choose files before adding, and switch files on or off later
- [x] **Background downloads**: keep going with the screen off (foreground service, wake lock, and a one-time battery-optimization prompt), with a progress notification, Pause all / Resume all / Exit actions, and a "Download complete" alert
- [x] **Works on aggressive phones** (Vivo, Xiaomi, Oppo, Realme, OnePlus, Huawei, Honor, Samsung and more): a setup guide that detects the phone brand and opens its autostart / background-power screens, a banner when the phone killed the app mid-download, auto-restart if killed, and resume after the phone restarts
- [x] **Open in VLC / MX Player** or any cast app, via a local stream URL
- [x] **Subtitles**: from the torrent (`.srt`, `.vtt`, `.ass`, `.ssa`), from the phone, or tracks built into the video
- [x] **Magnet / .torrent handler**: tap a magnet link anywhere, open `.torrent` files, share links to the app, and get an offer to add a magnet you just copied
- [x] **Settings**: separate "Download on Wi‑Fi" and "Download on mobile data" switches, speed limits, download folder, max active downloads, seeding, theme (System/Light/Dark plus Material You), default player

## How it works

| Piece | What it does |
|---|---|
| `engine/TorrentEngine.kt` | libtorrent session (via libtorrent4j): add/restore torrents, resume data, file priorities, queueing, pause per network type |
| `engine/StreamServer.kt` | Loopback HTTP server with Range support. Reads wait for verified pieces and set deadlines ahead of the playhead. URLs carry a random token |
| `service/TorrentService.kt` | Foreground service, notification, and wake/Wi‑Fi locks while transferring |
| `service/BackgroundState.kt` | Kill detection, watchdog alarms that restart the service, and resume on boot |
| `util/OemBackground.kt` | Per-brand setup steps and deep links into each phone maker's battery/autostart settings |
| `ui/` | Jetpack Compose (Material 3): Transfers, Add sheet, Torrent detail, Library, Settings, Player |

## Build

```bash
./gradlew :app:testReleaseUnitTest :app:assembleRelease
# APK: app/build/outputs/apk/release/app-release.apk
```

You need JDK 17 or newer and the Android SDK (platform 36). `StreamServerTest` runs a real seeder and leecher on loopback. It checks that ranged reads return the right bytes before the download finishes.

**Signing:** without a key, release builds are signed with the debug key. To sign with your own key (needed so updates install over older builds), add a `keystore.properties` file (`storeFile`, `storePassword`, `keyAlias`, `keyPassword`) or set the `BT_*` secrets used by `.github/workflows/android.yml`.

## Notes

- Only download content you have the right to share.
- The built-in player handles what the phone's decoders support. For formats it can't play (for example some DTS/AC3 audio), use **Open in another player**.
