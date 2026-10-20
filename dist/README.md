# Narrumo Android downloads

The current signed release is **1.9.0 / build 13**:

- [ARM64 APK](https://morrow-audio.aptnote8.chatgpt.site/download.html?arch=arm64&version=1.9.0) — 55,483,389 bytes
- [ARM32 APK](https://morrow-audio.aptnote8.chatgpt.site/download.html?arch=arm32&version=1.9.0) — 54,590,467 bytes

The home-screen Catch up shortcut opens or creates the last completed chapter’s recap. The AI tab groups recaps, chapter transcripts, illustrations, book words, passage questions and connection settings. A visible composer beneath the transcript suggests a free passage, offers optional AI selection and reviews 1–3 pictures before generation. Saved pictures link to chapters even without recaps; requests stop after a failure and never retry automatically. The shared three-image limit remains separate from the chapter allowance. New literary recaps use bounded earlier context; the private test shares 25 chapter attempts per UTC day. Reading saved recaps makes no AI request.

SHA-256 checksum files are retained alongside this document. Both APKs use the
existing release certificate and install over previous Morrow/Narrumo versions.
The larger APKs include the offline English speech model. The download page prepares and SHA-256 verifies one APK in your browser before saving; no separate model download is needed. Source builds package
the verified model automatically; `scripts/sign-release.sh` signs an ARM64 build
with the private key you supply. Never commit signing credentials.

The older generic APKs retained in this directory are historical 1.4.1 downloads
(and Morrow 1.4.0). Use the website above for the current version.
