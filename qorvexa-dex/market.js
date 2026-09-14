const $=id=>document.getElementById(id);
const intervals={'1m':60000,'5m':300000,'15m':900000,'1h':3600000,'4h':14400000,'1d':86400000};
let generation=0,controller,socket,retry,heartbeat,lastUpdate=0,candles=[],book=null,trades=[],lastTradeTime=0,streamReceived=0,dirty=false;
let marketMeta=[];
function endpoint(){return $('marketNetwork').value==='testnet'?'https://api.hyperliquid-testnet.xyz':'https://api.hyperliquid.xyz'}
function selection(){return marketMeta.find(x=>x.value===$('marketSymbol').value)}
function emit(){window.dispatchEvent(new CustomEvent('beltrix:market',{detail:{network:$('marketNetwork').value,market:selection(),book,received:streamReceived}}))}
function paintBook(){for(const [id,levels] of [['marketBids',book?.levels?.[0]],['marketAsks',book?.levels?.[1]]]){const box=$(id);box.replaceChildren();for(const level of (levels||[]).slice(0,8)){const row=document.createElement('div');row.className='row space pair';const price=document.createElement('span'),size=document.createElement('span');price.textContent=level.px;size.textContent=level.sz;row.append(price,size);box.append(row)}}
const box=$('marketTrades');box.replaceChildren();for(const t of trades.slice(-10).reverse()){const row=document.createElement('div');row.className='row space pair';row.textContent=`${new Date(t.time).toLocaleTimeString()}  ${t.side==='B'?'매수':'매도'}  ${t.px} · ${t.sz}`;box.append(row)}}

