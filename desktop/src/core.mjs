export const audioFile = name => /\.(mp3|m4a|m4b|aac|flac|wav|ogg|opus|webm)$/i.test(name);
export const natural = (a,b) => a.localeCompare(b,undefined,{numeric:true,sensitivity:'base'});
export const escape = value => String(value??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
export const clock = time => {let n=Math.max(0,Math.floor(Number(time)||0));return n>=3600?`${Math.floor(n/3600)}:${String(Math.floor(n/60)%60).padStart(2,'0')}:${String(n%60).padStart(2,'0')}`:`${Math.floor(n/60)}:${String(n%60).padStart(2,'0')}`;};
export const words = value => String(value||'').trim().split(/\s+/).filter(Boolean);
export function checkedHints(value){
 if(typeof value!=='string')throw Error('Enter book spellings as text.');
 const seen=new Set(),entries=[];
 for(const entry of value.normalize('NFC').split(/[,;\r\n]+/)){const name=entry.trim().replace(/\s+/gu,' ');if(!name)continue;if(name.length>60||words(name).length>4||!/^\p{L}[\p{L}\p{M}]*(?:[ '’-][\p{L}\p{M}]+)*$/u.test(name))throw Error('Use names or short terms, separated by commas. Keep each within four words and 60 characters.');const key=name.toLocaleLowerCase('en');if(!seen.has(key)){seen.add(key);entries.push(name);}}
 const hints=entries.join(', ');
 if(words(hints).length>100||hints.length>800)throw Error('Keep book spellings within 100 words and 800 characters.');
 return hints;
}
// Preserve a useful restore point when rebuilding an empty or partly processed transcript.
export function shouldArchiveTranscript(current,previous){
 if(!Array.isArray(current?.cues)||!current.cues.length)return false;
 if(!Array.isArray(previous?.cues)||!previous.cues.length)return true;
 return Number(current.processed||0)>=Number(previous.processed||0);
}
const sameCue=(a,b)=>a&&b&&a.chapter===b.chapter&&a.startMs===b.startMs&&a.endMs===b.endMs&&a.text===b.text;
export function validatePlan(plan,source,requestedCount){
 if(!Number.isInteger(requestedCount)||requestedCount<1||requestedCount>3||typeof plan?.guide!=='string'||!plan.guide.trim()||plan.guide.length>5000||!Array.isArray(plan.scenes)||plan.scenes.length<1||plan.scenes.length>requestedCount||(plan.requestedCount!==undefined&&plan.requestedCount!==requestedCount))throw Error('The storyboard could not be checked. Your transcript is kept; no pictures were requested.');
 let last=-1;
 for(const [n,s] of plan.scenes.entries()){
  const start=source.findIndex(c=>sameCue(c,s.cues?.[0]));
  if(start<=last||!Array.isArray(s.cues)||!s.cues.length||s.cues.length>1000||s.cues.some((c,i)=>!sameCue(c,source[start+i]))||s.cues.map(c=>c.text).join('\n').length>2500||s.number!==n+1||s.total!==plan.scenes.length||typeof s.title!=='string'||!s.title.trim()||s.title.length>120||typeof s.direction!=='string'||!s.direction.trim()||s.direction.length>800)throw Error('The storyboard did not match separate source passages. No pictures were requested.');
  last=start+s.cues.length-1;
 }
 return plan;
}
export async function apiFailure(response){
 let detail={};
 try{
  const reader=response.body?.getReader();if(reader){const parts=[];let size=0;try{for(;;){const {done,value}=await reader.read();if(done)break;size+=value.length;if(size>8192)throw Error();parts.push(value);}}finally{await reader.cancel().catch(()=>{});}const bytes=new Uint8Array(size);let at=0;for(const part of parts){bytes.set(part,at);at+=part.length;}detail=JSON.parse(new TextDecoder().decode(bytes));}
 }catch{}
 const known={
  insufficient_scenes:'This text supports fewer distinct moments. Choose fewer pictures or a longer passage. One text attempt was used; no images were requested.',
  scene_text_too_long:'The transcript lines are too long to illustrate safely. Select a shorter passage. No AI allowance was used.',
  insufficient_scene_ranges:'This text has too few separate passages. Choose fewer pictures or select more text. No AI allowance was used.'
 };
 if(response.status===422&&Object.hasOwn(known,detail?.code))return known[detail.code];
 return ({401:'The private server did not accept the access token. Check the connection in Settings.',400:'The server could not use this selection. Check the heard text and choose a shorter passage. No AI allowance was used.',404:'Your private server needs the latest Narrumo AI update.',409:'This request is still being prepared. Wait a moment before checking again.',413:'This selection is too large. Select a shorter passage. No AI allowance was used.',422:'This text did not support a usable storyboard. Try fewer pictures or another passage. No images were requested.',429:'The shared daily allowance is used up, or this chapter was already attempted today. Resets at midnight UTC. No automatic retry was made.',503:'The private server needs its AI connection checked. Your transcript is saved.'})[response.status]||'The private AI request could not finish. Your transcript is saved. Failed attempts may count; no automatic retry was made.';
}
export function position(book,saved){const index=Math.max(0,Math.min(book.chapters.length-1,Math.floor(saved?.index)||0));const duration=book.chapters[index]?.duration||0;return {index,time:Math.max(0,Math.min(duration||86400,Number(saved?.time)||0)),updated:Number(saved?.updated)||0,speed:Math.max(.5,Math.min(3,Number(saved?.speed)||1))};}
export function heard(book,progress,index,end){return index<progress.index||(index===progress.index&&end<=progress.time+.05);}
export function validateCues(cues,duration){let prev=0;return cues.map(c=>{if(!Number.isFinite(c.start)||!Number.isFinite(c.end)||c.start<prev||c.end<=c.start||c.end>duration+.1||typeof c.text!=='string'||!c.text.trim()||c.text.length>4000)throw Error('The recogniser returned invalid transcript timings.');prev=c.end;return {start:c.start,end:c.end,text:c.text.trim()};});}
