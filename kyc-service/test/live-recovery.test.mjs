import test from 'node:test';
import assert from 'node:assert/strict';
import {classifyProviderFailure,requestProvider} from '../provider-errors.mjs';
import {exchanges,researchExchange} from '../exchange-research.mjs';
const options={apiKey:'synthetic-only',model:'test-model'};
const denied=(status,error)=>({ok:false,status,headers:new Headers(),json:async()=>({error})});
test('observed credit_balance_exhausted is a permanent quota failure and is never retried',async()=>{
 assert.equal(classifyProviderFailure(429,{code:'credit_balance_exhausted'}),'provider_quota');
 let count=0;await assert.rejects(requestProvider({},{...options,scope:'test',sleepFn:async()=>assert.fail('do not retry exhausted credit'),fetchFn:async()=>{count++;return denied(429,{code:'credit_balance_exhausted'})}}),e=>e.code==='provider_quota');assert.equal(count,1);
});
async function toolsConflict({invalidSource=false,alwaysReject=false,parameter='tools'}={}){
 const exchange=exchanges.get(270);const source='https://www.binance.com/synthetic-public-help';let count=0;
 const fetchFn=async(url,init)=>{
  if(url.startsWith('https://api.coinmarketcap.com/'))return {ok:true,json:async()=>({data:{id:exchange.id,slug:exchange.slug,urls:{website:['https://www.binance.com/']}}})};
  const body=JSON.parse(init.body);count++;
  assert.deepEqual(body.tools,[{type:'web_search',filters:{allowed_domains:['binance.com']}}]);
  assert.equal(body.model,'test-model');assert.equal(body.tool_choice,'required');assert.equal(body.store,false);
  assert.deepEqual(body.include,['web_search_call.action.sources']);
  if(count===1||alwaysReject)return denied(400,{param:parameter});
  assert.equal(body.text,undefined);
  return {ok:true,json:async()=>({status:'completed',output:[{type:'web_search_call',status:'completed',action:{sources:[{url:source}]}},{type:'message',content:[{type:'output_text',text:JSON.stringify({topics:[{code:'appeal',sourceUrl:invalidSource?'https://www.binance.com/not-retrieved':source}]})}]}]})};
 };
 try{return {result:await researchExchange(exchange,{...options,fetchFn}),count}}catch(e){e.testCount=count;throw e;}
}
test('observed tools parameter rejection receives one format compatibility retry',async()=>{
 const {result,count}=await toolsConflict();assert.equal(count,2);assert.equal(result.status,'public_guidance');assert.equal(result.officialVerified,false);
});
test('tools compatibility still rejects unobserved sources',async()=>{
 await assert.rejects(toolsConflict({invalidSource:true}),e=>e.code==='unsupported_research_source'&&e.testCount===2);
});
test('persistent tools rejection is not hidden or retried indefinitely',async()=>{
 await assert.rejects(toolsConflict({alwaysReject:true}),e=>e.code==='provider_search_configuration'&&e.testCount===2);
});
test('unrelated bad parameters never invoke the tools compatibility path',async()=>{
 await assert.rejects(toolsConflict({parameter:'unrelated'}),e=>e.code==='provider_search_configuration'&&e.testCount===1);
});
