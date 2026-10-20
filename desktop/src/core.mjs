export const audioFile = name => /\.(mp3|m4a|m4b|aac|flac|wav|ogg|opus|webm)$/i.test(name);
export const natural = (a,b) => a.localeCompare(b,undefined,{numeric:true,sensitivity:'base'});
export const escape = value => String(value??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
export const clock = time => {let n=Math.max(0,Math.floor(Number(time)||0));return n>=3600?`${Math.floor(n/3600)}:${String(Math.floor(n/60)%60).padStart(2,'0')}:${String(n%60).padStart(2,'0')}`:`${Math.floor(n/60)}:${String(n%60).padStart(2,'0')}`;};
export const words = value => String(value||'').trim().split(/\s+/).filter(Boolean);
export function checkedHints(value){if(words(value).length>100||value.length>800)throw Error('Keep book spellings within 100 words and 800 characters.');return value.trim();}
export function position(book,saved){const index=Math.max(0,Math.min(book.chapters.length-1,Math.floor(saved?.index)||0));const duration=book.chapters[index]?.duration||0;return {index,time:Math.max(0,Math.min(duration||86400,Number(saved?.time)||0)),updated:Number(saved?.updated)||0,speed:Math.max(.5,Math.min(3,Number(saved?.speed)||1))};}
export function heard(book,progress,index,end){return index<progress.index||(index===progress.index&&end<=progress.time+.05);}
export function validateCues(cues,duration){let prev=0;return cues.map(c=>{if(!Number.isFinite(c.start)||!Number.isFinite(c.end)||c.start<prev||c.end<=c.start||c.end>duration+.1||typeof c.text!=='string'||!c.text.trim()||c.text.length>4000)throw Error('The recogniser returned invalid transcript timings.');prev=c.end;return {start:c.start,end:c.end,text:c.text.trim()};});}
