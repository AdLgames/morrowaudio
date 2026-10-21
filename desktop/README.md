# Narrumo desktop

Local-first installable browser audiobook player, published under the existing distribution origin at `/listen/`. Folder handles and library data are stored in IndexedDB; a final progress checkpoint uses localStorage. Fallback file import copies audio into browser storage. No account, audio upload, sync or desktop torrent engine is included.

Playback supports chapter files and embedded MP4/M4B chapters, natural ordering, embedded covers, bookmarks, speed, media keys, sleep and offline return. File fingerprints protect transcript identity. Only already-heard text is shown and sent for confirmed cloud actions. The API keeps existing bearer authentication and shared limits, with CORS restricted to the distribution origin.

Whisper base.en Q5_1 runs locally in a dedicated single-thread WASM worker; shared-memory headers are unnecessary. The 59,721,011-byte public model is cached after an explicit download and SHA-256 verified (`4baf70dd0d7c4247ba2b81fafd9c01005ac77c2f9ef064e00dcf195d0e2fdd2f`). Native prompts use known spellings, at most 100 words / 800 characters. Punctuation and names can still be wrong. No accuracy percentage was measured on Dune audio. Each local section is checkpointed; closing the app stops processing. Rebuild preserves a previous transcript, with a Restore action.

## Build

Run `npm ci --ignore-scripts`, `node --test src/core.test.mjs`, then `node build.mjs`. This emits the JS/UI into `dist/listen`. The speech runtime is built separately: install Emscripten **6.0.13**, activate it, and set `NARRUMO_EMSDK` to that SDK and `NARRUMO_WHISPER_SOURCE` to checksum-verified **whisper.cpp 1.9.5** source (archive SHA-256 `ff1a9053feb509ff9d7729703355541ae9690073a6b1c40eb692c962e0dc1720`). Run `bash src/build-native.sh`. Its only upstream patch removes the unconditional pthread flags to select ggml's supported single-thread path. The runtime output is `dist/listen/vendor/whisper.mjs` and `narrumo_browser.wasm`. PNG install icons rendered from `public/icon.svg` are included at 192/512 pixels.

Serve `dist` over HTTPS, or localhost for development. Keep the deployed origin stable: changing it separates browser storage and permissions. The full distribution website is managed separately by Sites; its release source contains the compiled runtime and install icons. Clearing browser data can remove everything; progress backups deliberately exclude audio, permissions, credentials, transcripts, pictures and models. Chrome/Edge offer the best folder access. Real directory/file handles, library restoration, offline loading and a real prompted speech clip were tested in Chromium.

MIT licenses for whisper.cpp/ggml and OpenAI Whisper are provided in the published app notices. JS dependencies retain their npm licenses. The existing server receives text only after confirmation. No provider keys or shared trial tokens are distributed in this source or public app.
