# Narrumo

Formerly Morrow Audio. Version 1.5.0 (build 8) installs over existing Morrow Audio releases: keep the installed app to retain your library, progress, widget and private AI connection. The Android application ID, signing certificate, backup format and existing server URL remain compatible. Existing `.morrow.zip` backups are supported. The GitHub repository retains its original address.

A calm, local audiobook library for Android. Warm paper, sage controls, soft abstract covers, and readable serif headings, inspired by the simplicity of Apple Books. Native Android Java UI with Media3 background playback; no account or media server required.

## Install on Samsung

Download the latest signed APK from the [Narrumo website](https://morrow-audio.aptnote8.chatgpt.site/#download), open it on your phone, and allow **Install unknown apps** for the browser or file manager you used. Android 8.0 or newer is required. If Samsung Auto Blocker blocks sideloading, its setting may need adjusting before installation. The downloadable APK is release-signed for sideloading. A private signing-key backup is supplied separately; keep it to sign future updates without losing app data. Never upload that backup to the repository. Play Store publication requires your own developer account and signing setup.

Open Narrumo and select **Add an audiobook folder**. In Samsung's file picker, select the folder containing your books and tap **Use this folder**. Android may prevent access to a storage root, the Downloads root, or Android/data: select a dedicated Audiobooks folder instead. Individual audio files can also be selected. Grant notification access when requested for convenient playback controls.

## Offline English transcription (1.5.0)

Read along can now transcribe already-heard audio entirely on the phone, without an AI connection, audio upload, API charge or daily transcription quota. Use **Read along · free offline transcript** on the book details page, or the existing player link, then **Transcribe on this phone · free**. Process up to five minutes per tap, see progress, and cancel without losing saved text. Keep the screen open; leaving cancels unfinished work. Saved text is selectable and exports as SRT. Completed-line and playback-cutoff rules still apply.

The APK bundles Vosk Android 0.3.75 and the small US English 0.15 model (Apache 2.0). It needs about 71 MB of additional private storage on first use and uses device CPU/battery. This is English recognition, not translation; unusual names, accents and punctuation may be less accurate than larger models. Import matching SRT/VTT when you need an exact transcript. Recaps and comics remain separately confirmed, chargeable AI actions using the saved private server connection. There is no automatic cloud fallback.

Builds fetch the fixed model from its original HTTPS publisher, verify SHA-256 `30f26242c4eb449f948e42cb302dd7a686cb29a3423a8367f99ff41780942498`, cache it, and package it inside the APK. The installed app never downloads a model. Reuse the Gradle cache for builds without network access. Model and native-library notices are included in the app. The larger signed 1.5.0 APKs are distributed from the website; the APKs retained under `dist/` are earlier historical releases.

## Features

- Offline audio playback from selected local or SD-card folders, without copying audio into the app.
- MP3, M4B, M4A/AAC, FLAC, Ogg/Opus, WAV and audio MP4, subject to the device's supported codecs. DRM-protected purchases are not supported.
- Folder and album-tag grouping; natural numbered-file ordering; CD/disc subfolder grouping; one M4B file per book.
- Embedded titles, authors, cover art, `cover.jpg` / `folder.jpg` / `front.jpg` sidecar images.
- Nero MP4 `chpl`, QuickTime text-track M4B chapters, and ID3v2.3/v2.4 `CHAP` metadata. Unsupported chapter formats fall back to one chapter per audio file.
- Edit titles/authors, rename/reorder chapters, mark books finished, search and sort the collection.
- Resume position, per-book progress, chapter navigation, 15-second rewind, 30-second forward, speeds from 0.5–3×.
- Background playback, media notifications, lock-screen/Bluetooth controls, audio focus and headphone disconnect handling.
- 15–60 minute sleep timers or pause at the next chapter boundary.
- Bookmarks with notes and a dedicated saved-moments view.
- Optional Open Library search with edition selection and cover preview; use cover only or title and author too. Downloaded covers work offline. Custom covers can be selected from your phone.
- Rescan saved folders to add new books and chapters without resetting listening progress. Scans do not remove missing files or overwrite your edits. To replace moved files, remove the old library entry and import their new folder.

## Free listening tools

All features are available without purchases or subscriptions.

- **Primary colour:** Settings → Primary colour offers Sage, Dusty Blue, Rose Clay, Lavender and Cocoa. The saved accent colours highlighted text, buttons, player/download controls, system dialogs and the home screen widget against the cream background. Backups include this choice.
- **Equalizer & volume boost:** open Settings or the player’s sound link. Five bands, speech/warmth presets and 0–12 dB boost apply in the playback pipeline, including Bluetooth. A gentle limiter controls peaks. Flat or disabled controls preserve the original PCM samples. Changes apply while listening.
- **Home screen widget:** use Settings → Home screen widget, or long-press your Samsung home screen → Widgets → Narrumo. The resizable widget shows the current book and progress, with play/pause, rewind 15 seconds, and forward 30 seconds. Tap the book to open its player.
- **Backup & restore:** Settings → Backup & restore writes a portable `.morrow.zip` to a location you select. It includes your catalog, listening progress, bookmarks, covers and sound settings. Audio files and torrent transfers are excluded. Restoring updates matching books while keeping other books, and pauses playback first. ZIP entries, sizes, references and images are validated before applying the backup; the library file is replaced atomically. The archive is not encrypted: store it somewhere you trust.
- **Reconnect folders:** Android does not transfer file permissions in backups. After reinstalling or moving audio, use Reconnect an audio folder to select its original folder or corresponding replacement. Standard hierarchical document paths are remapped while preserving progress/bookmarks; opaque provider IDs may need the original folder layout.
- **Uncapped downloads:** Narrumo applies no download-rate limit. Peer discovery remains tuned for quick startup. Swarm and network availability still determine actual speed.

## Foundation polish and AI reading lab (1.3.0)

- Multi-disc imports keep natural disc/folder order before file order; repeated folder selections no longer add the same file twice within a scan.
- Completed playback keeps its finished flag and restart position through subsequent progress snapshots and widget updates.
- Settings → Getting started & updates explains Samsung setup, folder access, backups and manual APK updates. Playback errors link directly to folder reconnection and help.
- Settings → AI reading lab is an explicit passage-only experiment with a real OpenAI-backed server connection. Paste a short passage or load the original sample, then request a recap or question. No audio transcription, whole-book knowledge, listening-position awareness, or automatic library upload is claimed.
- Live AI requires deploying the private backend and setting your own provider key there. The app never contains an OpenAI key or shared public test token. Setup: [`ai-backend/README.md`](ai-backend/README.md). GitHub builds/tests the code; a Worker hosts live requests. No provider credentials are needed for CI.

## Torrent downloads

Tap the download arrow or **+ → Download a torrent**. Paste a magnet link or choose a `.torrent` file, select a writable Audiobooks folder on your phone or SD card, and tap Download. Downloads show progress, speed and peers, with pause/resume, retry and folder controls. On completion, tap **Add saved books to library** to organize chapters and fetch covers as usual.

Peer discovery reuses a local DHT routing cache between transfers. Supplied trackers announce in parallel, and magnet metadata wakes the service immediately instead of waiting for its progress poll. Known torrent metadata starts payload transfers immediately after validation. Connection limits and the upload allowance are tuned for quicker peer negotiation; download speed still depends on the swarm and your connection. Private-torrent discovery restrictions remain enforced by libtorrent; no extra public trackers are injected.

HTTPS trackers use Android’s system certificate authorities with certificate validation enabled.

BitTorrent requires available peers and exchanges pieces while active; sharing stops when each torrent completes. Transfers can use mobile data. Files download into temporary app-private phone storage, then verified payloads are copied into a new subfolder in your selected destination. Keep enough free space in both locations. Partial copies use `.morrow-part` names and are excluded from library scans. Completed copies stay in the destination if you remove download history or uninstall the app. Restart paused transfers from Downloads after reopening; they do not start on boot. Android 15 may pause a foreground data transfer after its six-hour background allowance; tap Resume to continue when Android permits it. Destination access or storage failures can be retried without downloading verified data again.

Torrent downloads require Android 9 or newer; local playback supports Android 8.

The main APK is for ARM64 Samsung phones. For a phone running 32-bit Android, choose the ARM32 download on the website. Both use the same signing certificate and version. Build the 32-bit release with `./gradlew -PmorrowArm32 assembleRelease`. Debug builds include ARM64, ARMv7 and x86_64 for emulator checks.

## Privacy and storage

Only file/folder URIs you choose are granted to Narrumo through Android's Storage Access Framework. There is no broad storage permission, analytics, login, or cloud sync. Playback is offline. Torrent transfers connect to trackers/peers and exchange pieces while downloading. Searching sends your typed title and author to `https://openlibrary.org`; choosing an edition downloads its cover from `https://covers.openlibrary.org`. Cover rights belong to their respective owners. Library state and cached covers live in app-private storage; Android backup is disabled. Uninstalling clears progress and bookmarks, but leaves original audio alone. Playback position is saved on state changes and every five seconds during the service lifetime.

Use **Unrestricted** battery access in Samsung's App info → Battery settings if your phone stops background playback. Original files must remain available; moving them or removing an SD card breaks playback until they are reimported.

## Build

Install JDK 17 and Android SDK 35, then run:

```sh
./gradlew testDebugUnitTest lintDebug assembleDebug
```

Set `ANDROID_HOME` or an untracked `local.properties` with `sdk.dir=/path/to/android-sdk`. Output: `app/build/outputs/apk/debug/app-debug.apk`. CI runs the same checks and provides a debug APK artifact for each build. Debug APKs use a different signing key from the downloadable release APK. To reproduce a signed release, set `MORROW_KEYSTORE`, `MORROW_PASSWORD_FILE` and `ANDROID_HOME`, then run `scripts/sign-release.sh`. Do not commit personal signing keys or local SDK paths.

For on-device integration checks, run `./gradlew connectedDebugAndroidTest` with an Android emulator or test device attached. The debug build includes a test-only document provider (excluded from the release APK); the test imports three real WAV chapters, and checks natural ordering, playback, seeking, speed, chapter-end sleep, and persisted progress/bookmarks. Torrent tests seed synthetic WAV audio over loopback, transfer both a torrent file and magnet link, pause/resume the native engine, and verify byte-exact SAF exports and library import. It clears the test app’s library, so use a test installation.

## Source layout

- `MainActivity`: native library, player, book editing, and cover lookup UI.
- `PlaybackService`: Media3 audio session, resume state and sleep timers.
- `Library`: atomic local JSON persistence and audiobook model.
- `Importer`: SAF folder scans, tag/art extraction and duplicate handling.
- `ChapterParser`: bounded MP4 and ID3 chapter readers.
- `TorrentEngine`, `TorrentService`: native libtorrent4j transfers, foreground notification, pause and resume checkpoints.
- `DownloadsActivity`, `TorrentStore`, `TorrentExporter`: download queue, persisted progress and verified SAF folder exports.
- `CoverSearch`: HTTPS Open Library metadata requests.
- `ArtworkView`, `IconView`: locally drawn original artwork and interface icons.

Third-party notices are included inside the app. This is a local audiobook app; network Plex servers and cross-device synchronization are outside this version's scope.


### Read along and AI companion (1.4.0)
Open a book's player → **Read along · recap · illustrate**. Import a chapter SRT/VTT or explicitly transcribe up to five minutes already reached in playback. Completed timed lines appear as you listen. Long-press to recap, ask about, or illustrate a passage. Catch me up uses available recent transcript, with a listening cutoff and quoted evidence. One image request creates a medium-quality, four-panel graphic-novel page; three image attempts per UTC day are shared across the private server. Saved pages reopen locally and export as JPEG. Transcripts export as SRT; these assets are not included in library backups. Live features require the updated [private backend](ai-backend/README.md); local listening/import/export remain free and offline.
