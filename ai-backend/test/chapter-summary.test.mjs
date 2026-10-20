import test from 'node:test';
import assert from 'node:assert/strict';
import {handleRequest,TrialBudget} from '../worker.mjs';
import {chapterInput,validateSummary} from '../chapter-summary.mjs';
const token='private-chapter-test-token-1234567890';
function config(){
 const data=new Map();let tail=Promise.resolve();
 const tx={get:async k=>structuredClone(data.get(k)),put:async(k,v)=>data.set(k,structuredClone(v)),delete:async k=>data.delete(k)};
 const object=new TrialBudget({storage:{transaction(fn){const next=tail.then(()=>fn(tx));tail=next.catch(()=>{});return next;}}},{AI_DAILY_LIMIT:20});
 return {data,object,OPENAI_API_KEY:'secret',OPENAI_MODEL:'test-model',MORROW_TRIAL_TOKEN:token,TRIAL_BUDGET:{idFromName:()=>1,get:()=>({fetch:(url,opts)=>object.fetch(new Request(url,opts))})}};
}
const body=()=>({chapterKey:'a'.repeat(64),chapterIndex:0,durationMs:600000,cutoff:{chapter:1,positionMs:0},allowUnheard:false,cues:[{startMs:0,endMs:5000,text:'Mara finds a key.'},{startMs:500000,endMs:600000,text:'She opens the north gate.'}]});
const result={overview:'Mara finds a key and opens the gate.',points:['The key is discovered.','The north gate opens.'],quotes:['Mara finds a key.','She opens the north gate.']};
const request=(b,auth=token)=>new Request('https://test/chapter-summary',{method:'POST',headers:{Authorization:'Bearer '+auth,'Content-Type':'application/json'},body:JSON.stringify(b)});
const reply=(r=result,status='completed')=>new Response(JSON.stringify({status,output:[{type:'message',content:[{type:'output_text',text:JSON.stringify(r)}]}]}));
test('one chapter call includes the entire transcript, uses a bounded response and no book metadata',async()=>{
 const b=body();b.title='Not sent';b.cues.splice(1,0,...Array.from({length:5},(_,n)=>({startMs:10000+n*10000,endMs:20000+n*10000,text:'A'.repeat(4000)})));
 let calls=0,payload;const r=await handleRequest(request(b),config(),async(url,opts)=>{calls++;payload=JSON.parse(opts.body);return reply();});
 assert.equal(r.status,200);assert.equal(calls,1);assert.equal(payload.max_output_tokens,1000);assert.equal(payload.store,false);assert.equal(payload.model,'test-model');
 const input=JSON.parse(payload.input);assert.ok(input.transcript.includes(Array(5).fill('A'.repeat(4000)).join('\n')));assert.ok(input.transcript.endsWith('She opens the north gate.'));assert.ok(!payload.input.includes('Not sent'));
});
test('unheard chapters, invalid timing, oversized input and missing auth fail before any charge',async()=>{
 let calls=0;const invoke=async()=>{calls++;return reply();};
 const future=body();future.chapterIndex=2;
 const partial=body();partial.cutoff={chapter:0,positionMs:599999};
 const badTime=body();badTime.cues[1].endMs=600001;
 const large=body();large.cues=Array.from({length:26},(_,n)=>({startMs:n*1000,endMs:n*1000+900,text:'x'.repeat(4000)}));
 for(const b of [future,partial,badTime,large])assert.equal((await handleRequest(request(b),config(),invoke)).status,400);
 assert.equal((await handleRequest(request(body(),'wrong'),config(),invoke)).status,401);assert.equal(calls,0);
});
test('concurrent duplicate chapter requests reserve once and saved summaries reopen without a provider call',async()=>{
 const env=config();let calls=0,release;const gate=new Promise(r=>release=r);
 const invoke=async()=>{calls++;await gate;return reply();};const first=handleRequest(request(body()),env,invoke);
 while(!calls)await new Promise(r=>setTimeout(r,1));
 assert.equal((await handleRequest(request(body()),env,invoke)).status,409);release();assert.equal((await first).status,200);
 const cached=await handleRequest(request(body()),env,invoke);assert.equal(cached.status,200);assert.equal((await cached.json()).cached,true);assert.equal(calls,1);assert.equal(env.data.get('usage').count,1);
});
test('lookup after interrupted work never reserves, regenerates or charges, even if cache was lost',async()=>{
 const env=config();let calls=0;const invoke=async()=>{calls++;return reply();};
 assert.equal((await handleRequest(request({...body(),lookupOnly:true}),env,invoke)).status,404);assert.equal(env.data.get('usage'),undefined);
 assert.equal((await handleRequest(request(body()),env,invoke)).status,200);
 assert.equal((await handleRequest(request({...body(),lookupOnly:true}),env,invoke)).status,200);assert.equal(calls,1);
});
test('failed and invalid evidence attempts count once and share the existing text quota',async()=>{
 const env=config();let calls=0;
 const invoke=async()=>{calls++;return reply({...result,quotes:['Invented future event']});};
 assert.equal((await handleRequest(request(body()),env,invoke)).status,502);
 assert.equal((await handleRequest(request(body()),env,invoke)).status,429);
 assert.equal((await handleRequest(request({...body(),lookupOnly:true}),env,invoke)).status,502);
 assert.equal(calls,1);assert.equal(env.data.get('usage').count,1);
 for(let n=0;n<19;n++)assert.equal((await env.object.fetch(new Request('https://budget/reserve',{method:'POST'}))).status,200);
 const next=body();next.chapterKey='b'.repeat(64);assert.equal((await handleRequest(request(next),env,invoke)).status,429);assert.equal(calls,1);
});
test('summary word count, key points, exact evidence and provider completion are checked',async()=>{
 const source=chapterInput(body()).passage;
 for(const r of [{...result,overview:'word '.repeat(101)},{...result,points:[]},{...result,quotes:[]},{...result,quotes:['Something else']}])assert.throws(()=>validateSummary(r,source));
 assert.equal((await handleRequest(request(body()),config(),async()=>reply(result,'incomplete'))).status,502);
});
