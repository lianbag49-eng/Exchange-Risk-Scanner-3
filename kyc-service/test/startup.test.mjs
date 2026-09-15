import test from 'node:test';
import assert from 'node:assert/strict';
import {randomUUID} from 'node:crypto';
import {readFileSync} from 'node:fs';
import {createService} from '../server.mjs';
import {STARTUP_SCOPE} from '../startup.mjs';
import {directory,exchanges,canonicalDomain,researchExchange,researchResult,withinDomain} from '../exchange-research.mjs';
import {preflightSummary} from '../preflight.mjs';
import {providerFailure} from '../provider-errors.mjs';
const request=()=>({requestId:randomUUID(),consent:true});
const item={id:'a'.repeat(64),signals:['auto_time_off'],unknowns:['account_identity_unknown','exchange_risk_unknown']};
const exchange=exchanges.get(270);
test('provider diagnostics identify rejected parameters without logging provider messages or credentials',async()=>{
 const logs=[],original=console.error;console.error=value=>logs.push(value);
 try{
  await assert.rejects(providerFailure({status:400,json:async()=>({error:{code:'unsupported_parameter',param:'text.format',message:'private sk-never-log-this'}})},'gpt-4.1-mini','research'),e=>e.code==='provider_search_configuration');
  await assert.rejects(providerFailure({status:401,json:async()=>({error:{code:'invalid_api_key',param:'sk-never-log-this',message:'private'}})},'sk-never-log-this','preflight'),e=>e.code==='provider_credentials');
 }finally{console.error=original}
 assert.equal(JSON.parse(logs[0]).parameter,'text.format');assert.equal(logs.length,2);assert(!logs.join('').includes('never-log'));assert(!logs.join('').includes('private'));
});
const policy=id=>({exchangeId:id,slug:exchanges.get(id).slug,status:'public_guidance',topics:[{code:'appeal',sourceUrl:'https://example.com/help'}],actualCauseConfirmed:false,officialVerified:false});
async function service(t,options={}){
 const server=createService({apiKey:'fake',model:'mock',tokens:{reviewer:'private-reviewer-token-'.repeat(3)},startupOptions:options});
 await new Promise(r=>server.listen(0,'127.0.0.1',r));t.after(()=>server.close());
 const post=(path,body,token='')=>fetch(`http://127.0.0.1:${server.address().port}${path}`,{method:'POST',headers:{'Content-Type':'application/json',Authorization:'Bearer '+token},body:JSON.stringify(body)});
 const connect=async()=>{const r=await post('/v1/startup/session',{...request(),scope:STARTUP_SCOPE});assert.equal(r.status,200);return(await r.json()).token};
 return{post,connect};
}
test('server and Android share the complete directory, including beyond three account adapters',()=>{
 assert.equal(exchanges.size,2448);assert.deepEqual(directory,JSON.parse(readFileSync(new URL('../../android-app/app/src/main/assets/cmc-directory.json',import.meta.url))));
 for(const name of ['Toobit','CoinW','BingX','Upbit','Bitget'])assert([...exchanges.values()].some(e=>e.name===name),name);
});
test('first-use session requires consent, expires and never grants access to privileged routes',async t=>{
 let now=1000000;const{post,connect}=await service(t,{now:()=>now});
 const invalid=await post('/v1/startup/session',{...request(),scope:STARTUP_SCOPE,consent:false});assert.equal(invalid.status,400);
 const body={...request(),scope:STARTUP_SCOPE};assert.equal((await post('/v1/startup/session',body)).status,200);assert.equal((await post('/v1/startup/session',body)).status,409);
 const token=await connect();assert.equal(token.length,64);
 for(const path of ['/v1/identity/status','/v1/causes','/v1/kyc/reviews','/v1/diagnostics','/v1/preflight','/v1/connection-check'])assert.equal((await post(path,{},token)).status,401,path);
 now+=7200001;assert.equal((await post('/v1/startup/preflight',{...request(),items:[item]},token)).status,401);
});
test('automatic triage handles 50 distinct apps and preserves consent, replay and bounded data',async t=>{
 let calls=0;const{post,connect}=await service(t,{preflight:async body=>{calls++;return preflightSummary(body,{reviews:body.items.map(i=>({id:i.id,focus:i.signals,checks:['check_clock']}))},'mock')}});
 const token=await connect();const body={...request(),items:Array.from({length:50},(_,i)=>({...item,id:i.toString(16).padStart(64,'0')}))};
 const ok=await post('/v1/startup/preflight',body,token);assert.equal(ok.status,200);assert.equal((await ok.json()).reviews.length,50);
 assert.equal((await post('/v1/startup/preflight',body,token)).status,409);
 for(const patch of [{consent:false},{uid:'private'},{image:'private'},{items:[{...item,secret:'private'}]},{items:[{...item,signals:['fraud_confirmed']}]}])assert.equal((await post('/v1/startup/preflight',{...request(),items:[item],...patch},token)).status,400);
 assert.equal(calls,1);
});
test('registered exchanges are dynamic, cached across sessions and reject caller supplied domains',async t=>{
 const called=[];const{post,connect}=await service(t,{research:async e=>{called.push(e.id);return policy(e.id)}});
 const token=await connect();const ids=[...exchanges.values()].filter(e=>['Toobit','CoinW','BingX','Upbit','Bitget'].includes(e.name)).map(e=>e.id);
 for(const exchangeId of ids)assert.equal((await post('/v1/startup/exchange',{...request(),exchangeId},token)).status,200);
 assert.deepEqual(called,ids);
 const again=await connect();const cached=await post('/v1/startup/exchange',{...request(),exchangeId:ids[0]},again);assert.equal((await cached.json()).cached,true);assert.equal(called.length,ids.length);
 assert.equal((await post('/v1/startup/exchange',{...request(),exchangeId:999999999},token)).status,422);
 assert.equal((await post('/v1/startup/exchange',{...request(),exchangeId:270,url:'http://127.0.0.1'},token)).status,400);
});
test('provider budgets apply globally, including across fresh sessions',async t=>{
 let calls=0;const{post,connect}=await service(t,{limits:{preflightPerDay:1,researchPerDay:1},preflight:async()=>{calls++;return{}},research:async e=>{calls++;return policy(e.id)}});
 let token=await connect();assert.equal((await post('/v1/startup/preflight',{...request(),items:[item]},token)).status,200);
 assert.equal((await post('/v1/startup/exchange',{...request(),exchangeId:270},token)).status,200);
 token=await connect();assert.equal((await post('/v1/startup/preflight',{...request(),items:[item]},token)).status,429);
 const denied=await post('/v1/startup/exchange',{...request(),exchangeId:521},token);assert.equal(denied.status,429);assert.equal((await denied.json()).error,'startup_daily_limit');assert.equal(calls,2);
 assert.equal((await post('/v1/startup/exchange',{...request(),exchangeId:270},token)).status,200);
});
test('canonical sites come only from fixed CMC detail endpoint and verified directory identity',async()=>{
 let requested;assert.equal(await canonicalDomain(exchange,async url=>{requested=url;return{ok:true,json:async()=>({data:{id:270,slug:exchange.slug,urls:{website:['https://www.binance.com/']}}})}}),'binance.com');
 assert.equal(requested,'https://api.coinmarketcap.com/data-api/v3/exchange/detail?id=270');
 for(const patch of [{id:521},{slug:'wrong'},{urls:{website:['http://127.0.0.1/']}}])await assert.rejects(canonicalDomain(exchange,async()=>({ok:true,json:async()=>({data:{id:270,slug:exchange.slug,urls:{website:['https://binance.com']},...patch}})})));
 assert.equal(withinDomain('https://support.binance.com/help','binance.com'),true);
 for(const url of ['https://binance.com.attacker.test','https://evilbinance.com','https://user:pass@binance.com','http://binance.com'])assert.equal(withinDomain(url,'binance.com'),false);
});
function searchResponse(topics=[{code:'identity_requirement',sourceUrl:'https://www.binance.com/help/kyc'}]){return{status:'completed',output:[{type:'web_search_call',status:'completed',action:{sources:[{type:'url',url:'https://www.binance.com/help/kyc'}]}},{type:'message',content:[{type:'output_text',text:JSON.stringify({topics})}]}]};}
test('research requires actual retrieved primary sources and cannot claim account facts',()=>{
 const r=researchResult(searchResponse(),exchange,'binance.com','mock');assert.equal(r.scope,'public_exchange_guidance');assert.equal(r.actualCauseConfirmed,false);assert.equal(r.officialVerified,false);
 assert.equal(researchResult(searchResponse([]),exchange,'binance.com','mock').status,'insufficient_sources');
 for(const topic of [{code:'identity_requirement',sourceUrl:'https://www.binance.com/invented'},{code:'identity_requirement',sourceUrl:'https://attacker.test'},{code:'fraud_confirmed',sourceUrl:'https://www.binance.com/help/kyc'},{code:'identity_requirement',sourceUrl:'https://www.binance.com/help/kyc',actualCauseConfirmed:true}])assert.throws(()=>researchResult(searchResponse([topic]),exchange,'binance.com','mock'));
 const missing=searchResponse();missing.output.shift();assert.throws(()=>researchResult(missing,exchange,'binance.com','mock'));
});
test('provider searches the canonical domain for any directory exchange with no user identity',async()=>{
 const toobit=[...exchanges.values()].find(e=>e.name==='Toobit');let outbound;
 const result=await researchExchange(toobit,{apiKey:'fake',model:'mock',fetchFn:async(url,options)=>{
  if(url.startsWith('https://api.coinmarketcap.com/'))return{ok:true,json:async()=>({data:{id:toobit.id,slug:toobit.slug,urls:{website:['https://www.toobit.com/']}}})};
  outbound=JSON.parse(options.body);return{ok:true,json:async()=>searchResponse([])};
 }});
 assert.equal(result.exchangeId,toobit.id);assert.deepEqual(outbound.tools,[{type:'web_search',filters:{allowed_domains:['toobit.com']}}]);assert.equal(outbound.tool_choice,'required');assert.deepEqual(outbound.include,['web_search_call.action.sources']);assert.equal(outbound.store,false);assert.equal(outbound.text.format.strict,true);assert(!outbound.input.includes('uid'));
});
