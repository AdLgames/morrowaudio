# Narrumo Android downloads

The current signed release is **1.5.0 / build 8**:

- [ARM64 APK](https://morrow-audio.aptnote8.chatgpt.site/downloads/Narrumo-1.5.0.apk) — 55,430,141 bytes
- [ARM32 APK](https://morrow-audio.aptnote8.chatgpt.site/downloads/Narrumo-arm32-1.5.0.apk) — 54,533,123 bytes

SHA-256 checksum files are retained alongside this document. Both APKs use the
existing release certificate and install over previous Morrow/Narrumo versions.
The larger APKs include the offline English speech model. Source builds package
the verified model automatically; `scripts/sign-release.sh` signs an ARM64 build
with the private key you supply. Never commit signing credentials.

The older generic APKs retained in this directory are historical 1.4.1 downloads
(and Morrow 1.4.0). Use the website above for the current version.
