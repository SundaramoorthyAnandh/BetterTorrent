# BetterTorrent

Android app: paste a magnet link or open a `.torrent` file, then **stream it right away** or **download it** to your library.

Downloads work like [Seedr](https://www.seedr.cc): torrents land in the app's own storage (your private "cloud" folder, no storage permission needed), every file can be played while it downloads, and finished files can be saved to the phone's `Download/BetterTorrent` folder. The difference is that the torrent engine (libtorrent) runs **on your phone**, so there is no server and no quota.

## Install

1. Download [`dist/BetterTorrent-2.0.0.apk`](dist/BetterTorrent-1.2.0.apk) on your phone.
2. Open it. Android asks you to allow installs from your browser or file manager. Allow it, then tap **Install**.

Requires Android 10 or newer (arm64, armv7 and x86_64).

**Upgrading from 1.x:** version 2.0 is signed with a new release key, so uninstall the old version first. Save anything you want to keep with Library → Save to phone Downloads before uninstalling. Later versions install over 2.0 normally.

**Play Protect:** Google flags sideloaded apps it hasn't seen before. If it blocks the install, the developer can file a [Play Protect appeal](https://support.google.com/googleplay/android-developer/contact/protectappeals) with the APK's SHA-256. Registering in the Android Developer Console ([developer verification](https://developer.android.com/developer-verification/guides)) becomes required for sideloaded apps globally in 2027.

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
- [x] **Settings**: separate "Download on Wi‑Fi" and "Download on mobile data" switches, speed limits, max active downloads, seeding, theme (System/Light/Dark plus Material You), font, default player

- [x] **Fonts**: System default, sans (Inter, Poppins, Nunito, Manrope) or serif (Lora, Merriweather, Source Serif 4, Literata). All are bundled, so they work offline

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

**Signing:** official builds are signed with the BetterTorrent release key, which is never committed. Locally, add a `keystore.properties` file (`storeFile`, `storePassword`, `keyAlias`, `keyPassword`). On GitHub, set the `BT_KEYSTORE_BASE64`, `BT_KEYSTORE_PASSWORD`, `BT_KEY_ALIAS` and `BT_KEY_PASSWORD` secrets used by `.github/workflows/android.yml`. Without a key, builds fall back to the debug key and won't install over official ones.

## Notes

- Fonts are under the SIL Open Font License 1.1 (texts in `app/src/main/assets/licenses/fonts`, also shown in Settings → Font licenses). Inter, Poppins, Nunito, Manrope, Source Serif 4 and Literata are trimmed to Latin characters and four weights. Lora and Merriweather have Reserved Font Names, so they ship unmodified.

- Only download content you have the right to share.
- The built-in player handles what the phone's decoders support. For formats it can't play (for example some DTS/AC3 audio), use **Open in another player**.
