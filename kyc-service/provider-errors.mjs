import {ReviewError} from './review.mjs';
// Log only bounded configuration identifiers. Provider messages can contain API
// keys and are deliberately neither logged nor returned to anonymous clients.
export async function providerFailure(response,model,scope){
 let error;try{error=(await response.json()).error}catch{}
 const safe=v=>typeof v==='string'&&/^[a-zA-Z0-9_.:[\]-]{1,80}$/.test(v)&&!v.startsWith('sk-')?v:'unspecified';
 console.error(JSON.stringify({event:'ers_provider_rejected',scope,status:response.status||0,code:safe(error?.code),parameter:safe(error?.param),model:safe(model)}));
 throw new ReviewError(response.status===401?'provider_credentials':response.status===429?'provider_quota':[400,404].includes(response.status)?'provider_search_configuration':'provider_unavailable',502);
}
