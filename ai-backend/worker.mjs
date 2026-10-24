import {passageAnswerInstructions} from './prompts.mjs';
import {companion,companionBudget} from './companion.mjs';
import {vocabulary,vocabularyBudget} from './vocabulary.mjs';
import {chapterSummary,chapterBudget,chapterUsage} from './chapter-summary.mjs';
const MAX_BYTES = 65536;
const fallback = 'I couldn’t find that in this passage.';
const json = (body, status = 200) => new Response(JSON.stringify(body), {status, headers: {'Content-Type':'application/json; charset=utf-8','Cache-Control':'no-store','X-Content-Type-Options':'nosniff'}});
const failed = (message, status) => json({error: message}, status);
async function boundedText(stream, limit = MAX_BYTES) {
  if (!stream) return '';
  const reader = stream.getReader(); let length = 0; const chunks = [];
  try { for (;;) { const {value, done} = await reader.read(); if (done) break; length += value.length;
    if (length > limit) throw new RangeError('Body too large'); chunks.push(value);
  } } finally { await reader.cancel().catch(()=>{}); }
  const bytes = new Uint8Array(length); let offset = 0;
  for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.length; }
  return new TextDecoder('utf-8', {fatal: true}).decode(bytes);
}
async function sameToken(actual, expected) {
  if (typeof actual !== 'string' || actual.length > 256) return false;
  const enc = new TextEncoder();
  const [a,b] = await Promise.all([actual, expected].map(value => crypto.subtle.digest('SHA-256',enc.encode(value))));
  const x = new Uint8Array(a), y = new Uint8Array(b); let difference = 0;
  for (let n=0;n<x.length;n++) difference |= x[n]^y[n];
  return difference === 0;
}
function validate(body) {
  if (!body || !['recap','ask'].includes(body.action) || typeof body.passage !== 'string'
      || !body.passage.trim() || body.passage.length > 12000 || typeof body.question !== 'string'
      || body.question.length > 1000 || (body.action === 'ask' && !body.question.trim())) throw new Error('Invalid input');
  return {action:body.action, passage:body.passage, question:body.action==='ask'?body.question:''};
}
export async function handleRequest(request, env, fetchImpl = fetch) {
  const path = new URL(request.url).pathname;
  if (path === '/health' && request.method === 'GET') return json({status:env.OPENAI_API_KEY && env.OPENAI_MODEL && env.MORROW_TRIAL_TOKEN?.length>=24 && env.TRIAL_BUDGET ? 'ready' : 'setup_required'});
  if (!['/ai','/companion','/transcribe','/vocabulary','/chapter-summary','/chapter-usage'].includes(path)) return failed('Not found',404);
  if (request.method !== 'POST') return failed('POST required',405);
  if (!env.MORROW_TRIAL_TOKEN || env.MORROW_TRIAL_TOKEN.length<24 || !env.OPENAI_API_KEY || !env.OPENAI_MODEL || !env.TRIAL_BUDGET)
    return failed('AI server setup is incomplete',503);
  if (!await sameToken(request.headers.get('Authorization')?.replace(/^Bearer /,''),env.MORROW_TRIAL_TOKEN)) return failed('Access token required',401);
  if (path === '/chapter-usage') return chapterUsage(request,env);
  if (path === '/chapter-summary') return chapterSummary(request,env,fetchImpl);
  if (path === '/vocabulary') return vocabulary(request,env,fetchImpl);
  if (path !== '/ai') return companion(request,env,fetchImpl);
  if (!(request.headers.get('Content-Type')||'').toLowerCase().startsWith('application/json')) return failed('JSON required',400);
  let input;
  try { input = validate(JSON.parse(await boundedText(request.body))); }
  catch(e) { return failed(e instanceof RangeError?'Request too large':'Invalid passage or question',e instanceof RangeError?413:400); }
  // One durable, global counter across all Worker instances. Failed provider calls also count.
  let allowance;
  try { const id=env.TRIAL_BUDGET.idFromName('private-trial'); allowance = await env.TRIAL_BUDGET.get(id).fetch('https://budget/reserve',{method:'POST'}); }
  catch { return failed('Usage control unavailable',503); }
  if (allowance.status !== 200) return failed('Daily test allowance reached',429);
  const instructions = passageAnswerInstructions;
  let provider;
  try { provider = await fetchImpl('https://api.openai.com/v1/responses', {
    method:'POST',signal:AbortSignal.timeout(30000),headers:{'Authorization':'Bearer '+env.OPENAI_API_KEY,'Content-Type':'application/json'},
    body:JSON.stringify({model:env.OPENAI_MODEL,store:false,max_output_tokens:1000,instructions,
      input:JSON.stringify(input),text:{format:{type:'json_schema',name:'passage_answer',strict:true,schema:{type:'object',properties:{answer:{type:'string'},quotes:{type:'array',items:{type:'string'}}},required:['answer','quotes'],additionalProperties:false}}}})
  }); }
  catch { return failed('AI provider did not respond',502); }
  if (!provider.ok) return failed('AI provider could not complete the request',502);
  try {
    const response = JSON.parse(await boundedText(provider.body,262144));
    if (response.status !== 'completed') throw new Error('Incomplete generation');
    const text = (response.output||[]).filter(item=>item.type==='message').flatMap(item=>item.content||[]).filter(item=>item.type==='output_text').map(item=>item.text).join('');
    const answer = JSON.parse(text);
    if (typeof answer.answer !== 'string' || !answer.answer.trim() || answer.answer.length>5000 || !Array.isArray(answer.quotes) || answer.quotes.length>4
       || answer.quotes.some(q=>typeof q!=='string'||!q||q.length>300||!input.passage.includes(q))) throw new Error('Invalid evidence');
    return json({answer:answer.quotes.length?answer.answer:fallback,quotes:answer.quotes});
  } catch { return failed('The answer did not include valid passage evidence',502); }
}
// The desktop app is on the existing public distribution origin. CORS does not
// replace the existing bearer-token authentication or shared Durable Object limits.
const DESKTOP_ORIGIN = 'https://morrow-audio.aptnote8.chatgpt.site';
export async function browserRequest(request,env,fetchImpl=fetch) {
  const origin=request.headers.get('Origin');
  const allowed=origin===DESKTOP_ORIGIN;
  if(request.method==='OPTIONS') {
    if(!allowed||request.headers.get('Access-Control-Request-Method')!=='POST')return failed('Origin not allowed',403);
    const requested=(request.headers.get('Access-Control-Request-Headers')||'').toLowerCase().split(',').map(s=>s.trim()).filter(Boolean);
    if(requested.some(s=>!['authorization','content-type'].includes(s)))return failed('Headers not allowed',403);
    return new Response(null,{status:204,headers:{'Access-Control-Allow-Origin':DESKTOP_ORIGIN,'Access-Control-Allow-Methods':'POST','Access-Control-Allow-Headers':'Authorization, Content-Type','Access-Control-Max-Age':'600','Vary':'Origin'}});
  }
  if(origin&&!allowed)return failed('Origin not allowed',403);
  const response=await handleRequest(request,env,fetchImpl);
  if(!allowed)return response;
  const headers=new Headers(response.headers);headers.set('Access-Control-Allow-Origin',DESKTOP_ORIGIN);headers.set('Vary','Origin');
  return new Response(response.body,{status:response.status,headers});
}
export default {fetch(request,env){return browserRequest(request,env);}};

/** SQLite-backed Durable Object: reserve each attempt atomically before contacting OpenAI. */
export class TrialBudget {
  constructor(state,env) { this.state=state; this.env=env; }
  async fetch(request) {
    if (request.method!=='POST') return failed('POST required',405);
    if(new URL(request.url).pathname.startsWith('/chapter-'))return chapterBudget(this.state,request,this.env);
    if(new URL(request.url).pathname.startsWith('/vocabulary-'))return vocabularyBudget(this.state,request,this.env);
    if(new URL(request.url).pathname!=='/reserve')return companionBudget(this.state,request);
    const configured=Number(this.env.AI_DAILY_LIMIT||20);
    const limit=Number.isInteger(configured)&&configured>0&&configured<=200?configured:20;
    const today=new Date().toISOString().slice(0,10);
    const allowed=await this.state.storage.transaction(async tx=>{
      const prior=await tx.get('usage');
      const count=prior?.day===today?prior.count:0;
      if(count>=limit)return false;
      await tx.put('usage',{day:today,count:count+1}); return true;
    });
    return json({allowed},allowed?200:429);
  }
}
