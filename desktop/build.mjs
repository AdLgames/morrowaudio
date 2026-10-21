import {build} from 'esbuild';
import {cp,mkdir} from 'node:fs/promises';
await mkdir('dist/listen',{recursive:true});await cp('public','dist/listen',{recursive:true});
await build({entryPoints:['src/app.mjs'],outfile:'dist/listen/app.js',bundle:true,format:'esm',platform:'browser',target:'es2022',minify:true});
await build({entryPoints:['src/speech-worker.mjs'],outfile:'dist/listen/speech-worker.js',external:['./vendor/whisper.mjs'],bundle:true,format:'esm',platform:'browser',target:'es2022',minify:true});
