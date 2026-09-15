import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {knowledge,validateCauses,causeDigest,eligibleCauses,causeSummary,reviewCauses} from '../causes.mjs';
import {createService} from '../server.mjs';
const time='2026-09-15T09:00:00Z';
const evidence=(code,origin='user_report')=>({code,origin,observedAt:time});
const input=()=>({requestId:'11111111-2222-3333-4444-555555555555',consent:true,caseId:'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee',exchange:'bybit',knowledgeVersion:knowledge.version,evidence:[evidence('bybit_10024')]});
const completed=o=>({status:'completed',output:[{type:'message',content:[{type:'output_text',text:JSON.stringify(o)}]}]});
test('source catalog is identical on phone and server; every rule has official provenance and bounded evidence',()=>{
 assert.deepEqual(knowledge,JSON.parse(readFileSync(new URL('../../android-app/app/src/main/assets/cause-knowledge.json',import.meta.url),'utf8')));
 assert.equal(knowledge.rules.length,14);assert.equal(new Set(knowledge.rules.map(r=>r.id)).size,14);
 for(const r of knowledge.rules){assert(r.limit&&r.checks.length&&r.groups.length);for(const s of r.sources)assert(knowledge.sources.some(x=>x.id===s));for(const c of [...r.groups.flat(),...r.counter])assert(knowledge.evidence[c]);}
 for(const s of knowledge.sources)assert(['bybit-exchange.github.io','developers.binance.com','www.binance.com','www.okx.com','developer.android.com'].includes(new URL(s.url).host));
});
test('input rejects personal data, ground-truth labels, cross-exchange codes, forged provenance and knowledge drift',()=>{
 const body=validateCauses(input());assert.equal(body.inputSha256,causeDigest(body));
 for(const patch of [{consent:false},{notice:'private text'},{uid:'1234'},{image:'private'},{apiKey:'secret'},{confirmedCause:'fraud'},{previousPredictions:[]},{exchange:'binance'},{knowledgeVersion:'old'},{evidence:[evidence('bybit_10024','exchange_api')]},{evidence:[evidence('bybit_10024'),evidence('bybit_10024')]},{evidence:[evidence('__proto__')]},{evidence:[{...evidence('bybit_10024'),observedAt:'not-a-date'}]}])assert.throws(()=>validateCauses({...input(),...patch}));
 const reversed={...input(),evidence:[evidence('bybit_10010'),evidence('bybit_10024')]};assert.equal(validateCauses(reversed).inputSha256,validateCauses({...reversed,evidence:[...reversed.evidence].reverse()}).inputSha256);
});
test('exact Bybit error meanings stay narrow and generic 403 never becomes a compliance or forgery verdict',()=>{
 const expected={'10002':'request_time','10003':'api_environment','10005':'api_permission','10006':'request_limit','10009':'regional_access','10010':'api_ip','10024':'compliance_unspecified','10027':'trading_unspecified'};
 for(const [code,id] of Object.entries(expected)){const b=validateCauses({...input(),evidence:[evidence('bybit_'+code)]});assert.deepEqual(eligibleCauses(b).map(r=>r.id),[id]);}
 assert.deepEqual(eligibleCauses(validateCauses({...input(),evidence:[evidence('notice_security_hold')]})),[]);
 assert.throws(()=>validateCauses({...input(),evidence:[evidence('bybit_403')]}));
 const result=causeSummary(validateCauses(input()),{ranked:['compliance_unspecified']},'mock');assert.equal(result.actualCauseConfirmed,false);assert.equal(result.officialVerified,false);assert(!JSON.stringify(result).includes('probability'));
});
test('counter evidence is retained and public help alone does not make a personalized diagnosis',()=>{
 const b=validateCauses({...input(),exchange:'okx',evidence:[evidence('notice_withdrawal_restricted'),evidence('security_changed'),evidence('security_unchanged'),evidence('deposit_pending'),evidence('deposit_complete')]});
 const rows=eligibleCauses(b);assert.deepEqual(rows.map(r=>r.id),['security_cooldown','deposit_confirmation']);assert.deepEqual(rows[0].counter,['security_unchanged']);assert.deepEqual(rows[1].counter,['deposit_complete']);
 assert.equal(eligibleCauses(validateCauses({...input(),exchange:'okx',evidence:[evidence('notice_withdrawal_restricted')]})).length,0);
 assert.equal(eligibleCauses(validateCauses({...input(),exchange:'other',evidence:[evidence('notice_kyc_required')]})).length,0);
 const binance=eligibleCauses(validateCauses({...input(),exchange:'binance',evidence:[evidence('api_locked','exchange_api')]}));assert.deepEqual(binance.map(r=>r.id),['api_trading_lock']);assert(binance[0].limit.includes('특정하지'));
});
test('insufficient evidence skips external inference and returns an explicit unknown state',async()=>{
 const r=await reviewCauses(validateCauses({...input(),exchange:'other',evidence:[]}),{apiKey:'fake',model:'mock',fetchFn:()=>{throw Error('Must never invoke provider')}});
 assert.equal(r.status,'insufficient_evidence');assert.equal(r.model,'not_invoked');assert.deepEqual(r.ranked,[]);
});
test('AI can only rank eligible candidates; fabricated facts, omitted slots and duplicate ranks fail closed',()=>{
 const b=validateCauses(input());for(const o of [{ranked:['document_forgery']},{ranked:['api_ip']},{ranked:[]},{ranked:['compliance_unspecified','compliance_unspecified']},{ranked:['compliance_unspecified'],actualCauseConfirmed:true}])assert.throws(()=>causeSummary(b,o,'mock'));
});
test('provider receives evidence and official rules, excludes case identifiers and old predictions, uses strict output',async()=>{
 let sent;const r=await reviewCauses(validateCauses(input()),{apiKey:'fake',model:'mock',fetchFn:async(url,options)=>{sent={url,...options};return{ok:true,json:async()=>completed({ranked:['compliance_unspecified']})}}});
 const b=JSON.parse(sent.body);assert.equal(b.store,false);assert.equal(b.text.format.strict,true);assert.equal(sent.redirect,'error');assert.equal(sent.url,'https://api.openai.com/v1/responses');assert.equal(r.source,'ai_cause_hypotheses');assert(!b.input.includes(input().caseId));assert(!b.input.includes(input().requestId));assert(!b.input.includes('previousPredictions'));assert.equal(JSON.parse(b.input).candidates[0].sources[0],'bybit_errors');
});
test('provider failure, incomplete, refusal and malformed output are not converted into successful findings',async()=>{
 for(const result of [{status:'incomplete'},completed({ranked:['api_ip']}),{status:'completed',output:[{type:'message',content:[{type:'refusal'}]}]}])await assert.rejects(reviewCauses(validateCauses(input()),{apiKey:'fake',model:'mock',fetchFn:async()=>({ok:true,json:async()=>result})}));
 await assert.rejects(reviewCauses(validateCauses(input()),{apiKey:'fake',model:'mock',fetchFn:async()=>({ok:false})}));
});
test('cause endpoint enforces authentication consent replay and no-store without exposing private provider failures',async t=>{
 let calls=0;const token='cause-test-token-'.repeat(3);const server=createService({apiKey:'fake',model:'mock',tokens:{reviewer:token},causes:async b=>{calls++;return causeSummary(b,{ranked:['compliance_unspecified']},'mock')}});
 await new Promise(r=>server.listen(0,'127.0.0.1',r));t.after(()=>server.close());
 const post=(body,auth=token)=>fetch(`http://127.0.0.1:${server.address().port}/v1/causes`,{method:'POST',headers:{Authorization:'Bearer '+auth,'Content-Type':'application/json'},body:JSON.stringify(body)});
 assert.equal((await post(input(),'bad')).status,401);assert.equal((await post({...input(),consent:false})).status,400);assert.equal(calls,0);
 const result=await post(input());assert.equal(result.status,200);assert.equal(result.headers.get('cache-control'),'no-store');assert.equal((await result.json()).actualCauseConfirmed,false);assert.equal(calls,1);assert.equal((await post(input())).status,409);
});
