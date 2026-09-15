import {createHmac} from 'node:crypto';
import {ReviewError} from './review.mjs';

const uuid=/^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/i;
const exact=(o,keys)=>o&&typeof o==='object'&&!Array.isArray(o)&&Object.keys(o).sort().join(',')===[...keys].sort().join(',');
const text=(s,n=160)=>typeof s==='string'?s.replace(/[\u0000-\u001f\u007f]/g,' ').slice(0,n):'';
export function validateIdentity(input){
 if(!exact(input,['requestId','consent'])||!uuid.test(input.requestId)||input.consent!==true)throw new ReviewError('invalid_identity_request');
 return {requestId:input.requestId};
}
// Server-owned access lists. The client cannot select an applicant, UID or exchange binding.
export function identityConfig(env=process.env){
 const appToken=env.SUMSUB_APP_TOKEN||'',secret=env.SUMSUB_SECRET_KEY||'',raw=env.ERS_IDENTITY_SUBJECTS||'';
 if(!appToken&&!secret&&!raw)return null;
 try{
  if(!appToken||!secret||!raw||appToken.length>1024||secret.length>1024)throw Error();
  const subjects=JSON.parse(raw);if(!subjects||typeof subjects!=='object'||Array.isArray(subjects)||Object.keys(subjects).length>100)throw Error();
  const seen=new Set();
  for(const [reviewer,rows] of Object.entries(subjects)){
   if(!reviewer||!Array.isArray(rows)||rows.length>20)throw Error();
   for(const row of rows){
    if(!exact(row,['id','label','applicantId'])||typeof row.id!=='string'||!uuid.test(row.id)||seen.has(row.id)||typeof row.label!=='string'||!row.label.trim()||row.label.length>80||!/^[a-z0-9]{24}$/.test(row.applicantId))throw Error();
    seen.add(row.id);
   }
  }
  return {appToken,secret,subjects};
 }catch{throw new Error('Invalid identity provider configuration; check server settings.');}
}
export function sumsubSignature(secret,path,seconds){return createHmac('sha256',secret).update(String(seconds)+'GET'+path).digest('hex');}
export function identitySummary(subject,status,checkedAt){
 if(!status||typeof status!=='object'||Array.isArray(status)||typeof status.reviewStatus!=='string')throw new ReviewError('identity_invalid_response',502);
 const completed=status.reviewStatus==='completed',r=status.reviewResult||{};
 let decision='unknown';
 if(completed){
  if(r.reviewAnswer==='GREEN')decision='approved';
  else if(r.reviewAnswer==='RED'&&r.reviewRejectType==='RETRY')decision='resubmission';
  else if(r.reviewAnswer==='RED')decision='rejected';
 }else if(['init','pending','prechecked','queued','onHold','awaitingService','awaitingUser'].includes(status.reviewStatus))decision='pending';
 const declined=['rejected','resubmission'].includes(decision);
 if(declined&&r.rejectLabels!==undefined&&(!Array.isArray(r.rejectLabels)||r.rejectLabels.length>100||r.rejectLabels.some(v=>typeof v!=='string'||!/^[A-Z0-9_]{1,80}$/.test(v))))throw new ReviewError('identity_invalid_response',502);
 const reasons=declined?[...new Set(r.rejectLabels||[])].slice(0,20):[];
 return {id:subject.id,label:subject.label,source:'sumsub_api',scope:'independent_identity_review',decision,providerStatus:text(status.reviewStatus,40),verificationLevel:text(status.levelName,120),reviewId:text(status.reviewId,120),reviewDate:completed?text(status.reviewDate,80):'',checkedAt,
  reasons,applicantComment:declined?text(r.moderationComment,1000):'',
  documentAuthenticity:reasons.includes('FORGERY')?'provider_reported_forgery':reasons.includes('GRAPHIC_EDITOR')?'provider_reported_edit':'not_separately_reported',
  exchangeAccountBinding:'unverified',exchangeKycVerified:false,actualExchangeCauseConfirmed:false};
}
async function limitedJson(response){
 if(!/^application\/json(?:;|$)/i.test(response.headers.get('content-type')||''))throw new ReviewError('identity_invalid_response',502);
 const reader=response.body.getReader();const chunks=[];let length=0;
 try{while(true){const {done,value}=await reader.read();if(done)break;length+=value.length;if(length>131072)throw new ReviewError('identity_invalid_response',502);chunks.push(Buffer.from(value));}}finally{await reader.cancel();reader.releaseLock();}
 try{return JSON.parse(Buffer.concat(chunks).toString('utf8'));}catch{throw new ReviewError('identity_invalid_response',502);}
}
export async function readIdentity(body,{reviewer,config,fetchFn=fetch,now=Date.now}){
 const checkedAt=new Date(now()).toISOString();
 if(!config)return {requestId:body.requestId,source:'identity_gateway',status:'not_configured',checkedAt,subjects:[]};
 const allowed=Object.hasOwn(config.subjects,reviewer)?config.subjects[reviewer]:[];
 if(!allowed.length)return {requestId:body.requestId,source:'identity_gateway',status:'no_authorized_subjects',checkedAt,subjects:[]};
 // One shared deadline bounds an entire batch; no document images or biometric data are requested.
 const signal=AbortSignal.timeout(45000);
 const subjects=await Promise.all(allowed.map(async subject=>{
  const path=`/resources/applicants/${subject.applicantId}/status`,seconds=Math.floor(now()/1000);
  try{
   const response=await fetchFn('https://api.sumsub.com'+path,{method:'GET',redirect:'error',signal,headers:{'X-App-Token':config.appToken,'X-App-Access-Ts':String(seconds),'X-App-Access-Sig':sumsubSignature(config.secret,path,seconds),'Accept':'application/json'}});
   if(!response.ok)throw new ReviewError(response.status===429?'identity_rate_limit':'identity_provider_unavailable',502);
   return identitySummary(subject,await limitedJson(response),new Date(now()).toISOString());
  }catch(error){return {id:subject.id,label:subject.label,source:'sumsub_api',scope:'independent_identity_review',decision:'unavailable',checkedAt:new Date(now()).toISOString(),error:error instanceof ReviewError?error.code:'identity_provider_unavailable',exchangeAccountBinding:'unverified',exchangeKycVerified:false,actualExchangeCauseConfirmed:false};}
 }));
 return {requestId:body.requestId,source:'identity_gateway',status:'queried',checkedAt:new Date(now()).toISOString(),subjects};
}
