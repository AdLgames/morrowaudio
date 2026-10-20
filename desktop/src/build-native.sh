#!/usr/bin/env bash
set -euo pipefail
: "${NARRUMO_EMSDK:?Point NARRUMO_EMSDK at an activated Emscripten 6.0.13 installation}"
: "${NARRUMO_WHISPER_SOURCE:?Point NARRUMO_WHISPER_SOURCE at verified whisper.cpp 1.9.5 source}"
narrumo_web_root=$(cd "$(dirname "$0")/.." && pwd)
narrumo_wasm_source="$narrumo_web_root/native-build/whisper-source"
mkdir -p "$narrumo_wasm_source"
# Only source directories are required; do not copy host build caches.
python3 - "$NARRUMO_WHISPER_SOURCE" "$narrumo_wasm_source" <<'PY_COPY'
import sys,shutil
from pathlib import Path
source,target=map(Path,sys.argv[1:]);
for p in source.iterdir():
 if p.name in ['host-build','build','.git']:continue
 if p.is_dir():shutil.copytree(p,target/p.name,dirs_exist_ok=True)
 else:shutil.copy2(p,target/p.name)
PY_COPY
# Upstream enables pthreads unconditionally for Emscripten. The web target uses
# ggml's supported single-thread path, so SharedArrayBuffer/COOP/COEP are not needed.
python3 - "$narrumo_wasm_source/CMakeLists.txt" <<'PY'
import sys
from pathlib import Path
p=Path(sys.argv[1]);p.write_text(p.read_text().replace(' -pthread',''))
PY
"$NARRUMO_EMSDK/upstream/emscripten/emcmake" cmake -S "$narrumo_web_root/src/native" -B "$narrumo_web_root/native-build/whisper-build" -G Ninja -DCMAKE_BUILD_TYPE=Release -DCMAKE_POLICY_VERSION_MINIMUM=3.5 -DWHISPER_SOURCE="$narrumo_wasm_source"
cmake --build "$narrumo_web_root/native-build/whisper-build" --target narrumo_browser -j 3
mkdir -p "$narrumo_web_root/dist/listen/vendor"
cp "$narrumo_web_root/native-build/whisper-build/narrumo_browser.mjs" "$narrumo_web_root/dist/listen/vendor/whisper.mjs"
cp "$narrumo_web_root/native-build/whisper-build/narrumo_browser.wasm" "$narrumo_web_root/dist/listen/vendor/narrumo_browser.wasm"
