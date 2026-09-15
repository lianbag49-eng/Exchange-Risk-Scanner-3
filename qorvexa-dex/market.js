import {fundingView,finite} from './terminal-core.js';
const $=id=>document.getElementById(id);
const intervals={'1m':60000,'5m':300000,'15m':900000,'1h':3600000,'4h':14400000,'1d':86400000};
let generation=0,controller,socket,retry,heartbeat,lastUpdate=0,candles=[],book=null,trades=[],lastTradeTime=0,streamReceived=0,dirty=false;
let marketMeta=[],assetContext=null,contextReceived=0,contextTimer;
function endpoint(){return $('marketNetwork').value==='testnet'?'https://api.hyperliquid-testnet.xyz':'https://api.hyperliquid.xyz'}
function selection(){return marketMeta.find(x=>x.value===$('marketSymbol').value)}
function emit(){window.dispatchEvent(new CustomEvent('beltrix:market',{detail:{network:$('marketNetwork').value,market:selection(),book,received:streamReceived,context:assetContext,contextReceived}}))}
function paintBook(){
 for(const [id,levels] of [['marketBids',book?.levels?.[0]],['marketAsks',book?.levels?.[1]]]){
  const box=$(id);box.replaceChildren();let total=0;const rows=(levels||[]).slice(0,$('bookDepth').value==='fast'?5:20).map(x=>({...x,total:total+=Number(x.sz)}));
  if(id==='marketAsks')rows.reverse();for(const level of rows){const row=document.createElement('button');row.type='button';row.className='book-level';row.style.setProperty('--depth-color',id==='marketBids'?'#53d6a01a':'#ff82901a');row.style.setProperty('--depth-width',`${total?level.total/total*100:0}%`);
   for(const value of [level.px,level.sz,level.total.toLocaleString('en-US',{maximumFractionDigits:6})]){const span=document.createElement('span');span.textContent=value;row.append(span)}
   row.setAttribute('aria-label','Use limit price '+level.px);row.onclick=()=>window.dispatchEvent(new CustomEvent('beltrix:book-price',{detail:{price:level.px,coin:selection()?.value,network:$('marketNetwork').value}}));box.append(row);
  }
 }
 const bid=Number(book?.levels?.[0]?.[0]?.px),ask=Number(book?.levels?.[1]?.[0]?.px);$('marketSpread').textContent=bid>0&&ask>=bid?`Spread ${(ask-bid).toPrecision(4)} · ${((ask-bid)/((ask+bid)/2)*100).toFixed(3)}%`:'Spread —';
 const box=$('marketTrades');box.replaceChildren();for(const t of trades.slice(-10).reverse()){const row=document.createElement('div');row.className='row space pair '+(t.side==='B'?'green':'red');row.textContent=`${new Date(t.time).toLocaleTimeString('en-US')}  ${t.side==='B'?'Buy':'Sell'}  ${t.px} · ${t.sz}`;box.append(row)}
}
function paintContext(){
 const spot=selection()?.spot,ctx=assetContext,stale=!contextReceived||Date.now()-contextReceived>90000;
 const display=(id,v,suffix='')=>{$(id).textContent=!stale&&finite(v)?Number(v).toLocaleString('en-US',{maximumFractionDigits:8})+suffix:'—'};
 display('marketMark',ctx?.markPx);display('marketOracle',ctx?.oraclePx);display('marketVolume',ctx?.dayNtlVlm,' USDC');display('marketOI',ctx?.openInterest,selection()?' '+selection().value:'');
 const change=!stale&&Number(ctx?.prevDayPx)>0?(Number(ctx.markPx)/Number(ctx.prevDayPx)-1)*100:null;$('marketChange').textContent=finite(change)?`${change>=0?'+':''}${change.toFixed(2)}%`:'—';$('marketChange').className=finite(change)?change>=0?'green':'red':'';
 $('marketFundingCard').hidden=!!spot;$('marketFundingTime').hidden=!!spot;
 const f=fundingView(stale?null:ctx?.funding);$('marketFunding').textContent=f.label;$('marketFundingDirection').textContent=f.direction;$('marketFundingCountdown').textContent=f.countdown;$('marketFundingAt').textContent=new Date(f.next).toLocaleTimeString('en-US',{hour:'2-digit',minute:'2-digit'});
 $('marketContextStatus').textContent=stale?'Market statistics unavailable or stale. Funding is not assumed to be zero.':`${spot?'Spot market · No funding or leverage':'Perpetual funding settles hourly · Current rate is indicative'} · Statistics updated ${Math.floor((Date.now()-contextReceived)/1000)}s ago`;
}
async function refreshContext(token,coin){
 try{const rows=await info({type:selection()?.spot?'spotMetaAndAssetCtxs':'metaAndAssetCtxs'},AbortSignal.timeout(10000));if(token!==generation||coin!==selection()?.value)return;
 const i=rows?.[0]?.universe?.findIndex(x=>x.name===coin);if(i>=0&&rows[1]?.[i]&&finite(rows[1][i].markPx)){assetContext=rows[1][i];contextReceived=Date.now();paintContext();emit()}
 }catch{if(token===generation)paintContext()}
}