const canvas=$('marketCanvas'),ctx=canvas.getContext('2d');
function status(text){$('marketStatus').textContent=text;}
async function info(body,signal){const r=await fetch(endpoint()+'/info',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(body),signal});if(!r.ok)throw Error('HTTP '+r.status);return r.json();}
function normalize(c){const x={t:Number(c.t),o:Number(c.o),h:Number(c.h),l:Number(c.l),c:Number(c.c),v:Number(c.v)};return Object.values(x).every(Number.isFinite)&&x.l>0&&x.h>=Math.max(x.o,x.c)&&x.l<=Math.min(x.o,x.c)?x:null;}
function draw(){
 const w=canvas.clientWidth||600,h=320,dpr=devicePixelRatio||1;canvas.width=w*dpr;canvas.height=h*dpr;ctx.scale(dpr,dpr);ctx.clearRect(0,0,w,h);
 const rows=candles.slice(-Math.max(20,Math.floor((w-75)/7)));if(!rows.length){ctx.fillStyle='#9aaba3';ctx.fillText('캔들 데이터 없음',20,40);return}
 const low=Math.min(...rows.map(x=>x.l)),high=Math.max(...rows.map(x=>x.h)),range=high-low||high*.01,pad=range*.08;
 const y=p=>20+(high+pad-p)/(range+pad*2)*230;
 ctx.font='11px system-ui';for(let i=0;i<5;i++){const p=low+range*i/4;ctx.strokeStyle='#26362e';ctx.beginPath();ctx.moveTo(0,y(p));ctx.lineTo(w-70,y(p));ctx.stroke();ctx.fillStyle='#9aaba3';ctx.fillText(p.toPrecision(6),w-66,y(p)+4)}
 const step=(w-75)/rows.length,vmax=Math.max(...rows.map(x=>x.v),1);
 rows.forEach((c,i)=>{const x=i*step+step/2;ctx.strokeStyle=ctx.fillStyle=c.c>=c.o?'#54dfa0':'#ff6678';ctx.beginPath();ctx.moveTo(x,y(c.h));ctx.lineTo(x,y(c.l));ctx.stroke();ctx.fillRect(x-step*.3,Math.min(y(c.o),y(c.c)),Math.max(1,step*.6),Math.max(1,Math.abs(y(c.o)-y(c.c))));ctx.globalAlpha=.4;ctx.fillRect(x-step*.3,295-c.v/vmax*35,Math.max(1,step*.6),c.v/vmax*35);ctx.globalAlpha=1;});
 ctx.fillStyle='#9aaba3';ctx.fillText(new Date(rows[0].t).toLocaleString(),4,316);const c=rows.at(-1);if(!lastTradeTime)$('marketPrice').textContent=c.c.toLocaleString(undefined,{maximumFractionDigits:8});$('marketOHLC').textContent=`O ${c.o}  H ${c.h}  L ${c.l}  C ${c.c}  V ${c.v}`;
}
function cleanup(){clearTimeout(retry);clearInterval(heartbeat);controller?.abort();if(socket){socket.onclose=null;socket.close();socket=null}}
async function selectMarket(){
 const token=++generation;cleanup();controller=new AbortController();candles=[];book=null;trades=[];lastTradeTime=0;streamReceived=0;emit();paintBook();lastUpdate=0;$('marketPrice').textContent='—';$('marketOHLC').textContent='';draw();status('연결 중');
 const coin=$('marketSymbol').value,interval=$('marketInterval').value;if(!coin){status('종목 없음');return}
 const ownController=controller;const timeout=setTimeout(()=>ownController.abort(),15000);
 try{
 const endTime=Date.now();const rows=await info({type:'candleSnapshot',req:{coin,interval,startTime:endTime-intervals[interval]*240,endTime}},controller.signal);
 if(token!==generation)return;
 candles=[...new Map(rows.map(normalize).filter(Boolean).map(c=>[c.t,c])).values()].sort((a,b)=>a.t-b.t);lastUpdate=Date.now();draw();status(candles.length?'스냅샷 수신 · 실시간 연결 중':'해당 종목 캔들 없음');
 socket=new WebSocket(endpoint().replace('https:','wss:')+'/ws');
 socket.onopen=()=>{if(token!==generation)return;for(const subscription of [{type:'candle',coin,interval},{type:'l2Book',coin},{type:'trades',coin}])socket.send(JSON.stringify({method:'subscribe',subscription}));heartbeat=setInterval(()=>{if(socket?.readyState===1)socket.send(JSON.stringify({method:'ping'}))},25000)};
 socket.onmessage=e=>{if(token!==generation)return;try{
 const msg=JSON.parse(e.data);
 if(msg.channel==='l2Book'&&msg.data.coin===coin){const b=msg.data;if(!Array.isArray(b.levels)||b.levels.length!==2||!Number.isFinite(b.time)||Math.abs(Date.now()-b.time)>30000)return;if(!b.levels.every(xs=>Array.isArray(xs)&&xs.every(x=>Number(x.px)>0&&Number(x.sz)>=0)))return;if(book&&b.time<book.time)return;book=b;streamReceived=Date.now();dirty=true;emit();}
 if(msg.channel==='trades'&&Array.isArray(msg.data)){for(const t of msg.data){if(t.coin!==coin||!Number.isFinite(t.time)||!Number.isFinite(Number(t.px))||Number(t.px)<=0||Number(t.sz)<0||t.time>Date.now()+5000)continue;if(trades.some(x=>x.tid===t.tid&&x.time===t.time))continue;trades.push(t);}trades.sort((a,b)=>a.time-b.time);trades=trades.slice(-100);dirty=true;}
 if(msg.channel==='candle'){for(const raw of (Array.isArray(msg.data)?msg.data:[msg.data])){if(raw.s!==coin||raw.i!==interval)continue;const c=normalize(raw);if(!c)continue;const i=candles.findIndex(x=>x.t===c.t);if(i>=0)candles[i]=c;else candles.push(c);candles.sort((a,b)=>a.t-b.t);candles=candles.slice(-300);lastUpdate=Date.now();dirty=true;}}
 }catch{status('데이터 형식 오류')}};
 socket.onclose=()=>{if(token!==generation)return;clearInterval(heartbeat);status('연결 끊김 · 5초 후 재연결');retry=setTimeout(selectMarket,5000)};
 socket.onerror=()=>status('실시간 연결 오류');
 }catch(e){if(token===generation)status('시세 조회 실패 · 새로고침으로 재시도')}finally{clearTimeout(timeout)}
}
async function loadSymbols(){
 ++generation;cleanup();marketMeta=[];book=null;streamReceived=0;trades=[];lastTradeTime=0;$('marketPrice').textContent='—';$('marketSymbol').replaceChildren();emit();paintBook();candles=[];draw();status('종목 조회 중');
 const mode=$('marketType').value,token=generation;const abort=new AbortController(),timeout=setTimeout(()=>abort.abort(),15000);
 try{const meta=await info({type:mode==='spot'?'spotMeta':'meta'},abort.signal);if(token!==generation)return;
 const rows=mode==='spot'?meta.universe.map(p=>({value:p.name,label:p.tokens.map(i=>meta.tokens.find(t=>t.index===i)?.name||'?').join('/'),asset:10000+p.index,szDecimals:meta.tokens.find(t=>t.index===p.tokens[0])?.szDecimals,spot:true})):meta.universe.map((x,i)=>({value:x.name,label:x.name+' / USDC PERP',asset:i,szDecimals:x.szDecimals,spot:false,delisted:x.isDelisted})).filter(x=>!x.delisted);
 marketMeta=rows;rows.forEach(r=>$('marketSymbol').add(new Option(r.label,r.value)));if(mode!=='spot'&&rows.some(x=>x.value==='ETH'))$('marketSymbol').value='ETH';await selectMarket();
 }catch{if(token===generation)status('종목 목록 조회 실패 · 새로고침으로 재시도')}finally{clearTimeout(timeout)}
}
$('marketNetwork').onchange=loadSymbols;$('marketType').onchange=loadSymbols;$('marketSymbol').onchange=selectMarket;$('marketInterval').onchange=selectMarket;$('marketRefresh').onclick=()=> $('marketSymbol').options.length?selectMarket():loadSymbols();
new ResizeObserver(draw).observe(canvas);
setInterval(()=>{
 if(dirty){draw();paintBook();dirty=false;const t=trades.at(-1);if(t){lastTradeTime=t.time;$('marketPrice').textContent=Number(t.px).toLocaleString(undefined,{maximumFractionDigits:8})}}
 const age=streamReceived?Date.now()-streamReceived:Infinity;
 $('marketClock').textContent=new Date().toLocaleTimeString()+' · 화면 갱신 1초';
 if(age<5000&&socket?.readyState===1)status('실시간 · '+($('marketNetwork').value==='testnet'?'테스트넷':'메인넷')+' · 최근 호가 '+(age/1000).toFixed(1)+'초 전');
 else if(streamReceived)status('호가 지연 / 연결 확인 · 주문 잠금');
 if(streamReceived)emit();
},1000);
window.addEventListener('pagehide',()=>{++generation;cleanup()});window.addEventListener('pageshow',e=>{if(e.persisted)loadSymbols()});loadSymbols();
