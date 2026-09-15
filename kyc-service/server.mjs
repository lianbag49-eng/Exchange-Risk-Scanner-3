import http from 'node:http';
import {validatePreflight,reviewPreflight} from './preflight.mjs';
import {identityConfig,validateIdentity,readIdentity} from './identity.mjs';
import {createHash,timingSafeEqual} from 'node:crypto';
import {pathToFileURL} from 'node:url';
import {ReviewError,validateInput,reviewImage} from './review.mjs';
import {validateDiagnostic,diagnoseImage,providerStatus} from './diagnostics.mjs';
const hash=s=>createHash('sha256').update(s).digest();
export function createService({apiKey,model,tokens,review=reviewImage,diagnose=diagnoseImage,checkProvider=providerStatus,preflight=reviewPreflight,identity=readIdentity,identitySettings=null,now=Date.now}){
 if(!apiKey||!model||!tokens||!Object.keys(tokens).length||Object.keys(tokens).length>100||Object.values(tokens).some(t=>typeof t!=='string'||t.length<32)||new Set(Object.values(tokens)).size!==Object.keys(tokens).length)throw Error('Configure OPENAI_API_KEY, OPENAI_MODEL and distinct ERS_REVIEW_TOKENS (min 32 characters each).');
 const users=Object.entries(tokens).map(([id,token])=>({id,digest:hash(token)})),rates=new Map(),seen=new Map();let active=0;
 const server=http.createServer(async(req,res)=>{
  const send=(status,value)=>{res.writeHead(status,{'Content-Type':'application/json','Cache-Control':'no-store','X-Content-Type-Options':'nosniff'});res.end(JSON.stringify(value));};
  if(req.method==='GET'&&req.url==='/healthz')return send(200,{status:'ready'});
  if(req.method!=='POST'||!['/v1/kyc/reviews','/v1/diagnostics','/v1/connection-check','/v1/preflight','/v1/identity/status'].includes(req.url))return send(404,{error:'not_found'});
  const raw=req.headers.authorization||'';const supplied=hash(raw.startsWith('Bearer ')?raw.slice(7):'');const user=users.find(x=>timingSafeEqual(x.digest,supplied));
  if(!user)return send(401,{error:'unauthorized'});
  if(!/^application\/json(?:;|$)/i.test(req.headers['content-type']||''))return send(415,{error:'json_required'});
  const time=now(),rate=rates.get(user.id)||{start:time,count:0};if(time-rate.start>=60000){rate.start=time;rate.count=0}rates.set(user.id,rate);
  if(++rate.count>10||active>=4)return send(429,{error:'review_rate_limit'});
  let claimed=false;
  try{
   let length=0;const chunks=[];for await(const chunk of req){length+=chunk.length;if(length>6*1024*1024)throw new ReviewError('image_too_large',413);chunks.push(chunk)}
   let input;try{input=JSON.parse(Buffer.concat(chunks).toString())}catch{throw new ReviewError('invalid_json')}
   if(req.url==='/v1/connection-check'){if(!input||Array.isArray(input)||typeof input!=='object'||Object.keys(input).length)throw new ReviewError('invalid_json');if(active>=4)throw new ReviewError('review_rate_limit',429);active++;claimed=true;return send(200,await checkProvider({apiKey,model}))}
   const body=req.url==='/v1/identity/status'?validateIdentity(input):req.url==='/v1/preflight'?validatePreflight(input):req.url==='/v1/diagnostics'?validateDiagnostic(input):validateInput(input);for(const [id,expiry] of seen)if(expiry<time)seen.delete(id);
   const key=user.id+':'+body.requestId;if(seen.has(key))throw new ReviewError('duplicate_review',409);seen.set(key,time+300000);
   if(active>=4)throw new ReviewError('review_rate_limit',429);active++;claimed=true;const result=req.url==='/v1/identity/status'?await identity(body,{reviewer:user.id,config:identitySettings}):await (req.url==='/v1/preflight'?preflight:req.url==='/v1/diagnostics'?diagnose:review)(body,{apiKey,model});send(200,result);
  }catch(error){send(error instanceof ReviewError?error.status:500,{error:error instanceof ReviewError?error.code:'review_failed'});}
  finally{if(claimed)active--}
 });
 server.requestTimeout=60000;server.headersTimeout=10000;return server;
}
if(process.argv[1]&&import.meta.url===pathToFileURL(process.argv[1]).href){
 const server=createService({apiKey:process.env.OPENAI_API_KEY,model:process.env.OPENAI_MODEL,tokens:process.env.ERS_REVIEW_TOKENS?JSON.parse(process.env.ERS_REVIEW_TOKENS):process.env.ERS_REVIEW_TOKEN?{reviewer:process.env.ERS_REVIEW_TOKEN}:{},identitySettings:identityConfig()});
 server.listen(Number(process.env.PORT||8080),process.env.HOST||'127.0.0.1',()=>console.log('ERS KYC review server ready'));
}
