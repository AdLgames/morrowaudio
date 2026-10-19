# Morrow Audio

A calm, local audiobook library for Android. Warm paper, sage controls, soft abstract covers, and readable serif headings, inspired by the simplicity of Apple Books. Native Android Java UI with Media3 background playback; no account or media server required.

## Install on Samsung

Download [`MorrowAudio.apk`](dist/MorrowAudio.apk), open it on your phone, and allow **Install unknown apps** for the browser or file manager you used. Android 8.0 or newer is required. If Samsung Auto Blocker blocks sideloading, its setting may need adjusting before installation. The downloadable APK is release-signed for sideloading. A private signing-key backup is supplied separately; keep it to sign future updates without losing app data. Never upload that backup to the repository. Play Store publication requires your own developer account and signing setup.

Open Morrow and select **Add an audiobook folder**. In Samsung's file picker, select the folder containing your books and tap **Use this folder**. Android may prevent access to a storage root, the Downloads root, or Android/data: select a dedicated Audiobooks folder instead. Individual audio files can also be selected. Grant notification access when requested for convenient playback controls.

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

## Torrent downloads

Tap the download arrow or **+ → Download a torrent**. Paste a magnet link or choose a `.torrent` file, select a writable Audiobooks folder on your phone or SD card, and tap Download. Downloads show progress, speed and peers, with pause/resume, retry and folder controls. On completion, tap **Add saved books to library** to organize chapters and fetch covers as usual.

BitTorrent requires available peers and exchanges pieces while active; sharing stops when each torrent completes. Transfers can use mobile data. Files download into temporary app-private phone storage, then verified payloads are copied into a new subfolder in your selected destination. Keep enough free space in both locations. Partial copies use `.morrow-part` names and are excluded from library scans. Completed copies stay in the destination if you remove download history or uninstall the app. Restart paused transfers from Downloads after reopening; they do not start on boot. Android 15 may pause a foreground data transfer after its six-hour background allowance; tap Resume to continue when Android permits it. Destination access or storage failures can be retried without downloading verified data again.

Torrent downloads require Android 9 or newer; local playback supports Android 8.

The release APK includes ARM64 and ARMv7 libraries for Samsung phones. Debug builds also include x86_64 for emulator checks.

## Privacy and storage

Only file/folder URIs you choose are granted to Morrow through Android's Storage Access Framework. There is no broad storage permission, analytics, login, or cloud sync. Playback is offline. Torrent transfers connect to trackers/peers and exchange pieces while downloading. Searching sends your typed title and author to `https://openlibrary.org`; choosing an edition downloads its cover from `https://covers.openlibrary.org`. Cover rights belong to their respective owners. Library state and cached covers live in app-private storage; Android backup is disabled. Uninstalling clears progress and bookmarks, but leaves original audio alone. Playback position is saved on state changes and every five seconds during the service lifetime.

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