const canvas=$('marketCanvas'),ctx=canvas.getContext('2d');
function status(text){$('marketStatus').textContent=text;}
async function info(body,signal){const r=await fetch(endpoint()+'/info',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(body),signal});if(!r.ok)throw Error('HTTP '+r.status);return r.json();}
function normalize(c){const x={t:Number(c.t),o:Number(c.o),h:Number(c.h),l:Number(c.l),c:Number(c.c),v:Number(c.v)};return Object.values(x).every(Number.isFinite)&&x.l>0&&x.h>=Math.max(x.o,x.c)&&x.l<=Math.min(x.o,x.c)?x:null;}
function draw(){
 const w=canvas.clientWidth||600,h=320,dpr=devicePixelRatio||1;canvas.width=w*dpr;canvas.height=h*dpr;ctx.scale(dpr,dpr);ctx.clearRect(0,0,w,h);
 const rows=candles.slice(-Math.max(20,Math.floor((w-75)/7)));if(!rows.length){ctx.fillStyle='#9aaba3';ctx.fillText('No candle data',20,40);return}
 const low=Math.min(...rows.map(x=>x.l)),high=Math.max(...rows.map(x=>x.h)),range=high-low||high*.01,pad=range*.08;
 const y=p=>20+(high+pad-p)/(range+pad*2)*230;
 ctx.font='11px system-ui';for(let i=0;i<5;i++){const p=low+range*i/4;ctx.strokeStyle='#26362e';ctx.beginPath();ctx.moveTo(0,y(p));ctx.lineTo(w-70,y(p));ctx.stroke();ctx.fillStyle='#9aaba3';ctx.fillText(p.toPrecision(6),w-66,y(p)+4)}
 const step=(w-75)/rows.length,vmax=Math.max(...rows.map(x=>x.v),1);
 rows.forEach((c,i)=>{const x=i*step+step/2;ctx.strokeStyle=ctx.fillStyle=c.c>=c.o?'#54dfa0':'#ff6678';ctx.beginPath();ctx.moveTo(x,y(c.h));ctx.lineTo(x,y(c.l));ctx.stroke();ctx.fillRect(x-step*.3,Math.min(y(c.o),y(c.c)),Math.max(1,step*.6),Math.max(1,Math.abs(y(c.o)-y(c.c))));ctx.globalAlpha=.4;ctx.fillRect(x-step*.3,295-c.v/vmax*35,Math.max(1,step*.6),c.v/vmax*35);ctx.globalAlpha=1;});
 ctx.fillStyle='#9aaba3';ctx.fillText(new Date(rows[0].t).toLocaleString("en-US"),4,316);const c=rows.at(-1);if(!lastTradeTime)$('marketPrice').textContent=c.c.toLocaleString(undefined,{maximumFractionDigits:8});$('marketOHLC').textContent=`O ${c.o}  H ${c.h}  L ${c.l}  C ${c.c}  V ${c.v}`;
}
function cleanup(){clearTimeout(retry);clearInterval(heartbeat);clearInterval(contextTimer);controller?.abort();if(socket){socket.onclose=null;socket.close();socket=null}}
async function selectMarket(){
 const token=++generation;cleanup();controller=new AbortController();candles=[];book=null;assetContext=null;contextReceived=0;paintContext();trades=[];lastTradeTime=0;streamReceived=0;emit();paintBook();lastUpdate=0;$('marketPrice').textContent='—';$('marketOHLC').textContent='';draw();status('Connecting');
 const coin=$('marketSymbol').value,interval=$('marketInterval').value;if(!coin){status('No markets available');return}
 refreshContext(token,coin);contextTimer=setInterval(()=>{if(!document.hidden)refreshContext(token,coin)},30000);
 const ownController=controller;const timeout=setTimeout(()=>ownController.abort(),15000);
 try{
 const endTime=Date.now();const rows=await info({type:'candleSnapshot',req:{coin,interval,startTime:endTime-intervals[interval]*240,endTime}},controller.signal);
 if(token!==generation)return;
 candles=[...new Map(rows.map(normalize).filter(Boolean).map(c=>[c.t,c])).values()].sort((a,b)=>a.t-b.t);lastUpdate=Date.now();draw();status(candles.length?'Snapshot received · Connecting live feed':'No candles for this market');
 socket=new WebSocket(endpoint().replace('https:','wss:')+'/ws');
 socket.onopen=()=>{if(token!==generation)return;for(const subscription of [{type:'candle',coin,interval},{type:'l2Book',coin,fast:$('bookDepth').value==='fast'},{type:'trades',coin},{type:'activeAssetCtx',coin}])socket.send(JSON.stringify({method:'subscribe',subscription}));heartbeat=setInterval(()=>{if(socket?.readyState===1)socket.send(JSON.stringify({method:'ping'}))},25000)};
 socket.onmessage=e=>{if(token!==generation)return;try{
 const msg=JSON.parse(e.data);
 if(['activeAssetCtx','activeSpotAssetCtx'].includes(msg.channel)&&msg.data?.coin===coin&&finite(msg.data.ctx?.markPx)){assetContext=msg.data.ctx;contextReceived=Date.now();paintContext();emit();}
 if(msg.channel==='l2Book'&&msg.data.coin===coin){const b=msg.data;if(!Array.isArray(b.levels)||b.levels.length!==2||!Number.isFinite(b.time)||Math.abs(Date.now()-b.time)>30000)return;if(!b.levels.every(xs=>Array.isArray(xs)&&xs.every(x=>Number(x.px)>0&&Number(x.sz)>=0)))return;if(b.levels[0].some((x,i,a)=>i&&Number(x.px)>Number(a[i-1].px))||b.levels[1].some((x,i,a)=>i&&Number(x.px)<Number(a[i-1].px)))return;if(b.levels[0][0]&&b.levels[1][0]&&Number(b.levels[0][0].px)>=Number(b.levels[1][0].px))return;if(book&&b.time<book.time)return;book=b;streamReceived=Date.now();dirty=true;emit();}
 if(msg.channel==='trades'&&Array.isArray(msg.data)){for(const t of msg.data){if(t.coin!==coin||!Number.isFinite(t.time)||!Number.isFinite(Number(t.px))||Number(t.px)<=0||Number(t.sz)<0||t.time>Date.now()+5000)continue;if(trades.some(x=>x.tid===t.tid&&x.time===t.time))continue;trades.push(t);}trades.sort((a,b)=>a.time-b.time);trades=trades.slice(-100);dirty=true;}
 if(msg.channel==='candle'){for(const raw of (Array.isArray(msg.data)?msg.data:[msg.data])){if(raw.s!==coin||raw.i!==interval)continue;const c=normalize(raw);if(!c)continue;const i=candles.findIndex(x=>x.t===c.t);if(i>=0)candles[i]=c;else candles.push(c);candles.sort((a,b)=>a.t-b.t);candles=candles.slice(-300);lastUpdate=Date.now();dirty=true;}}
 }catch{status('Invalid market data')}};
 socket.onclose=()=>{if(token!==generation)return;clearInterval(heartbeat);status('Disconnected · Reconnecting in 5s');retry=setTimeout(selectMarket,5000)};
 socket.onerror=()=>status('Live connection error');
 }catch(e){if(token===generation)status('Market request failed · Refresh to retry')}finally{clearTimeout(timeout)}
}
async function loadSymbols(){
 ++generation;cleanup();marketMeta=[];book=null;assetContext=null;contextReceived=0;paintContext();streamReceived=0;trades=[];lastTradeTime=0;$('marketPrice').textContent='—';$('marketSymbol').replaceChildren();emit();paintBook();candles=[];draw();status('Loading markets');
 const mode=$('marketType').value,token=generation;const abort=new AbortController(),timeout=setTimeout(()=>abort.abort(),15000);
 try{const meta=await info({type:mode==='spot'?'spotMeta':'meta'},abort.signal);if(token!==generation)return;
 const rows=mode==='spot'?meta.universe.map(p=>({value:p.name,label:p.tokens.map(i=>meta.tokens.find(t=>t.index===i)?.name||'?').join('/'),asset:10000+p.index,szDecimals:meta.tokens.find(t=>t.index===p.tokens[0])?.szDecimals,spot:true})):meta.universe.map((x,i)=>({value:x.name,label:x.name+' / USDC PERP',asset:i,szDecimals:x.szDecimals,spot:false,maxLeverage:x.maxLeverage,onlyIsolated:x.onlyIsolated,delisted:x.isDelisted})).filter(x=>!x.delisted);
 marketMeta=rows;rows.forEach(r=>$('marketSymbol').add(new Option(r.label,r.value)));if(mode!=='spot'&&rows.some(x=>x.value==='ETH'))$('marketSymbol').value='ETH';await selectMarket();
 }catch{if(token===generation)status('Market list failed · Refresh to retry')}finally{clearTimeout(timeout)}
}
$('bookDepth').onchange=selectMarket;$('marketNetwork').onchange=loadSymbols;$('marketType').onchange=loadSymbols;$('marketSymbol').onchange=selectMarket;$('marketInterval').onchange=selectMarket;$('marketRefresh').onclick=()=> $('marketSymbol').options.length?selectMarket():loadSymbols();
new ResizeObserver(draw).observe(canvas);
setInterval(()=>{
 if(dirty){draw();paintBook();dirty=false;const t=trades.at(-1);if(t){lastTradeTime=t.time;$('marketPrice').textContent=Number(t.px).toLocaleString(undefined,{maximumFractionDigits:8})}}
 const age=streamReceived?Date.now()-streamReceived:Infinity;
 paintContext();
 $('marketClock').textContent=new Date().toLocaleTimeString("en-US")+' · Refresh: 1s';
 if(age<5000&&socket?.readyState===1)status('Live · '+($('marketNetwork').value==='testnet'?'Testnet':'Mainnet')+' · Order book updated '+(age/1000).toFixed(1)+'s ago');
 else if(streamReceived)status('Stale order book · Orders locked');
 if(streamReceived)emit();
},1000);
window.addEventListener('pagehide',()=>{++generation;cleanup()});window.addEventListener('pageshow',e=>{if(e.persisted)loadSymbols()});loadSymbols();
