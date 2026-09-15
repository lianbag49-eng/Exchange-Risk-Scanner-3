import test from 'node:test';
import assert from 'node:assert/strict';
import {validatePreflight,preflightDigest,preflightLevel,preflightSummary,reviewPreflight} from '../preflight.mjs';
import {createService} from '../server.mjs';
const input=()=>({requestId:'11111111-2222-3333-4444-555555555555',consent:true,items:[{id:'a'.repeat(64),signals:['auto_time_off'],unknowns:['exchange_risk_unknown','account_identity_unknown']}]});
const output=()=>({reviews:[{id:'a'.repeat(64),focus:['auto_time_off'],checks:['check_clock','official_support']}]});
const response=o=>({status:'completed',output:[{type:'message',content:[{type:'output_text',text:JSON.stringify(o)}]}]});
test('preflight never needs an image or identity and rejects unexpected uploads',()=>{
 const b=validatePreflight(input());assert.equal(b.inputSha256,preflightDigest(input().items));
 for(const patch of [{consent:false},{image:'data:image/jpeg;base64,private'},{uid:'1234'},{apiKey:'secret'},{items:[]},{items:[{...input().items[0],packageName:'private.app'}]},{items:[{...input().items[0],signals:['confirmed_fraud']}]},{items:[input().items[0],input().items[0]]}])assert.throws(()=>validatePreflight({...input(),...patch}));
});
test('triage is scoped to technical signals and missing information is not an adverse fact',()=>{
 assert.equal(preflightLevel({signals:['app_debuggable'],unknowns:[]}), 'HIGH');
 assert.equal(preflightLevel({signals:['vpn_present','proxy_present'],unknowns:['api_status_unknown']}), 'LOW');
 assert.equal(preflightLevel({signals:[],unknowns:['app_metadata_missing']}), 'UNKNOWN');
 const r=preflightSummary(validatePreflight(input()),output(),'mock');assert.equal(r.reviews[0].level,'MEDIUM');assert.equal(r.actualCauseConfirmed,false);assert.equal(r.officialVerified,false);assert.equal(r.riskScope,'observed_technical_signals');
});
test('model cannot invent signals, change severity, claim official access or omit critical evidence',()=>{
 for(const row of [{...output().reviews[0],level:'HIGH'},{...output().reviews[0],focus:['app_debuggable']},{...output().reviews[0],checks:['bypass_kyc']},{...output().reviews[0],id:'b'.repeat(64)},{...output().reviews[0],focus:[]}])assert.throws(()=>preflightSummary(validatePreflight(input()),{reviews:[row]},'mock'));
 const i=input();i.items[0].signals=['app_debuggable','auto_time_off'];assert.throws(()=>preflightSummary(validatePreflight(i),output(),'mock'));
 assert.throws(()=>preflightSummary(validatePreflight(input()),{...output(),officialVerified:true},'mock'));
});
test('provider receives only bounded codes with strict output and no stored response',async()=>{
 let request;const result=await reviewPreflight(validatePreflight(input()),{apiKey:'fake',model:'mock',fetchFn:async(url,options)=>{request={url,...options};return{ok:true,json:async()=>response(output())}}});
 const b=JSON.parse(request.body);assert.equal(b.store,false);assert.equal(b.text.format.strict,true);assert.equal(request.redirect,'error');assert.equal(request.url,'https://api.openai.com/v1/responses');assert.deepEqual(JSON.parse(b.input),input().items);
 assert.equal(result.source,'ai_preflight');assert(!b.input.includes('image'));assert(!b.input.includes('uid'));assert(!b.input.includes('apiKey'));
});
test('preflight refuses incomplete, invalid and refused model responses',async()=>{
 for(const o of [{status:'incomplete'},response({reviews:[]}),{status:'completed',output:[{type:'message',content:[{type:'refusal'}]}]}])await assert.rejects(reviewPreflight(validatePreflight(input()),{apiKey:'fake',model:'mock',fetchFn:async()=>({ok:true,json:async()=>o})}));
});
test('preflight endpoint enforces authentication, consent, replay and per-user rate limits',async t=>{
 let calls=0;const token='test-preflight-token-'.repeat(3);const server=createService({apiKey:'fake',model:'mock',tokens:{reviewer:token},preflight:async b=>{calls++;return preflightSummary(b,output(),'mock')}});
 await new Promise(r=>server.listen(0,'127.0.0.1',r));t.after(()=>server.close());
 const post=(body,auth=token)=>fetch(`http://127.0.0.1:${server.address().port}/v1/preflight`,{method:'POST',headers:{Authorization:'Bearer '+auth,'Content-Type':'application/json'},body:JSON.stringify(body)});
 assert.equal((await post(input(),'bad')).status,401);assert.equal((await post({...input(),consent:false})).status,400);assert.equal(calls,0);
 assert.equal((await post(input())).status,200);assert.equal(calls,1);assert.equal((await post(input())).status,409);
 for(let n=0;n<7;n++)await post({...input(),image:'not allowed'});
 assert.equal((await post(input())).status,429);assert.equal(calls,1);
});
