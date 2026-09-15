import {createHash} from 'node:crypto';
import {ReviewError} from './review.mjs';

export const SIGNALS=['app_debuggable','no_screen_lock','adb_enabled','security_patch_old','auto_time_off','app_disabled','network_unvalidated','no_network','kyc_followup','vpn_present','proxy_present','user_reported_open_incident'];
export const UNKNOWNS=['app_metadata_missing','device_observation_missing','security_patch_unknown','official_app_unverified','account_identity_unknown','exchange_risk_unknown','api_status_unknown','latest_version_unknown'];
export const CHECKS=['verify_app_source','check_security_settings','check_network','check_clock','install_updates','official_kyc_page','official_support','collect_notice'];
const MEDIUM=new Set(['no_screen_lock','adb_enabled','security_patch_old','auto_time_off','app_disabled','network_unvalidated','no_network','kyc_followup','user_reported_open_incident']);
const exact=(o,keys)=>o&&typeof o==='object'&&!Array.isArray(o)&&Object.keys(o).sort().join(',')===[...keys].sort().join(',');
function codes(value,allowed){if(!Array.isArray(value)||value.length>20||new Set(value).size!==value.length||value.some(c=>!allowed.includes(c)))throw new ReviewError('invalid_preflight');return value;}
export function preflightLevel(item){return item.signals.includes('app_debuggable')?'HIGH':item.signals.some(s=>MEDIUM.has(s))?'MEDIUM':item.unknowns.some(u=>['app_metadata_missing','device_observation_missing'].includes(u))?'UNKNOWN':'LOW';}
export function preflightDigest(items){return createHash('sha256').update(items.map(i=>`${i.id}:${[...i.signals].sort().join(',')}:${[...i.unknowns].sort().join(',')}`).join('|')).digest('hex');}
export function validatePreflight(input){
 if(!exact(input,['requestId','consent','items'])||typeof input.requestId!=='string'||!/^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/i.test(input.requestId)||input.consent!==true||!Array.isArray(input.items)||input.items.length<1||input.items.length>50)throw new ReviewError('invalid_preflight');
 const items=input.items.map(i=>{if(!exact(i,['id','signals','unknowns'])||typeof i.id!=='string'||!/^[a-f0-9]{64}$/.test(i.id))throw new ReviewError('invalid_preflight');return{id:i.id,signals:codes(i.signals,SIGNALS),unknowns:codes(i.unknowns,UNKNOWNS)};});
 if(new Set(items.map(i=>i.id)).size!==items.length)throw new ReviewError('invalid_preflight');
 return {requestId:input.requestId,items,inputSha256:preflightDigest(items)};
}
export function preflightSummary(body,output,model){
 if(!exact(output,['reviews'])||!Array.isArray(output.reviews)||output.reviews.length!==body.items.length)throw new ReviewError('invalid_model_response',502);
 const seen=new Set();
 const reviews=output.reviews.map(r=>{
  const item=body.items.find(i=>i.id===r?.id);
  if(!exact(r,['id','focus','checks'])||!item||seen.has(r.id)||!Array.isArray(r.focus)||!Array.isArray(r.checks)||r.focus.length>20||new Set(r.focus).size!==r.focus.length||r.focus.some(c=>!item.signals.includes(c))||r.checks.length<1||r.checks.length>8||new Set(r.checks).size!==r.checks.length||r.checks.some(c=>!CHECKS.includes(c))||(item.signals.length>0&&r.focus.length===0)||(item.signals.includes('app_debuggable')&&!r.focus.includes('app_debuggable')))throw new ReviewError('invalid_model_response',502);
  seen.add(r.id);return {id:r.id,level:preflightLevel(item),focus:r.focus,checks:r.checks};
 });
 return {requestId:body.requestId,inputSha256:body.inputSha256,reviewedAt:new Date().toISOString(),model,reviews,source:'ai_preflight',riskScope:'observed_technical_signals',actualCauseConfirmed:false,officialVerified:false};
}
export async function reviewPreflight(body,{apiKey,model,fetchFn=fetch}){
 const itemSchema={
  type:'object',additionalProperties:false,required:['id','focus','checks'],
  properties:{id:{type:'string'},focus:{type:'array',items:{type:'string',enum:SIGNALS}},checks:{type:'array',items:{type:'string',enum:CHECKS}}}
 };
 const schema={type:'object',additionalProperties:false,required:['reviews'],properties:{reviews:{type:'array',items:itemSchema}}};
 let response;
 try{response=await fetchFn('https://api.openai.com/v1/responses',{method:'POST',redirect:'error',signal:AbortSignal.timeout(45000),headers:{Authorization:'Bearer '+apiKey,'Content-Type':'application/json'},body:JSON.stringify({model,store:false,max_output_tokens:8000,instructions:'Review anonymized device observations for an exchange app self-check. This is technical triage, not a fraud score, identity verification, KYC authenticity check, or exchange ban prediction. Return exactly one review for each input id. Order the supplied observed signals by what should be examined first; focus must contain only supplied signals and include app_debuggable when present. VPN/proxy alone do not establish misuse or account risk. user_reported_open_incident is an unverified user report. Unknowns are not adverse evidence. Choose useful next checks from the allowed codes. Never provide bypass, spoofing or hidden tracking instructions. Do not claim access to app sessions, app screens, private account details or exchange systems. Return no free text.',input:JSON.stringify(body.items),text:{format:{type:'json_schema',name:'ers_preflight',strict:true,schema}}})});}catch{throw new ReviewError('provider_unavailable',502)}
 if(!response.ok)throw new ReviewError('provider_unavailable',502);
 let data;try{data=await response.json()}catch{throw new ReviewError('invalid_model_response',502)}
 if(data.status!=='completed')throw new ReviewError('incomplete_review',502);
 const content=(data.output||[]).filter(x=>x.type==='message').flatMap(x=>x.content||[]);
 if(content.some(x=>x.type==='refusal'))throw new ReviewError('review_refused',422);
 const texts=content.filter(x=>x.type==='output_text');if(texts.length!==1)throw new ReviewError('invalid_model_response',502);
 let output;try{output=JSON.parse(texts[0].text)}catch{throw new ReviewError('invalid_model_response',502)}
 return preflightSummary(body,output,model);
}
