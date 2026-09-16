import test from 'node:test';
import assert from 'node:assert/strict';
import {classifyProviderFailure,requestProvider} from '../provider-errors.mjs';
import {preflightTokenBudget} from '../preflight.mjs';
import {exchanges,researchExchange} from '../exchange-research.mjs';
const denied=(status,error,headers={})=>({ok:false,status,headers:new Headers(headers),json:async()=>({error})});
const options={apiKey:'invented-test-only',model:'test-model',scope:'test'};

test('quota, explicit rate limit, model access and format incompatibility are distinct',()=>{
 assert.equal(classifyProviderFailure(429,{code:'insufficient_quota'}),'provider_quota');
 assert.equal(classifyProviderFailure(429,{code:'rate_limit_exceeded'}),'provider_rate_limit');
 assert.equal(classifyProviderFailure(429,{}),'provider_quota');
 assert.equal(classifyProviderFailure(403,{}),'provider_model_access');
 assert.equal(classifyProviderFailure(404,{code:'model_not_found'}),'provider_model_access');
 assert.equal(classifyProviderFailure(400,{param:'text.format',code:'unsupported_parameter'}),'provider_output_configuration');
 assert.equal(classifyProviderFailure(400,{param:'text.format.schema',code:'invalid_json_schema'}),'provider_schema_configuration');
});
test('one transient rejection is retried with bounded Retry-After and store disabled',async()=>{
 let calls=0;const delays=[];const success={ok:true};
 const result=await requestProvider({input:'invented',store:true},{...options,sleepFn:async ms=>delays.push(ms),fetchFn:async(url,init)=>{assert.equal(url,'https://api.openai.com/v1/responses');assert.equal(JSON.parse(init.body).store,false);return ++calls===1?denied(429,{code:'rate_limit_exceeded'},{'Retry-After':'2'}):success;}});
 assert.equal(result,success);assert.equal(calls,2);assert.deepEqual(delays,[2000]);
});
test('exhausted quota and unknown 429 are never repeatedly billed or retried',async()=>{
 for(const code of ['insufficient_quota',undefined]){let calls=0;await assert.rejects(requestProvider({},{...options,sleepFn:async()=>assert.fail('must not retry'),fetchFn:async()=>{calls++;return denied(429,{code})}}),e=>e.code==='provider_quota');assert.equal(calls,1);}
});
test('long Retry-After, credentials and model errors do not create retry storms',async()=>{
 for(const response of [denied(429,{code:'rate_limit_exceeded'},{'Retry-After':'60'}),denied(401,{}),denied(404,{code:'model_not_found'})]){let calls=0;await assert.rejects(requestProvider({},{...options,sleepFn:async()=>assert.fail('must not retry'),fetchFn:async()=>{calls++;return response;}}));assert.equal(calls,1);}
});
test('a transient provider failure is bounded to two calls',async()=>{
 let calls=0;await assert.rejects(requestProvider({},{...options,sleepFn:async()=>{},fetchFn:async()=>{calls++;return denied(503,{})}}),e=>e.code==='provider_unavailable');assert.equal(calls,2);
});
test('ambiguous network failures are not replayed; timeouts have their own safe code',async()=>{
 for(const name of ['Error','TimeoutError']){let calls=0;await assert.rejects(requestProvider({},{...options,fetchFn:async()=>{calls++;const e=new Error('sensitive upstream detail');e.name=name;throw e;}}),e=>e.code===(name==='TimeoutError'?'provider_timeout':'provider_unavailable'));assert.equal(calls,1);}
});
test('small preflight batches no longer reserve maximum output budget',()=>{
 assert.equal(preflightTokenBudget(1),1024);assert.equal(preflightTokenBudget(2),1024);assert.ok(preflightTokenBudget(10)>1024);assert.equal(preflightTokenBudget(50),8000);
});
function fixture(){const exchange=[...exchanges.values()].find(e=>e.slug==='binance');const url='https://www.binance.com/en/support/faq/synthetic-public-guidance';return {exchange,url,result:{status:'completed',output:[{type:'web_search_call',status:'completed',action:{sources:[{url}]}},{type:'message',content:[{type:'output_text',text:JSON.stringify({topics:[{code:'appeal',sourceUrl:url}]})}]}]}};}
async function exerciseResearch({badSource=false,error={param:'text.format',code:'unsupported_parameter'}}={}){
 const f=fixture();let providerCalls=0;
 const result=researchExchange(f.exchange,{...options,fetchFn:async(url,init)=>{
  if(url.startsWith('https://api.coinmarketcap.com/'))return {ok:true,json:async()=>({data:{id:f.exchange.id,slug:f.exchange.slug,urls:{website:['https://www.binance.com']}}})};
  const body=JSON.parse(init.body);providerCalls++;
  if(providerCalls===1){assert.equal(body.text.format.strict,true);return denied(400,error);}
  assert.equal(body.text,undefined);assert.equal(body.store,false);assert.deepEqual(body.tools[0].filters.allowed_domains,['binance.com']);assert.equal(body.tool_choice,'required');
  if(badSource)f.result.output[1].content[0].text=JSON.stringify({topics:[{code:'appeal',sourceUrl:'https://www.binance.com/invented-unretrieved-page'}]});
  return {ok:true,json:async()=>f.result};
 }});
 return {result:await result,providerCalls};
}
test('search-format incompatibility falls back without weakening source binding',async()=>{
 const {result,providerCalls}=await exerciseResearch();assert.equal(providerCalls,2);assert.equal(result.status,'public_guidance');assert.equal(result.actualCauseConfirmed,false);assert.equal(result.officialVerified,false);
});
test('compatibility mode still rejects fabricated or unretrieved source links',async()=>{
 await assert.rejects(exerciseResearch({badSource:true}),e=>e.code==='unsupported_research_source');
});
test('invalid schemas do not silently downgrade to compatibility mode',async()=>{
 await assert.rejects(exerciseResearch({error:{code:'invalid_json_schema',param:'text.format.schema'}}),e=>e.code==='provider_schema_configuration');
});
