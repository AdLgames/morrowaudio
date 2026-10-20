import test from 'node:test';
import assert from 'node:assert/strict';
import {handleRequest,TrialBudget} from '../worker.mjs';
import {validateGuide,containsTerm} from '../vocabulary.mjs';
const token='private-narrumo-trial-token-1234567890';
function config(){
 const data=new Map();let tail=Promise.resolve();
 const tx={get:async k=>structuredClone(data.get(k)),put:async(k,v)=>data.set(k,structuredClone(v)),delete:async k=>data.delete(k)};
 const storage={transaction(fn){const next=tail.then(()=>fn(tx));tail=next.catch(()=>{});return next;}};
 const object=new TrialBudget({storage},{AI_DAILY_LIMIT:20});
 return {data,object,OPENAI_API_KEY:'secret',OPENAI_MODEL:'test-model',MORROW_TRIAL_TOKEN:token,TRIAL_BUDGET:{idFromName:()=>1,get:()=>({fetch:(url,opts)=>object.fetch(new Request(url,opts))})}};
}
const cue=(chapter,startMs,endMs,text)=>({chapter,startMs,endMs,text});
const input=()=>({title:'Dune',author:'Frank Herbert',cutoff:{chapter:0,positionMs:5000},cues:[cue(0,0,4000,'The quiz at had arrived.'),cue(0,4000,6000,'Future secret.'),cue(1,0,3000,'Later character.')]});
const entry={heard:'quiz at',spelling:'Kwisatz',pronunciation:'KWEE-sahts'};
const request=(b,auth=token)=>new Request('https://test/vocabulary',{method:'POST',headers:{Authorization:'Bearer '+auth,'Content-Type':'application/json'},body:JSON.stringify(b)});
const response=(entries=[entry],status='completed')=>new Response(JSON.stringify({status,output:[{type:'message',content:[{type:'output_text',text:JSON.stringify({entries})}]}]}));
test('guide is authenticated and bounds both heard input and output; no audio or future text sent',async()=>{
 let payload,calls=0;const invoke=async(url,opts)=>{calls++;assert.equal(url,'https://api.openai.com/v1/responses');payload=JSON.parse(opts.body);return response();};
 assert.equal((await handleRequest(request(input(),'wrong'),config(),invoke)).status,401);
 assert.equal(calls,0);
 const r=await handleRequest(request(input()),config(),invoke);assert.equal(r.status,200);
 assert.equal(payload.model,'test-model');assert.equal(payload.store,false);assert.equal(payload.max_output_tokens,800);
 assert.deepEqual(JSON.parse(payload.input),{title:'Dune',author:'Frank Herbert',passage:'The quiz at had arrived.'});
 assert.ok(payload.instructions.includes('100 whitespace-separated words TOTAL'));
 assert.deepEqual((await r.json()).entries,[entry]);
});
test('one shared text reservation for concurrent duplicate guides; cached reopening has no provider call',async()=>{
 const env=config();let calls=0,release;const gate=new Promise(r=>release=r);
 const invoke=async()=>{calls++;await gate;return response();};
 const first=handleRequest(request(input()),env,invoke);
 while(!calls)await new Promise(r=>setTimeout(r,1));
 assert.equal((await handleRequest(request(input()),env,invoke)).status,409);release();
 assert.equal((await first).status,200);
 const cached=await handleRequest(request(input()),env,invoke);assert.equal(cached.status,200);assert.equal((await cached.json()).cached,true);
 assert.equal(calls,1);assert.equal(env.data.get('usage').count,1);
});
test('guide and ordinary text requests share the daily cap, provider failures count with no automatic retry',async()=>{
 const env=config();let calls=0;
 for(let n=0;n<19;n++)assert.equal((await env.object.fetch(new Request('https://budget/reserve',{method:'POST'}))).status,200);
 const invoke=async()=>{calls++;return new Response('private failure',{status:500});};
 assert.equal((await handleRequest(request(input()),env,invoke)).status,502);
 assert.equal((await handleRequest(request(input()),env,invoke)).status,429);
 assert.equal((await env.object.fetch(new Request('https://budget/reserve',{method:'POST'}))).status,429);
 const another=input();another.author='Different';assert.equal((await handleRequest(request(another),env,invoke)).status,429);assert.equal(calls,1);
});
test('large requests, missing metadata, future-only cues and budget outages fail before the provider',async()=>{
 let calls=0;const invoke=async()=>{calls++;return response();};
 const large=input();large.cues=[cue(0,0,3000,'x'.repeat(3001))];
 const future=input();future.cues=[cue(0,5000,6000,'Not heard')];
 for(const b of [large,future,{...input(),title:''},{...input(),author:'x'.repeat(121)}])assert.equal((await handleRequest(request(b),config(),invoke)).status,400);
 const env=config();env.TRIAL_BUDGET.get=()=>({fetch:async()=>{throw new Error();}});
 assert.equal((await handleRequest(request(input()),env,invoke)).status,503);assert.equal(calls,0);
});
test('unheard terms, substring matches, duplicates, long or incomplete model replies cannot be used',async()=>{
 assert.equal(containsTerm('quizzical','quiz'),false);
 assert.equal(containsTerm('quizé 2quiz quiz2','quiz'),false);
 for(const entries of [[{...entry,heard:'unheard'}],[entry,{...entry,heard:'QUIZ AT'}],[{...entry,pronunciation:'x'.repeat(61)}],Array(9).fill(entry)]){
  assert.throws(()=>validateGuide({entries},'quiz at'));
  assert.equal((await handleRequest(request(input()),config(),async()=>response(entries))).status,502);
 }
 assert.equal((await handleRequest(request(input()),config(),async()=>response([entry],'incomplete'))).status,502);
 const terms=['alpha','beta','gamma','delta','epsilon','zeta','eta','theta'];
 assert.throws(()=>validateGuide({entries:terms.map(heard=>({heard,spelling:'one two three four',pronunciation:'a a a a a a a a a a a'}))},terms.join(' ')));
});
test('an empty result is a valid cached guide and metadata differences do not share a guide',async()=>{
 const env=config();let calls=0;const invoke=async()=>{calls++;return response([]);};
 assert.deepEqual((await (await handleRequest(request(input()),env,invoke)).json()).entries,[]);
 await handleRequest(request(input()),env,invoke);
 const other=input();other.title='Another book';await handleRequest(request(other),env,invoke);
 assert.equal(calls,2);
});
