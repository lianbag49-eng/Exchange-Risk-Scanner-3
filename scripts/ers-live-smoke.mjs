import assert from 'node:assert/strict';
import {createHash,randomUUID} from 'node:crypto';
import {writeFileSync} from 'node:fs';
import {validatePreflight,preflightLevel,CHECKS} from '../kyc-service/preflight.mjs';
import {exchanges,withinDomain,TOPICS} from '../kyc-service/exchange-research.mjs';

// Opt-in CI smoke test: fixed operator-owned origin, invented observations only.
// Never accepts provider/reviewer keys, uploads images, or tests private accounts.
const origin='https://ers-ai-review.onrender.com';
const report={checkedAt:new Date().toISOString(),origin,syntheticOnly:true,checks:[]};
const safeCode=value=>typeof value==='string'&&/^[a-z_]{1,80}$/.test(value)?value:'unexpected_response';
const sha=value=>createHash('sha256').update(value).digest('hex');
async function request(path,body,token){
 const started=Date.now();
 const response=await fetch(origin+path,{method:body?'POST':'GET',redirect:'error',signal:AbortSignal.timeout(80000),headers:{Accept:'application/json',...(body?{'Content-Type':'application/json'}:{}),...(token?{Authorization:'Bearer '+token}:{})},...(body?{body:JSON.stringify(body)}:{})});
 let data;try{data=await response.json();}catch{data={error:'non_json_response'};}
 if(!response.ok){const e=new Error(safeCode(data?.error));e.httpStatus=response.status;throw e;}
 return {data,durationMs:Date.now()-started};
}
async function check(name,run){
 try{const detail=await run();report.checks.push({name,status:'passed',...detail});}
 catch(e){report.checks.push({name,status:'failed',error:safeCode(e.message),httpStatus:Number(e.httpStatus)||null});}
 console.log(JSON.stringify(report.checks.at(-1)));
}
let token;
await check('service_health',async()=>{const r=await request('/healthz');assert.equal(r.data.status,'ready');return {durationMs:r.durationMs};});
await check('startup_session',async()=>{
 const r=await request('/v1/startup/session',{requestId:randomUUID(),consent:true,scope:'ers-startup-v1'});
 assert.equal(r.data.scope,'ers-startup-v1');assert.match(r.data.token,/^[a-f0-9]{64}$/);assert.ok(r.data.registeredCount>0);assert.ok(Date.parse(r.data.expiresAt)>Date.now());token=r.data.token;
 return {durationMs:r.durationMs,registeredCount:r.data.registeredCount};
});
if(token){
 await check('real_ai_preflight',async()=>{
  const input={requestId:randomUUID(),consent:true,items:[{id:sha('ERS invented sample A - no user package'),signals:[],unknowns:['account_identity_unknown','exchange_risk_unknown']},{id:sha('ERS invented sample B - no user package'),signals:['app_debuggable','auto_time_off'],unknowns:['official_app_unverified']}]};
  const expected=validatePreflight(input);const {data,durationMs}=await request('/v1/startup/preflight',input,token);
  assert.equal(data.requestId,input.requestId);assert.equal(data.inputSha256,expected.inputSha256);assert.equal(data.source,'ai_preflight');assert.equal(data.riskScope,'observed_technical_signals');assert.equal(data.actualCauseConfirmed,false);assert.equal(data.officialVerified,false);
  assert.equal(data.reviews.length,input.items.length);assert.equal(new Set(data.reviews.map(r=>r.id)).size,input.items.length);
  for(const r of data.reviews){const item=input.items.find(i=>i.id===r.id);assert.ok(item);assert.equal(r.level,preflightLevel(item));assert.ok(Array.isArray(r.focus)&&r.focus.every(c=>item.signals.includes(c)));assert.ok(Array.isArray(r.checks)&&r.checks.length>0&&r.checks.every(c=>CHECKS.includes(c)));if(item.signals.includes('app_debuggable'))assert.ok(r.focus.includes('app_debuggable'));}
  return {durationMs,itemCount:data.reviews.length};
 });
 await check('real_public_source_research',async()=>{
  const exchange=[...exchanges.values()].find(e=>e.slug==='binance');assert.ok(exchange);
  const input={requestId:randomUUID(),consent:true,exchangeId:exchange.id};const {data,durationMs}=await request('/v1/startup/exchange',input,token);
  assert.equal(data.requestId,input.requestId);assert.equal(data.exchangeId,exchange.id);assert.equal(data.scope,'public_exchange_guidance');assert.equal(data.actualCauseConfirmed,false);assert.equal(data.officialVerified,false);
  assert.equal(data.status,'public_guidance');assert.ok(data.topics.length>0);assert.equal(new Set(data.topics.map(t=>t.code)).size,data.topics.length);for(const t of data.topics){assert.ok(TOPICS.includes(t.code));assert.ok(withinDomain(t.sourceUrl,data.domain));}
  return {durationMs,exchangeId:exchange.id,topicCount:data.topics.length,cached:data.cached===true};
 });
}
report.passed=report.checks.length===4&&report.checks.every(c=>c.status==='passed');
writeFileSync('ers-live-report.json',JSON.stringify(report,null,2)+'\n');
console.log('ERS_LIVE_CHECK_COMPLETE '+JSON.stringify({passed:report.passed,checks:report.checks.length}));
if(!report.passed)process.exitCode=1;
