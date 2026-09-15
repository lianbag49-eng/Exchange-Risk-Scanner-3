import test from 'node:test';
import assert from 'node:assert/strict';
import {identityConfig,validateIdentity,identitySummary,readIdentity,sumsubSignature} from '../identity.mjs';
import {createService} from '../server.mjs';
const id='11111111-2222-3333-4444-555555555555',other='aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee';
const subject={id,label:'Synthetic subject',applicantId:'1234567890abcdef12345678'};
const config={appToken:'test-sumsub-token',secret:'test-sumsub-secret',subjects:{alice:[subject],bob:[{...subject,id:other,applicantId:'aaaaaaaaaaaaaaaaaaaaaaaa'}]}};
const input={requestId:id,consent:true};
const status=(answer='RED',state='completed')=>({reviewStatus:state,reviewId:'synthetic-review',levelName:'test-level',reviewDate:'2026-09-15 00:00:00+0000',reviewResult:{reviewAnswer:answer,reviewRejectType:'FINAL',rejectLabels:['FORGERY'],clientComment:'PRIVATE_CLIENT_COMMENT',moderationComment:'Synthetic applicant notice'}});
test('identity inputs and server-owned subject bindings reject client supplied identity and extra fields',()=>{
 assert.deepEqual(validateIdentity(input),{requestId:id});
 for(const extra of [{consent:false},{applicantId:subject.applicantId},{uid:'123456789'},{exchangeKycVerified:true},{image:'photo'}])assert.throws(()=>validateIdentity({...input,...extra}));
 assert.equal(identityConfig({}),null);
 assert.throws(()=>identityConfig({SUMSUB_APP_TOKEN:'partial'}));
 const env={SUMSUB_APP_TOKEN:config.appToken,SUMSUB_SECRET_KEY:config.secret,ERS_IDENTITY_SUBJECTS:JSON.stringify(config.subjects)};
 assert.deepEqual(identityConfig(env),config);
 assert.throws(()=>identityConfig({...env,ERS_IDENTITY_SUBJECTS:JSON.stringify({alice:[{...subject,applicantId:'../../secret'}]})}));
});
test('provider decisions require completed review and never imply exchange KYC or separate document authenticity',()=>{
 const declined=identitySummary(subject,status(),'2026-09-15T00:00:00Z');
 assert.equal(declined.decision,'rejected');assert.equal(declined.documentAuthenticity,'provider_reported_forgery');assert.equal(declined.exchangeKycVerified,false);assert.equal(declined.actualExchangeCauseConfirmed,false);
 assert(!JSON.stringify(declined).includes('PRIVATE_CLIENT_COMMENT'));assert(!JSON.stringify(declined).includes(subject.applicantId));
 const approved=identitySummary(subject,status('GREEN'),'2026-09-15T00:00:00Z');assert.equal(approved.decision,'approved');assert.deepEqual(approved.reasons,[]);assert.equal(approved.documentAuthenticity,'not_separately_reported');
 for(const state of ['pending','onHold','init']){const r=identitySummary(subject,status('RED',state),'2026-09-15T00:00:00Z');assert.equal(r.decision,'pending');assert.deepEqual(r.reasons,[]);assert.equal(r.applicantComment,'');}
 const retry=status();retry.reviewResult.reviewRejectType='RETRY';assert.equal(identitySummary(subject,retry,'2026-09-15T00:00:00Z').decision,'resubmission');
 assert.throws(()=>identitySummary(subject,{reviewStatus:'completed',reviewResult:{reviewAnswer:'RED',rejectLabels:['injected free text']}},'2026-09-15T00:00:00Z'));
});
test('missing provider settings or authorization never request arbitrary applicant data',async()=>{
 const fetchFn=()=>{throw Error('Must not fetch');};
 assert.equal((await readIdentity(input,{reviewer:'alice',config:null,fetchFn})).status,'not_configured');
 assert.equal((await readIdentity(input,{reviewer:'mallory',config,fetchFn})).status,'no_authorized_subjects');
 assert.equal((await readIdentity(input,{reviewer:'__proto__',config,fetchFn})).status,'no_authorized_subjects');
});
test('Sumsub requests are signed GET status-only reads scoped to authenticated reviewer',async()=>{
 let called=[];
 const result=await readIdentity(input,{reviewer:'alice',config,now:()=>1700000000000,fetchFn:async(url,options)=>{called.push({url,...options});return Response.json(status());}});
 assert.equal(called.length,1);assert.equal(called[0].url,'https://api.sumsub.com/resources/applicants/'+subject.applicantId+'/status');assert.equal(called[0].method,'GET');assert.equal(called[0].redirect,'error');assert.equal(called[0].body,undefined);
 assert.equal(sumsubSignature(config.secret,'/resources/applicants/'+subject.applicantId+'/status',1700000000),'699b3a21072e43e85d156c46ddd04c3f9720a0968038cc362735efdcfedf9c26');
 assert.equal(called[0].headers['X-App-Access-Sig'],sumsubSignature(config.secret,'/resources/applicants/'+subject.applicantId+'/status',1700000000));
 assert.equal(result.subjects[0].id,id);assert(!JSON.stringify(result).includes('PRIVATE_CLIENT_COMMENT'));assert(!JSON.stringify(result).includes('test-sumsub-token'));
});
test('provider HTTP errors, malformed output, oversize and redirects become unavailable, never approved',async()=>{
 for(const fetchFn of [async()=>new Response('',{status:401}),async()=>Response.json({}),async()=>new Response('x'.repeat(131073),{headers:{'content-type':'application/json'}}),async()=>{throw Error('secret failure body');}]){
  const result=await readIdentity(input,{reviewer:'alice',config,fetchFn});assert.equal(result.subjects[0].decision,'unavailable');assert(!JSON.stringify(result).includes('secret failure body'));
 }
});
test('identity route authenticates, applies consent and replay checks and passes only authenticated owner',async t=>{
 const alice='alice-test-token-'.repeat(3),bob='bob-test-token-'.repeat(3);let owners=[];
 const server=createService({apiKey:'test',model:'test',tokens:{alice,bob},identitySettings:config,identity:async(body,options)=>{owners.push(options.reviewer);return readIdentity(body,{...options,fetchFn:async()=>Response.json(status())});}});
 await new Promise(r=>server.listen(0,'127.0.0.1',r));t.after(()=>server.close());
 const post=(body,token=alice)=>fetch(`http://127.0.0.1:${server.address().port}/v1/identity/status`,{method:'POST',headers:{Authorization:'Bearer '+token,'Content-Type':'application/json'},body:JSON.stringify(body)});
 assert.equal((await post(input,'bad')).status,401);assert.equal((await post({...input,consent:false})).status,400);assert.equal((await post({...input,applicantId:subject.applicantId})).status,400);assert.equal(owners.length,0);
 const a=await (await post(input)).json();assert.equal(a.subjects[0].id,id);assert.equal((await post(input)).status,409);
 const b=await (await post(input,bob)).json();assert.equal(b.subjects[0].id,other);assert.deepEqual(owners,['alice','bob']);
});
