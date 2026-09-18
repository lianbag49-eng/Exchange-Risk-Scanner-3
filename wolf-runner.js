const http=require('http');
let didSetup=false;
async function hit(u){
  try{
    const r=await fetch(u);
    const t=await r.text();
    console.log('CHECK',u,'STATUS',r.status,'BODY',t.slice(0,1200));
  }catch(e){console.error('CHECKERR',u,e&&e.stack||e)}
}
async function run(){
  await hit('https://wolf-telegram-support-bot-dope18.vercel.app/api/ping');
  if(!didSetup){
    didSetup=true;
    await hit('https://wolf-telegram-support-bot-dope18.vercel.app/api/setup');
  }
}
run();
setInterval(run,60000);
http.createServer((req,res)=>{res.writeHead(200,{'content-type':'text/plain'});res.end('wolf diagnostic runner');}).listen(process.env.PORT||10000);
