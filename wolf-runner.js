const http=require('http');
const targets=[
 'https://wolf-telegram-support-ocdwtvgxt-dope18.vercel.app/api/ping',
 'https://wolf-telegram-support-ocdwtvgxt-dope18.vercel.app/api/setup'
];
async function run(){
 for(const u of targets){
  try{
   const r=await fetch(u);
   const t=await r.text();
   console.log('CHECK',u,'STATUS',r.status,'BODY',t);
  }catch(e){console.error('CHECKERR',u,e&&e.stack||e)}
 }
}
run();
setInterval(run,60000);
http.createServer((req,res)=>{res.writeHead(200,{'content-type':'text/plain'});res.end('wolf diagnostic runner');}).listen(process.env.PORT||10000);
