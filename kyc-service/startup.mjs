import {createHash,randomBytes} from 'node:crypto';
import {ReviewError} from './review.mjs';
import {validatePreflight,reviewPreflight} from './preflight.mjs';
import {exchanges,researchExchange} from './exchange-research.mjs';

export const STARTUP_SCOPE='ers-startup-v1';
const uuid=s=>typeof s==='string'&&/^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/i.test(s);
const exact=(o,keys)=>o&&typeof o==='object'&&!Array.isArray(o)&&Object.keys(o).sort().join(',')===[...keys].sort().join(',');
const hash=s=>createHash('sha256').update(s).digest('hex');
// Public onboarding is intentionally a separate, narrow capability. These sessions
// cannot read accounts, upload documents, or authenticate to reviewer endpoints.
export function createStartup({apiKey,model,preflight=reviewPreflight,research=researchExchange,now=Date.now,limits={}}){
 const caps={sessionsPerHour:100,preflightPerDay:200,researchPerDay:100,...limits};
 const sessions=new Map(),issued=new Map(),cache=new Map(),inflight=new Map();
 let active=0,hour=-1,issuedCount=0,day=-1,preflightCount=0,researchCount=0;
 return async(req,send)=>{
  if(!['/v1/startup/session','/v1/startup/preflight','/v1/startup/exchange'].includes(req.url))return false;
  let claimed=false;
  try{
   if(req.method!=='POST')throw new ReviewError('not_found',404);
   if(!/^application\/json(?:;|$)/i.test(req.headers['content-type']||''))throw new ReviewError('json_required',415);
   const time=now();for(const[k,v]of sessions)if(v.expires<=time)sessions.delete(k);for(const[k,v]of issued)if(v<=time)issued.delete(k);
   if(hour!==Math.floor(time/3600000)){hour=Math.floor(time/3600000);issuedCount=0}
   if(day!==Math.floor(time/86400000)){day=Math.floor(time/86400000);preflightCount=0;researchCount=0}
   let session=null;
   if(req.url!=='/v1/startup/session'){
    const auth=req.headers.authorization||'';session=sessions.get(hash(auth.startsWith('Bearer ')?auth.slice(7):''));
    if(!session)throw new ReviewError('startup_session_expired',401);
    if(session.calls>=2700)throw new ReviewError('startup_session_limit',429);
   }else if(issuedCount>=caps.sessionsPerHour||sessions.size>=200)throw new ReviewError('startup_session_limit',429);
   let length=0;const chunks=[];for await(const chunk of req){length+=chunk.length;if(length>65536)throw new ReviewError('startup_body_too_large',413);chunks.push(chunk)}
   let input;try{input=JSON.parse(Buffer.concat(chunks).toString())}catch{throw new ReviewError('invalid_json')}
   if(req.url==='/v1/startup/session'){
    if(!exact(input,['requestId','consent','scope'])||!uuid(input.requestId)||input.consent!==true||input.scope!==STARTUP_SCOPE)throw new ReviewError('startup_consent_required');
    if(issued.has(input.requestId))throw new ReviewError('duplicate_review',409);
    if(issuedCount>=caps.sessionsPerHour||sessions.size>=200)throw new ReviewError('startup_session_limit',429);
    const token=randomBytes(32).toString('hex'),expires=time+7200000;issuedCount++;issued.set(input.requestId,expires);sessions.set(hash(token),{expires,calls:0,seen:new Set()});
    send(200,{token,scope:STARTUP_SCOPE,expiresAt:new Date(expires).toISOString(),registeredCount:exchanges.size});return true;
   }
   let body;
   if(req.url==='/v1/startup/preflight')body=validatePreflight(input);
   else{
    if(!exact(input,['requestId','consent','exchangeId'])||!uuid(input.requestId)||input.consent!==true||!Number.isSafeInteger(input.exchangeId))throw new ReviewError('invalid_startup_exchange');
    if(!exchanges.has(input.exchangeId))throw new ReviewError('exchange_not_in_server_directory',422);
    body=input;
   }
   if(session.seen.has(body.requestId))throw new ReviewError('duplicate_review',409);
   session.calls++;session.seen.add(body.requestId);
   if(req.url==='/v1/startup/exchange'){
    const hit=cache.get(body.exchangeId);
    if(hit&&hit.expires>time){send(200,{requestId:body.requestId,...hit.result,cached:true});return true}
    if(inflight.has(body.exchangeId)){send(200,{requestId:body.requestId,...await inflight.get(body.exchangeId),cached:true});return true}
   }
   if(active>=2)throw new ReviewError('startup_busy',429);
   if(req.url==='/v1/startup/preflight'){
    if(preflightCount>=caps.preflightPerDay)throw new ReviewError('startup_daily_limit',429);
    preflightCount++;active++;claimed=true;send(200,await preflight(body,{apiKey,model}));
   }else{
    if(researchCount>=caps.researchPerDay)throw new ReviewError('startup_daily_limit',429);
    researchCount++;active++;claimed=true;
    const pending=research(exchanges.get(body.exchangeId),{apiKey,model});inflight.set(body.exchangeId,pending);
    try{const result=await pending;cache.set(body.exchangeId,{result,expires:now()+(result.status==='public_guidance'?7*86400000:3600000)});send(200,{requestId:body.requestId,...result,cached:false})}finally{inflight.delete(body.exchangeId)}
   }
  }catch(e){send(e instanceof ReviewError?e.status:500,{error:e instanceof ReviewError?e.code:'startup_failed'})}
  finally{if(claimed)active--}
  return true;
 };
}
