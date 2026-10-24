const term=/^[\p{L}\p{M}]+(?:[ '\u2019-][\p{L}\p{M}]+)*$/u;
const words=value=>value.trim().split(/\s+/u).filter(Boolean).length;
function present(source,phrase){
 const escaped=phrase.replace(/[.*+?^${}()|[\]\\]/g,'\\$&').replace(/ /g,'\\s+');
 return new RegExp('(?<![\\p{L}\\p{M}\\p{N}])'+escaped+'(?![\\p{L}\\p{M}\\p{N}])','iu').test(source);
}
export function reviewedSpellings(value,source){
 if(value===undefined)return [];
 if(!Array.isArray(value)||value.length>8)throw new Error();
 let count=0,length=0;const seen=new Set();
 for(const entry of value){
  if(!entry||Object.keys(entry).sort().join(',')!=='heard,spelling')throw new Error();
  for(const key of ['heard','spelling']){
   const text=entry[key];
   if(typeof text!=='string'||text!==text.trim()||!term.test(text)||text.length>60||words(text)>(key==='heard'?5:4))throw new Error();
   count+=words(text);length+=text.length;
  }
  const key=entry.heard.toLowerCase();if(seen.has(key))throw new Error();seen.add(key);
 }
 if(count>100||length>800)throw new Error();
 // A book-level approved list can contain names not heard in this chapter.
 // Only send names with a literal source match; never introduce absent names.
 return value.filter(entry=>present(source,entry.heard)).map(({heard,spelling})=>({heard,spelling}));
}

export const spellingInstructions='REVIEWED SPELLINGS: Optional reviewedSpellings map heard phrases to user-approved name spellings. Use them only to disambiguate those same names in your prose and visual direction, never to add a character, event or relationship. Keep every evidence quote exactly as it appears in the raw transcript; never rewrite a quote with a reviewed spelling.';
