import {setTimeout as sleep} from 'node:timers/promises';
import {ReviewError} from './review.mjs';
import {providerHint} from './provider-hints.mjs';

// Provider messages, request bodies and credentials must never reach logs/responses.
const safe=v=>typeof v==='string'&&/^[a-zA-Z0-9_.:[\]-]{1,80}$/.test(v)&&!v.startsWith('sk-')?v:'unspecified';
export function classifyProviderFailure(status,error={}){
 if(status===401)return 'provider_credentials';
 if(status===403||status===404||error?.code==='model_not_found')return 'provider_model_access';
 if(status===429)return error?.code==='rate_limit_exceeded'?'provider_rate_limit':'provider_quota';
 if(status===400){
  if(error?.code==='invalid_json_schema')return 'provider_schema_configuration';
  if(typeof error?.param==='string'&&(error.param==='text.format'||error.param.startsWith('text.format.')))return 'provider_output_configuration';
  if(error?.param==='model')return 'provider_model_access';
  return 'provider_search_configuration';
 }
 return 'provider_unavailable';
}
function retryAfter(response){
 const raw=response.headers?.get?.('retry-after');if(!raw)return 1500;
 const seconds=Number(raw);const ms=Number.isFinite(seconds)?seconds*1000:Date.parse(raw)-Date.now();
 return Number.isFinite(ms)?Math.max(0,ms):1500;
}
export async function providerFailure(response,model,scope){
 let error;try{error=(await response.json()).error}catch{}
 console.error(JSON.stringify({event:'ers_provider_rejected',scope:safe(scope),status:response.status||0,code:safe(error?.code),parameter:safe(error?.param),model:safe(model),hint:providerHint(error)}));
 const result=new ReviewError(classifyProviderFailure(response.status,error),502);
 // A bounded parameter code is for internal compatibility handling, not a provider message.
 result.parameter=safe(error?.param);
 result.retryable=result.code==='provider_rate_limit'||[500,502,503,504].includes(response.status);
 result.retryAfterMs=retryAfter(response);
 throw result;
}
/** Retry one explicit transient rejection. Credit exhaustion, invalid credentials,
 * invalid configuration and ambiguous network/timeouts are never replayed. */
export async function requestProvider(payload,{apiKey,model,scope,fetchFn=fetch,sleepFn=sleep,timeoutMs=45000}){
 const started=Date.now();
 for(let attempt=0;attempt<2;attempt++){
  let response;
  try{response=await fetchFn('https://api.openai.com/v1/responses',{method:'POST',redirect:'error',signal:AbortSignal.timeout(Math.max(1,timeoutMs-(Date.now()-started))),headers:{Authorization:'Bearer '+apiKey,'Content-Type':'application/json'},body:JSON.stringify({...payload,model,store:false})});}
  catch(e){throw new ReviewError(e?.name==='TimeoutError'||e?.name==='AbortError'?'provider_timeout':'provider_unavailable',502);}
  if(response.ok)return response;
  try{await providerFailure(response,model,scope)}catch(e){
   if(attempt!==0||e.retryable!==true||e.retryAfterMs>5000||Date.now()-started+e.retryAfterMs>=timeoutMs-1000)throw e;
   await sleepFn(e.retryAfterMs);
  }
 }
 throw new ReviewError('provider_unavailable',502);
}
