import createWhisper from './vendor/whisper.mjs';
import {checkedHints} from './core.mjs';
const URL='https://huggingface.co/ggerganov/whisper.cpp/resolve/5359861c739e955e79d9a303bcbc70fb988958b1/ggml-base.en-q5_1.bin';
const SHA='4baf70dd0d7c4247ba2b81fafd9c01005ac77c2f9ef064e00dcf195d0e2fdd2f',BYTES=59721011;
let module,ready;
async function prepare(){
 module??=await createWhisper({locateFile:name=>new globalThis.URL('./vendor/'+name,self.location).href,print:()=>{},printErr:()=>{}});
 const cache=await caches.open('narrumo-speech-model-v1');let response=await cache.match(URL),fresh=false;
 if(!response){response=await fetch(URL,{credentials:'omit'});if(!response.ok)throw Error('The public model could not be downloaded.');fresh=true;}
 const reader=response.body.getReader(),parts=[];let size=0;
 try{for(;;){const {done,value}=await reader.read();if(done)break;size+=value.length;if(size>BYTES)throw Error('Model is larger than expected.');parts.push(value);self.postMessage({type:'download',file:'Whisper base.en',progress:100*size/BYTES});}}finally{await reader.cancel().catch(()=>{});}
 const bytes=new Uint8Array(size);let at=0;for(const p of parts){bytes.set(p,at);at+=p.length;}
 const hash=Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256',bytes)),n=>n.toString(16).padStart(2,'0')).join('');
 if(size!==BYTES||hash!==SHA){await cache.delete(URL);throw Error('Model checksum did not match. Nothing was loaded.');}
 if(fresh)await cache.put(URL,new Response(bytes,{headers:{'Content-Type':'application/octet-stream'}}));
 module.FS.writeFile('/model.bin',bytes);
 const result=module.ccall('load_model','number',['string'],['/model.bin']);module.FS.unlink('/model.bin');
 if(result!==0)throw Error('Not enough memory to load accurate speech. Close other heavy tabs and try again.');
}
self.onmessage=async({data})=>{
 try{
  ready??=prepare().catch(error=>{ready=undefined;throw error;});await ready;
  if(data.type==='prepare'){self.postMessage({type:'ready'});return;}
  const pcm=new Float32Array(data.audio),chunks=[];
  module.onSegment=(start,end,text)=>chunks.push({timestamp:[start,end],text:text.trim()});
  module.onProgress=progress=>self.postMessage({type:'download',file:'Transcribing locally',progress});
  const pointer=module._malloc(pcm.byteLength);if(!pointer)throw Error('Not enough memory for this section.');
  try{module.HEAPF32.set(pcm,pointer/4);const result=module.ccall('decode_audio','number',['number','number','string'],[pointer,pcm.length,checkedHints(data.hints||'')]);if(result!==0)throw Error('Recognition did not finish.');}finally{module._free(pointer);}
  self.postMessage({type:'result',result:{text:chunks.map(c=>c.text).join(' '),chunks}});
 }catch(error){self.postMessage({type:'error',message:'Local speech could not finish: '+(error?.message||String(error))});}
};
