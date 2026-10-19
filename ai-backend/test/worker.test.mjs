import test from 'node:test';
import assert from 'node:assert/strict';
import worker,{handleRequest,TrialBudget} from '../worker.mjs';
const token='private-morrow-trial-token-1234567890';
const passage='Mara carried a brass key given to her by her grandmother. She waited until morning.';
function request(body={action:'ask',passage,question:'Who gave Mara the key?'},auth=token){return new Request('https://test/ai',{method:'POST',headers:{'Content-Type':'application/json','Authorization':'Bearer '+auth},body:JSON.stringify(body)});}
function config(overrides={}){return {OPENAI_API_KEY:'provider-secret-never-returned',OPENAI_MODEL:'gpt-4.1-mini',MORROW_TRIAL_TOKEN:token,TRIAL_BUDGET:{idFromName:()=>1,get:()=>({fetch:async()=>new Response('{}',{status:200})})},...overrides};}
function provider(answer={answer:'Her grandmother gave her the key.',quotes:['given to her by her grandmother']}){return new Response(JSON.stringify({status:'completed',output:[{type:'reasoning',summary:[]},{type:'message',content:[{type:'output_text',text:JSON.stringify(answer)}]}]}));}
test('setup is closed until all secrets and durable usage controls exist',async()=>{let called=false;const response=await handleRequest(request(),{},async()=>{called=true});assert.equal(response.status,503);assert.equal(called,false);});
test('unauthorised requests never reserve budget or contact provider',async()=>{let reserved=false;let called=false;const env=config({TRIAL_BUDGET:{idFromName:()=>{reserved=true}}});const response=await handleRequest(request(undefined,'wrong-token'),env,async()=>{called=true});assert.equal(response.status,401);assert.equal(reserved,false);assert.equal(called,false);});
test('passes only validated passage and question, disables storage and bounds output',async()=>{let payload;const response=await handleRequest(request(),config(),async(url,options)=>{assert.equal(url,'https://api.openai.com/v1/responses');payload=JSON.parse(options.body);return provider();});assert.equal(response.status,200);assert.equal(payload.store,false);assert.equal(payload.max_output_tokens,1000);assert.deepEqual(JSON.parse(payload.input),{action:'ask',passage,question:'Who gave Mara the key?'});assert.equal(payload.text.format.type,'json_schema');assert.deepEqual((await response.json()).quotes,['given to her by her grandmother']);});
test('recaps do not send the unused question to the provider',async()=>{await handleRequest(request({action:'recap',passage,question:'unused private text'}),config(),async(url,options)=>{assert.equal(JSON.parse(JSON.parse(options.body).input).question,'');return provider();});});
test('rejects fabricated quotes instead of displaying an unsupported answer',async()=>{const response=await handleRequest(request(),config(),async()=>provider({answer:'Her father gave it to her.',quotes:['Her father gave it to her.']}));assert.equal(response.status,502);});
test('no evidence produces a clear unknown answer',async()=>{const response=await handleRequest(request(),config(),async()=>provider({answer:'Invented confidence',quotes:[]}));assert.equal((await response.json()).answer,'I couldn’t find that in this passage.');});
test('empty or oversized passages never reach the provider',async()=>{for(const text of ['', 'x'.repeat(12001)]){let called=false;const response=await handleRequest(request({action:'recap',passage:text,question:''}),config(),async()=>{called=true});assert.equal(response.status,400);assert.equal(called,false);}});
test('rejects a large actual body even without a content-length header',async()=>{const response=await handleRequest(request({action:'recap',passage:'x'.repeat(70000),question:''}),config());assert.equal(response.status,413);});
test('provider failures and incomplete output never expose provider payloads or secrets',async()=>{for(const response of [new Response('provider-secret-never-returned',{status:401}),new Response(JSON.stringify({status:'incomplete',output:[]}))]){const result=await handleRequest(request(),config(),async()=>response);assert.equal(result.status,502);assert.ok(!(await result.text()).includes('provider-secret-never-returned'));}});
test('quota exhaustion and quota outages fail closed',async()=>{let called=false;for(const status of [429,503]){const env=config({TRIAL_BUDGET:{idFromName:()=>1,get:()=>({fetch:async()=>new Response('{}',{status})})}});assert.equal((await handleRequest(request(),env,async()=>{called=true})).status,429);}assert.equal(called,false);});
test('Durable budget atomically limits concurrent requests across object instances and resets next UTC day',async()=>{
 let saved={day:'2000-01-01',count:20};let tail=Promise.resolve();
 const storage={transaction(fn){const current=tail.then(()=>fn({get:async()=>saved,put:async(key,value)=>{saved=value}}));tail=current.then(()=>{},()=>{});return current;}};
 const a=new TrialBudget({storage},{AI_DAILY_LIMIT:'3'}),b=new TrialBudget({storage},{AI_DAILY_LIMIT:'3'});
 const responses=await Promise.all(Array.from({length:10},(_,n)=>(n%2?a:b).fetch(new Request('https://budget/reserve',{method:'POST'}))));
 assert.equal(responses.filter(r=>r.status===200).length,3);assert.equal(responses.filter(r=>r.status===429).length,7);assert.equal(saved.count,3);
});
test('Worker entry point handles Cloudflare execution context without treating it as fetch',async()=>{
 const original=globalThis.fetch;globalThis.fetch=async()=>provider();try{const response=await worker.fetch(request(),config(),{waitUntil(){}});assert.equal(response.status,200);}finally{globalThis.fetch=original;}
});
