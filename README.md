# Narrumo

Formerly Morrow Audio. Version 2.0.1 (build 16) installs over existing Morrow Audio releases: keep the installed app to retain your library, progress, widget and private AI connection. The Android application ID, signing certificate, backup format and existing server URL remain compatible. Existing `.morrow.zip` backups are supported. The GitHub repository retains its original address.

A calm, local audiobook library for Android. Warm paper, sage controls, soft abstract covers, and readable serif headings, inspired by the simplicity of Apple Books. Native Android Java UI with Media3 background playback; no account or media server required.

## Install on Samsung

Download the latest signed APK from the [Narrumo website](https://morrow-audio.aptnote8.chatgpt.site/#download), open it on your phone, and allow **Install unknown apps** for the browser or file manager you used. Android 8.0 or newer is required. If Samsung Auto Blocker blocks sideloading, its setting may need adjusting before installation. The downloadable APK is release-signed for sideloading. A private signing-key backup is supplied separately; keep it to sign future updates without losing app data. Never upload that backup to the repository. Play Store publication requires your own developer account and signing setup.

Open Narrumo and select **Add an audiobook folder**. In Samsung's file picker, select the folder containing your books and tap **Use this folder**. Android may prevent access to a storage root, the Downloads root, or Android/data: select a dedicated Audiobooks folder instead. Individual audio files can also be selected. Grant notification access when requested for convenient playback controls.

## A quieter chapter studio (2.0.1)

The main illustration card has two choices: **Illustrate a passage** and **Let AI illustrate the chapter**. Passage previews, selection changes and picture-count controls appear only after choosing Passage. The top-right **•••** menu groups Transcription (including speech quality, rebuild/restore and import/export), Text & book words (ask, recap and reviewed spellings), Saved chapter pictures, Allowance & privacy and AI connection settings. A free transcription shortcut appears when there is no heard transcript; active processing keeps a Stop control visible. Existing saved text, pictures and listening progress remain compatible.

## Desktop listening room (2.0.0)

Open https://morrow-audio.aptnote8.chatgpt.site/listen/ in desktop Chrome or Edge. Choose a local folder; library references, chapter, position, bookmarks, covers and preferences are retained in browser storage. Return to Continue listening or install it from the browser. Folder permissions may need reconnecting. Audio stays local. A verified 60 MB Whisper model supports offline punctuation and bounded book spelling prompts. Transcription checkpoints completed sections; closing stops processing. Optional text/image AI uses the existing private server connection and shared quotas. No accounts, cross-device sync or desktop torrent engine are included. [Desktop source and build notes](desktop/README.md).

## Accurate local speech (2.0.0)

Open **AI → Speech quality & book spellings → Download Accurate**. A one-time, SHA-256-verified 60 MB Whisper base.en Q5_1 model downloads from Hugging Face; subsequent recognition is offline. Fast Vosk stays bundled and selected until you explicitly choose Accurate. Whisper produces punctuation and natural segments and accepts up to 100 words of local spelling hints, including approved Book words behind the listening cutoff. Hints guide decoding, not guaranteed recognition or learned pronunciations. Nothing is trained from book audio. Larger models use more memory/CPU; names, noise and speech can still be misheard.

Existing transcripts are retained. In Chapter studio, choose **Rebuild this chapter’s transcript** after switching recognisers; the current text is preserved, and **Restore previous transcript** can bring it back. Rebuilding invalidates that chapter’s recap, requiring a separately authorised new recap request. Then transcribe heard sections locally or select completed chapters for background processing. The model and spelling hints are private device data, excluded from library backups. The download host receives only the public model request, never audio or book metadata.

Native recognition uses pinned **whisper.cpp 1.9.5** (MIT), compiled for ARM64, ARMv7 and x86_64 with 16 KB alignment; model hash \`4baf70dd0d7c4247ba2b81fafd9c01005ac77c2f9ef064e00dcf195d0e2fdd2f\`. Builds need Android NDK 27.2.12479018, CMake and Ninja. The test-only public-domain JFK fixture and verified model exercise real offline JNI recognition; the test model is excluded from release APKs. We cannot claim a Dune word-error-rate improvement without a recording and checked reference text.

## Illustration review (1.9.1)

In Chapter studio, review the suggested source passage, choose **Keep this passage**, then pick 1–3 pictures. **Select another passage** returns to the transcript; using selected text, a free local suggestion or optional AI choice returns to passage review. The confirmation sheet shows the exact source, picture count and last checked shared allowance before any image request. Each successful page saves automatically before opening its preview. The preview offers **Chapter gallery** and **Generate another · uses allowance**, which returns to passage selection and requires another confirmation. Reopening an identical cached picture is free; choose different text for a new scene. No automatic paid retries are made.

## Android Auto (1.9.0)

Connect your phone to an Android Auto head unit and open **Narrumo**. **Continue listening** shows recent unfinished books. **Audiobooks** opens your local library; choose a book to resume its saved chapter/position or select a chapter. Covers, title/author search, play/pause, previous/next chapter and seek controls use the same playback session as your phone. Large libraries and long chapter lists are grouped into browse folders. Import audio and grant folder access on your phone first; Android Auto does not copy the books to your car.

For this directly downloaded APK, Android Auto may hide Narrumo until **Unknown sources** is enabled in Android Auto’s own developer settings: open Android Auto settings, tap the Version/version-permission section ten times, confirm developer mode, then open the overflow menu → Developer settings → Unknown sources. Reconnect the phone and check Customize launcher if needed. [Google’s testing instructions](https://developer.android.com/training/cars/testing#unknown-sources) describe this option for media apps. This is phone-projected Android Auto; installing directly on an Android Automotive OS car is a separate build.

The app uses Media3 `MediaLibraryService`, its platform media-browser bridge, an automotive media declaration and media-button resumption. Car selections resolve only known local library IDs, preserving existing SAF access and M4B chapter clipping. Private restore/widget/sleep maintenance commands remain available only to the app’s own UID. Browse artwork is small embedded image data, so the car does not need access to private cover files. Real-vehicle and Desktop Head Unit verification still require access to that hardware/runtime; the automated test connects through both Android’s platform MediaBrowser and Media3 MediaBrowser.

## One AI section and visible illustrations (1.9.0)

The bottom **AI** tab contains chapter recaps, transcripts, illustrations, Book words, passage questions and connection settings. Book details, Settings and the listening screen all lead here with the book/chapter context. The recap popup has a direct **Illustrate this chapter** action. Chapter studio keeps **AI tools** and **Back to listening** available in its footer.

The illustration panel sits directly beneath a bounded, scrollable transcript. It highlights a free suggested source passage; select different transcript text and use **Use for illustration** to replace it. **Let AI choose a key moment** optionally sends up to 100,000 already-heard transcript characters in one confirmed text request. The reply must point to exact contiguous source cues, up to 2,500 characters. No image is generated until you review the passage and confirm a picture count.

Choose **1, 2 or 3 pictures** from one chapter. Each is a medium-quality four-panel graphic-novel page, using a different visual framing of the selected passage. Attempts are sequential, individually cached and bounded by the existing **three shared daily image attempts**; failed attempts count and stop the batch. Leaving stops pages not yet started. Saved illustrations remain linked to their chapter even without a saved recap, and unheard source material stays hidden after rewinding.

[View the app user-flow diagram](docs/ai-user-flow.md).

## Catch up (1.8.0)

Tap **Catch up · last chapter** beneath the home screen’s continue-listening card to open the last completed chapter’s saved recap, or prepare it in the background. New summaries require a one-time explanation and consent; saved recaps reopen locally without an AI request. The cream recap sheet uses the app’s serif headings and selected accent colour. A completion notification invites you back to the exact saved chapter, with private lock-screen content.

The bottom **Catch up** tab has book and chapter dropdowns, optional multiple selection, saved recaps and graphic scenes linked to their source chapters. Its allowance card reads the private server’s **25 chapter attempts shared across all testers per UTC day**, separate from the 20-request budget for other text features. Failed generation attempts count; cached reads and allowance checks are free. Graphic scenes retain their separate shared limit of three medium-quality pages per day.

A chapter’s complete local transcript is sent in one summary request, with up to four bounded earlier saved recaps for story context. The prompt asks for grounded emotional movement, motives, stakes, themes and key plot points, without future events or external book knowledge. Summaries stay within 100 words plus exact supporting source quotes. Local transcription can mishear names and AI can make mistakes; the source quotes help review the recap. Only completed chapters are eligible, and rewinding hides summaries and linked scenes beyond the new listening position.

The older release notes below describe when the underlying features were introduced; the current chapter allowance and selection controls are those above.

## Listening-screen recap card (1.7.1)

A compact **Chapter summaries** card now sits immediately below play/pause and above the smaller listening tools. Its soft background and small book icon keep it visible without competing with playback. Tap **Choose** to select chapters. Once a saved recap is available, a two-line preview appears; tap **View** to read its overview and key points locally, with **Choose chapters** for further work. The cover adapts to shorter phone screens so the recap card can fit with playback controls. No AI request occurs when reading or opening this card. Only completed, already-reached chapters can appear, and rewinding hides an ineligible preview. Cache reads run off the UI thread, with periodic refresh after background generation.

## Background chapter summaries (1.7.0)

Open a book → **Chapter summaries · select chapters** (also in the player). Select completed chapters individually, or **Select available chapters**, then **Summarise selected chapters**. Confirm one batch of up to 20 chapters. Each chapter is transcribed locally in bounded sections, with progress in a foreground notification. You can leave the screen; there is no need to tap Transcribe for every section. Finished chapters make **one AI generation attempt each**, using the complete available chapter transcript, then show a concise overview and up to three key points (100 words total), with supporting source quotes available separately. Saved summaries reopen offline without another request. The read-along screen, import/export and Book words remain available through a secondary link.

Default selection and viewing are limited to chapters whose end you have reached. Rewinding before a chapter hides its summary. A full chapter is never silently replaced with its last few minutes: audio must be processed through its declared duration before requesting a summary. Unknown durations or chapters over six hours need corrected/smaller chapter metadata; transcripts over 100,000 characters need splitting and cause no AI call. A chapter request can cost more than a short passage or 100-word vocabulary guide; the 100-word limit applies to the result, not the transcript input. Shared text quota remains 20 per UTC day.

Pause from the notification or this screen. Five-minute progress checkpoints and queued selections are durable; reopening offers Resume after interruption. On Android 15 the user-started service uses `mediaProcessing`, with `dataSync` on older versions; CPU wake time is bounded. Android/Samsung battery restrictions, force-stop, reboot, low memory or the OS media-processing time limit can interrupt it. It does not auto-start from boot. Resuming an attempted AI request only looks for its server-cached result and cannot regenerate or charge it automatically; select the chapter in a new confirmed batch to authorise another attempt after failure. Cache lookups are network calls but not AI generations. Temporary decoded audio is deleted on cancellation/completion. Transcripts, summary cards and queue checkpoints stay outside library/Android backups. Changing source identity invalidates summaries; replacing a transcript clears its existing summary.

## Book words: a tiny reviewed AI guide (1.6.0)

Read along → **Book words · optional AI guide** uses a completed, already-heard excerpt (up to 3,000 characters), title and author to request at most **8 unusual terms within 100 words total**, including readable pronunciation hints. Confirm before sharing text with your private backend and OpenAI; no audio is sent. One successful guide is cached per book/audio identity; reopening and applying approved corrections work offline. It shares the existing 20-request daily server text budget. Input is bounded and provider output capped at 800 tokens; 100 words is not a currency limit. Failed attempts count and are never automatically retried.

Review each proposed “heard phrase → spelling” before selecting it. Only approved exact whole phrases are substituted in the read-along display, within individual cues, without cascading replacements or changing timestamps. **Show original transcript** reverses the display; SRT export always retains the original. Imported text can also receive reviewed display substitutions. The guide hides and stops applying if playback moves before its source excerpt. Delete a guide to discard it; requesting another may use another allowance. Guides are excluded from library/Android backups, scoped to book title/author and audio identity, and invalidated when those change.

**Engine limitation:** Vosk's small model cannot accept new phoneme pronunciations or out-of-dictionary words via its runtime grammar API. This release adds reviewed local spelling correction after recognition, not acoustic training, open-vocabulary decoding or guaranteed recognition improvement. Pronunciation hints are for the listener. Title/author knowledge is requested only to disambiguate terms already in heard text; no plot details or unseen terms are requested. AI can guess wrong or imply unwanted information, so suggestions cannot be guaranteed spoiler-free. Future decoder adaptation needs a different engine or rebuilt lexicon; do not market this version as learning pronunciations.

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
Open a book's player → **Read along · recap · illustrate**. Import a chapter SRT/VTT or explicitly transcribe up to five minutes already reached in playback. Completed timed lines appear as you listen. Long-press to recap, ask about, or illustrate a passage. Catch me up uses available recent transcript, with a listening cutoff and quoted evidence. One image request creates a medium-quality, four-panel graphic-novel page; three image attempts per UTC day are shared across the private server. Saved pages reopen locally and export as JPEG. Transcripts export as SRT; these assets are not included in library backups. Recaps, questions and illustrations require the [private backend](ai-backend/README.md). In 1.5.0, transcription also runs free and offline with the bundled English recognizer.

## Catch up and chapter memories (1.8.0)

The home-screen Catch up shortcut opens or creates the last completed chapter’s recap. After one informed opt-in, new chapter summaries start in one tap; disable this in Catch up at any time. Saved recaps always open locally. The player and notifications use a cream, rounded recap sheet with serif headings and the selected accent.

The Catch up bottom tab has book/chapter dropdowns, an optional batch picker (up to 25), saved recaps and graphic scenes linked to exact chapter source identities. Older unambiguous single-file scenes are recognised; other older pages remain in Read along. New scene generation uses the existing passage selection and explicit image-charge confirmation.

The private server has a separate shared allowance of **25 chapter attempts per UTC day**, plus the existing 20 other text attempts and three medium-quality image attempts. Failed attempts count; cached reopening and the authenticated `/chapter-usage` endpoint are free. Usage is fetched from the server, with an explicitly labelled cached value when offline. This is a private-server allowance, not a paid-user entitlement system.

Chapter prompts now cover grounded emotional movement, stakes, themes and plot turns. At most four earlier saved recaps (4,500 characters total) provide story context; never later chapters or external book knowledge. Overview plus points remain within 100 words, with exact quotes from the current chapter. Context is checkpointed before the one generation attempt so interrupted work only looks up that same result. Existing v1 interrupted jobs retain their original lookup identity.

The newest completed chapter posts one replaceable notification with a read action and a private lock-screen version. Notifications require Android permission. Rewinding before its chapter removes it. Processing still needs user initiation; no boot job, scheduled generation or automatic paid retry is added. Local data remains excluded from library backups.
