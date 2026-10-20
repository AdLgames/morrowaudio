# Narrumo Android downloads

The current signed release is **1.6.0 / build 9**:

- [ARM64 APK](https://morrow-audio.aptnote8.chatgpt.site/download.html?arch=arm64&version=1.6.0) — 55,434,237 bytes
- [ARM32 APK](https://morrow-audio.aptnote8.chatgpt.site/download.html?arch=arm32&version=1.6.0) — 54,541,315 bytes

SHA-256 checksum files are retained alongside this document. Both APKs use the
existing release certificate and install over previous Morrow/Narrumo versions.
The larger APKs include the offline English speech model. The download page prepares and SHA-256 verifies one APK in your browser before saving; no separate model download is needed. Source builds package
the verified model automatically; `scripts/sign-release.sh` signs an ARM64 build
with the private key you supply. Never commit signing credentials.

The older generic APKs retained in this directory are historical 1.4.1 downloads
(and Morrow 1.4.0). Use the website above for the current version.
