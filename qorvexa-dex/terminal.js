import { createChart, CandlestickSeries, HistogramSeries } from 'lightweight-charts';
import { ExchangeClient } from '@nktkas/hyperliquid';
import { formatPrice, formatSize } from '@nktkas/hyperliquid/utils';
import { createWalletClient, custom } from 'viem';
import { arbitrumSepolia } from 'viem/chains';

const $ = id => document.getElementById(id);
const API = Object.freeze({testnet:'https://api.hyperliquid-testnet.xyz',mainnet:'https://api.hyperliquid.xyz'});
const CHAIN='0x66eee';
const validAddress=x=>/^0x[0-9a-fA-F]{40}$/.test(x||'');
const numeric=x=>typeof x==='string'&&/^(?:0|[1-9]\d*)(?:\.\d+)?$/.test(x)&&Number.isFinite(Number(x))&&Number(x)>0;
const fmt=(n,max=4)=>Number.isFinite(Number(n))?Number(n).toLocaleString('en-US',{maximumFractionDigits:max}):'—';
const money=n=>Number.isFinite(Number(n))?Number(n).toLocaleString('en-US',{style:'currency',currency:'USD',minimumFractionDigits:2,maximumFractionDigits:2}):'—';
const marketPrice=n=>'$'+fmt(n,8);
const errorText=e=>String(e?.shortMessage||e?.message||e||'알 수 없는 오류').slice(0,260);
let network='testnet',kind='perp',markets=[],market=null,book=null,bookAt=0,marketEpoch=0,accountEpoch=0;
let address=null,provider=null,wallet=null,connecting=false,busy=false,buy=true,review=null,accountData=null,accountAt=0;
let loadingBook=false,loadingChart=false,loadingAccount=false;
const pendingKey='qorvexa-testnet-pending-v1';
let uncertain=null;
try{const x=JSON.parse(sessionStorage.getItem(pendingKey));if(validAddress(x?.user)&&/^0x[0-9a-f]{32}$/.test(x?.cloid)&&Number.isFinite(x?.expires))uncertain=x;}catch{}
function pending(x){uncertain=x;try{if(x)sessionStorage.setItem(pendingKey,JSON.stringify(x));else sessionStorage.removeItem(pendingKey)}catch{}}
async function post(net,path,body,timeout=12000){
 const response=await fetch(API[net]+'/'+path,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(body),cache:'no-store',signal:AbortSignal.timeout(timeout)});
 if(!response.ok)throw Error('서버 응답 '+response.status);
 return response.json();
}
const info=(body,net=network)=>post(net,'info',body);
function row(text,button){const el=document.createElement('div');el.className='pair account-item';const span=document.createElement('span');span.textContent=text;el.append(span);if(button)el.append(button);return el;}
function clearAccount(message='지갑을 연결하면 테스트넷 계정이 표시됩니다.'){
 accountData=null;accountAt=0;$('accountStatus').textContent=message;
 for(const id of ['positions','openOrders','fills','liveBalances'])$(id).textContent='—';
}
function invalidate(){review=null;if(!$('orderDialog').open)return;$('submitOrder').disabled=true;$('submitState').textContent='계정 또는 시장이 변경됐습니다. 주문을 다시 확인하세요.';}
function disconnect(){address=null;wallet=null;accountEpoch++;invalidate();clearAccount();$('walletBtn').textContent='CONNECT WALLET';$('walletAddress').textContent='Hyperliquid 테스트넷 계정 미연결';$('ethBal').textContent='—';}
const chart=createChart($('chart'),{autoSize:true,layout:{background:{color:'#0b131f'},textColor:'#a0aec0',attributionLogo:true},grid:{vertLines:{color:'#172334'},horzLines:{color:'#172334'}},rightPriceScale:{borderColor:'#26384d'},timeScale:{timeVisible:true,borderColor:'#26384d'},crosshair:{mode:0}});
const candles=chart.addSeries(CandlestickSeries,{upColor:'#54dfa0',downColor:'#ff6678',borderVisible:false,wickUpColor:'#54dfa0',wickDownColor:'#ff6678'});
const volumes=chart.addSeries(HistogramSeries,{priceFormat:{type:'volume'},priceScaleId:'volume'});chart.priceScale('volume').applyOptions({scaleMargins:{top:0.84,bottom:0}});
let initialChart=true,streamLatest=null,socket=null,reconnectTimer=null,heartbeat=null,lastStreamBook=0;
async function loadMarkets(){
 const epoch=++marketEpoch,net=network,type=kind;stopStream();invalidate();market=null;book=null;bookAt=0;
 $('market').replaceChildren(new Option('불러오는 중…',''));$('lastPrice').textContent='—';$('marketChange').textContent='24H —';$('feedState').textContent='시장 연결 중';
 for(const id of ['asks','bids','bookMid','bidDepth','askDepth','spread'])$(id).textContent='—';candles.setData([]);volumes.setData([]);
 try{
  const raw=await info({type:type==='perp'?'metaAndAssetCtxs':'spotMetaAndAssetCtxs'},net);if(epoch!==marketEpoch)return;
  if(!Array.isArray(raw)||!Array.isArray(raw[0]?.universe)||!Array.isArray(raw[1]))throw Error('시장 메타데이터 형식 오류');
  const [meta,contexts]=raw;
  markets=meta.universe.map((u,i)=>{
   if(type==='perp')return {id:i,coin:u.name,label:u.name+'/USDC',base:u.name,sz:u.szDecimals,maxLeverage:u.maxLeverage||1,delisted:u.isDelisted,ctx:contexts[i]||{}};
   const tokens=u.tokens?.map(index=>meta.tokens.find(t=>t.index===index));
   if(!tokens?.[0]||tokens[1]?.name!=='USDC')return null;
   return {id:10000+u.index,coin:u.name,label:tokens[0].name+'/USDC',base:tokens[0].name,sz:tokens[0].szDecimals,maxLeverage:1,ctx:contexts[i]||{}};
  }).filter(m=>m&&!m.delisted&&Number.isInteger(m.sz)&&m.sz>=0&&m.sz<=8&&Number(m.ctx.markPx)>0);
  if(!markets.length)throw Error('이 네트워크에서 거래 가능한 시장이 없습니다');
  $('market').replaceChildren(...markets.map(m=>new Option(m.label,String(m.id))));
  const preferred=markets.find(m=>m.base==='ETH')||markets.find(m=>m.base==='PURR')||markets[0];$('market').value=String(preferred.id);
  $('marketCount').textContent=markets.length;$('volume24').textContent=money(markets.reduce((s,m)=>s+(Number(m.ctx.dayNtlVlm)||0),0));
  $('liveMarkets').replaceChildren(...markets.slice(0,8).map(m=>row(m.label+' · '+money(m.ctx.markPx))));
  selectMarket();
 }catch(e){if(epoch!==marketEpoch)return;markets=[];$('market').replaceChildren(new Option('조회 실패',''));$('feedState').textContent='연결 실패 · '+errorText(e);$('chartStatus').textContent='시장 데이터를 불러오지 못했습니다.';}
}
function selectMarket(){
 marketEpoch++;stopStream();streamLatest=null;invalidate();market=markets.find(m=>String(m.id)===$('market').value);book=null;bookAt=0;initialChart=true;
 candles.setData([]);volumes.setData([]);$('asks').replaceChildren();$('bids').replaceChildren();$('bookMid').textContent='—';
 if(!market)return;
 const px=Number(market.ctx.markPx),prev=Number(market.ctx.prevDayPx);$('lastPrice').textContent=marketPrice(px);
 $('marketChange').textContent=prev>0?'24H '+((px/prev-1)*100).toFixed(2)+'%':'24H —';
 $('sizeUnit').textContent=market.base;$('chartTitle').textContent=market.label+' · '+network.toUpperCase();
 $('perpOptions').hidden=kind!=='perp';$('reduceOnly').checked=false;
 $('leverage').replaceChildren(...[1,2,3,5,10,20,50].filter(x=>x<=market.maxLeverage).map(x=>new Option(x+'×',x)));
 $('limitPrice').value='';$('buy').textContent=kind==='perp'?'매수 / Long':'매수';$('sell').textContent=kind==='perp'?'매도 / Short':'매도';
 $('orderNotice').textContent=network==='mainnet'?'메인넷은 조회 전용입니다. 주문하려면 TESTNET으로 전환하세요.':'테스트넷 자산만 사용합니다. 각 주문을 지갑에서 확인하세요.';
 loadingBook=false;loadingChart=false;loadBook();loadChart();startStream();estimate();
}
async function loadBook(){
 if(!market||loadingBook||document.hidden||Date.now()-lastStreamBook<3500)return;loadingBook=true;const epoch=marketEpoch,m=market,net=network;
 try{
  const b=await info({type:'l2Book',coin:m.coin},net);if(epoch!==marketEpoch)return;
  applyBook(b,m,net);
 }catch(e){if(epoch!==marketEpoch)return;bookAt=0;$('bookStatus').textContent='호가 확인 필요 · '+errorText(e);$('feedState').textContent='호가 지연 · 주문 일시 중지';$('depthStatus').textContent='호가 지연 · 이전 수치는 참고용';}
 finally{if(epoch===marketEpoch)loadingBook=false;}
}
function applyBook(b,m,net,streaming=false){
  if(b.coin!==m.coin||!Array.isArray(b.levels)||b.levels.length!==2||!Number.isFinite(b.time)||Date.now()-b.time>15000||b.time-Date.now()>10000)throw Error('오래됐거나 유효하지 않은 호가');
  if(b.levels.some(side=>!Array.isArray(side)||side.some(l=>!(Number(l.px)>0&&Number(l.sz)>0))))throw Error('호가 형식 오류');
  book=b;bookAt=Date.now();
  for(const [id,levels,color] of [['bids',b.levels[0],'green'],['asks',b.levels[1],'red']]){
   let total=0;const rs=levels.slice(0,8).map(l=>{total+=Number(l.sz);const r=document.createElement('div');r.className='book-row '+color;for(const val of [fmt(l.px,6),fmt(l.sz,4),fmt(total,4)]){const sp=document.createElement('span');sp.textContent=val;r.append(sp)}return r});
   $(id).replaceChildren(...(id==='asks'?rs.reverse():rs));
  }
  const bid=Number(b.levels[0][0]?.px),ask=Number(b.levels[1][0]?.px);
  if(bid>0&&ask>0){$('bookMid').textContent=marketPrice((bid+ask)/2);$('spread').textContent=((ask-bid)/ask*100).toFixed(4)+'%';}
  else $('spread').textContent='호가 부족';
  $('bidDepth').textContent=money(b.levels[0].slice(0,20).reduce((s,l)=>s+Number(l.px)*Number(l.sz),0));
  $('askDepth').textContent=money(b.levels[1].slice(0,20).reduce((s,l)=>s+Number(l.px)*Number(l.sz),0));
  const label=net.toUpperCase()+' · '+new Date(b.time).toLocaleTimeString();$('bookStatus').textContent=label;$('depthStatus').textContent=m.label+' · '+label;$('feedState').textContent=streaming?'실시간 호가 연결':'호가 연결 · 3초 간격 갱신';estimate();
}
async function loadChart(){
 if(!market||loadingChart||document.hidden)return;loadingChart=true;const epoch=marketEpoch,m=market,net=network,interval=$('interval').value;
 const ms={'1m':60000,'5m':300000,'15m':900000,'1h':3600000,'4h':14400000,'1d':86400000}[interval];
 try{
  const raw=await info({type:'candleSnapshot',req:{coin:m.coin,interval,startTime:Date.now()-200*ms,endTime:Date.now()}},net);if(epoch!==marketEpoch||interval!==$('interval').value)return;
  if(!Array.isArray(raw))throw Error('캔들 형식 오류');
  if(streamLatest&&streamLatest.s===m.coin&&streamLatest.i===interval)raw.push(streamLatest);
  const good=raw.filter(c=>c.s===m.coin&&c.i===interval&&Number.isFinite(c.t)&&[c.o,c.h,c.l,c.c].every(x=>Number(x)>0)&&Number(c.v)>=0&&Number(c.h)>=Math.max(Number(c.o),Number(c.c),Number(c.l))&&Number(c.l)<=Math.min(Number(c.o),Number(c.c)));
  const cs=[...new Map(good.map(c=>[c.t,c])).values()].sort((a,b)=>a.t-b.t);
  candles.setData(cs.map(c=>({time:Math.floor(c.t/1000),open:+c.o,high:+c.h,low:+c.l,close:+c.c})));
  volumes.setData(cs.map(c=>({time:Math.floor(c.t/1000),value:+c.v,color:+c.c>=+c.o?'#54dfa03d':'#ff66783d'})));
  if(initialChart&&cs.length){chart.timeScale().fitContent();initialChart=false;}
  $('chartStatus').textContent=cs.length?`${net.toUpperCase()} · ${cs.length}개 봉 · 조회 ${new Date().toLocaleTimeString()} · 최근 봉 ${new Date(cs.at(-1).t).toLocaleString()}`:'이 시장의 해당 구간에 캔들이 없습니다.';
  if(cs.length)$('lastPrice').textContent=marketPrice(cs.at(-1).c);
 }catch(e){if(epoch===marketEpoch)$('chartStatus').textContent='차트 갱신 실패 · '+errorText(e);}
 finally{if(epoch===marketEpoch)loadingChart=false;}
}
function stopStream(){
 clearTimeout(reconnectTimer);clearInterval(heartbeat);lastStreamBook=0;
 if(socket){socket.onclose=null;socket.close();socket=null;}
}
function startStream(){
 if(!market||document.hidden)return;const epoch=marketEpoch,m=market,net=network,interval=$('interval').value;
 const ws=new WebSocket(API[net].replace('https://','wss://')+'/ws');socket=ws;
 ws.onopen=()=>{
  if(epoch!==marketEpoch||socket!==ws)return;
  for(const subscription of [{type:'l2Book',coin:m.coin},{type:'candle',coin:m.coin,interval}])ws.send(JSON.stringify({method:'subscribe',subscription}));
  heartbeat=setInterval(()=>{if(ws.readyState===1)ws.send(JSON.stringify({method:'ping'}))},25000);
 };
 ws.onmessage=e=>{
  if(epoch!==marketEpoch||socket!==ws)return;
  try{
   const msg=JSON.parse(e.data);
   if(msg.channel==='l2Book'&&msg.data?.coin===m.coin){applyBook(msg.data,m,net,true);lastStreamBook=Date.now();}
   if(msg.channel==='candle'){
    const c=msg.data;if(c?.s!==m.coin||c.i!==interval||!Number.isFinite(c.t)||[c.o,c.h,c.l,c.c].some(v=>!(Number(v)>0))||!(Number(c.v)>=0)||Number(c.h)<Math.max(+c.o,+c.c,+c.l)||Number(c.l)>Math.min(+c.o,+c.c))return;
    if(streamLatest&&c.t<streamLatest.t)return;streamLatest=c;
    candles.update({time:Math.floor(c.t/1000),open:+c.o,high:+c.h,low:+c.l,close:+c.c});
    volumes.update({time:Math.floor(c.t/1000),value:+c.v,color:+c.c>=+c.o?'#54dfa03d':'#ff66783d'});
    $('lastPrice').textContent=marketPrice(c.c);$('chartStatus').textContent=net.toUpperCase()+' · 실시간 캔들 · '+new Date().toLocaleTimeString();
   }
  }catch{ /* REST polling remains active if a stream packet is malformed. */ }
 };
 ws.onclose=()=>{if(epoch!==marketEpoch||socket!==ws)return;clearInterval(heartbeat);lastStreamBook=0;reconnectTimer=setTimeout(startStream,5000);};
 ws.onerror=()=>{if(socket===ws)$('feedState').textContent='실시간 연결 재시도 · REST 조회 유지';};
}
function estimate(){
 const n=Number($('orderSize').value),p=$('orderType').value==='market'?Number(book?.levels[buy?1:0]?.[0]?.px):Number($('limitPrice').value);
 $('orderEstimate').textContent=n>0&&p>0?'예상 명목가 '+money(n*p)+(kind==='perp'?' · 증거금 약 '+money(n*p/Number($('leverage').value)):'')+' · 수수료 별도':'수량과 가격을 입력하세요.';
}
$('market').onchange=selectMarket;
$('marketType').onchange=()=>{kind=$('marketType').value;loadMarkets();};
$('dataNetwork').onchange=()=>{network=$('dataNetwork').value;loadMarkets();};
$('interval').onchange=()=>{initialChart=true;streamLatest=null;stopStream();loadChart();startStream();};
for(const id of ['orderSize','limitPrice','leverage','marketSlippage','postOnly','reduceOnly'])$(id).addEventListener('input',()=>{invalidate();estimate()});
$('orderType').onchange=()=>{invalidate();const isMarket=$('orderType').value==='market';$('limitRow').hidden=isMarket;$('postOnlyRow').hidden=isMarket;$('marketSlippageRow').hidden=!isMarket;estimate();};
function side(b){buy=b;$('buy').classList.toggle('selected',b);$('sell').classList.toggle('selected',!b);$('buy').setAttribute('aria-pressed',String(b));$('sell').setAttribute('aria-pressed',String(!b));invalidate();estimate()}
$('buy').onclick=()=>side(true);$('sell').onclick=()=>side(false);
$('walletBtn').onclick=async()=>{
 if(connecting||busy)return;if(address){disconnect();return}
 if(!window.ethereum)return window.toast('MetaMask 등 지갑 앱의 내장 브라우저에서 열어주세요. 일반 Safari에서는 차트·모의 거래를 이용할 수 있습니다.');
 connecting=true;$('walletBtn').disabled=true;provider=window.ethereum;
 try{
  const ac=await provider.request({method:'eth_requestAccounts'});if(!validAddress(ac?.[0]))throw Error('지갑 계정 없음');
  try{await provider.request({method:'wallet_switchEthereumChain',params:[{chainId:CHAIN}]})}
  catch(e){if(e.code!==4902)throw e;await provider.request({method:'wallet_addEthereumChain',params:[{chainId:CHAIN,chainName:'Arbitrum Sepolia',nativeCurrency:{name:'ETH',symbol:'ETH',decimals:18},rpcUrls:['https://sepolia-rollup.arbitrum.io/rpc'],blockExplorerUrls:['https://sepolia.arbiscan.io']}]})}
  if((await provider.request({method:'eth_chainId'})).toLowerCase()!==CHAIN)throw Error('테스트넷 네트워크 확인 실패');
  const now=await provider.request({method:'eth_accounts'});if(now?.[0]?.toLowerCase()!==ac[0].toLowerCase())throw Error('계정이 변경됐습니다');
  address=ac[0];accountEpoch++;wallet=createWalletClient({account:address,chain:arbitrumSepolia,transport:custom(provider)});
  $('walletBtn').textContent=address.slice(0,6)+'…'+address.slice(-4)+' ×';$('walletAddress').textContent=address+' · Hyperliquid Testnet';
  await loadAccount();
 }catch(e){disconnect();window.toast('연결 실패 · '+errorText(e))}
 finally{connecting=false;$('walletBtn').disabled=false;}
};
window.ethereum?.on?.('accountsChanged',disconnect);window.ethereum?.on?.('chainChanged',disconnect);window.ethereum?.on?.('disconnect',disconnect);
async function loadAccount(){
 if(!address||loadingAccount)return;loadingAccount=true;const user=address,epoch=accountEpoch;
 try{
  const [perps,spot,orders,fills]=await Promise.all([
   info({type:'clearinghouseState',user},'testnet'),info({type:'spotClearinghouseState',user},'testnet'),info({type:'openOrders',user},'testnet'),info({type:'userFills',user},'testnet')]);
  if(epoch!==accountEpoch)return;
  if(!perps?.marginSummary||!Array.isArray(perps.assetPositions)||!Array.isArray(spot?.balances)||!Array.isArray(orders)||!Array.isArray(fills))throw Error('계정 응답 형식 오류');
  accountData={perps,spot,orders,fills};accountAt=Date.now();
  $('accountStatus').textContent='TESTNET · '+user.slice(0,8)+'…'+user.slice(-6)+' · 조회 '+new Date().toLocaleTimeString();
  $('liveBalances').textContent='선물 계정 가치 '+money(perps.marginSummary.accountValue)+' · 출금 가능 '+money(perps.withdrawable)+' USDC | 현물 '+(spot.balances.map(b=>b.coin+' '+fmt(b.total,6)).join(' · ')||'잔액 없음');
  const ps=perps.assetPositions.filter(p=>Number(p.position?.szi)!==0);
  $('positions').replaceChildren(...ps.map(({position:p})=>row(`${p.coin} ${Number(p.szi)>0?'LONG':'SHORT'} · ${p.szi} · 미실현 손익 ${money(p.unrealizedPnl)} · 청산가 ${p.liquidationPx??'—'}`)));
  if(!ps.length)$('positions').textContent='열린 포지션 없음';
  $('openOrders').replaceChildren(...orders.map(o=>{const b=document.createElement('button');b.className='wallet';b.textContent='취소';b.disabled=busy;b.onclick=()=>cancelOrder(o);return row(`${o.coin} ${o.side} · ${o.sz} @ ${o.limitPx} · #${o.oid}`,b)}));
  if(!orders.length)$('openOrders').textContent='미체결 주문 없음';
  $('fills').replaceChildren(...fills.slice(0,20).map(f=>row(`${new Date(f.time).toLocaleString()} · ${f.coin} ${f.side} ${f.sz} @ ${f.px} · 수수료 ${f.fee} ${f.feeToken||'USDC'}`)));
  if(!fills.length)$('fills').textContent='체결 내역 없음';
  if(uncertain?.user.toLowerCase()===user.toLowerCase())await reconcile(user,epoch);
 }catch(e){if(epoch===accountEpoch){accountAt=0;$('accountStatus').textContent='계정 조회 실패 · '+errorText(e)}}
 finally{loadingAccount=false;}
}
async function reconcile(user,epoch){
 const p=uncertain;if(!p)return;
 const result=await info({type:'orderStatus',user,oid:p.cloid},'testnet');if(epoch!==accountEpoch||uncertain!==p)return;
 if(result.status==='order'){
  $('submitState').textContent='주문 상태 확인: '+result.order.status;pending(null);
 }else if(result.status==='unknownOid'&&Date.now()>p.expires+15000){
  $('submitState').textContent='만료 후 서버에 주문이 없는 것을 확인했습니다.';pending(null);
 }else $('accountStatus').textContent+=' · 제출 결과 확인 중, 새 주문 잠금';
}
$('refreshAccount').onclick=()=>loadAccount();
async function guard(r,checkMarket=true){
 if(!address||!wallet||r.user.toLowerCase()!==address.toLowerCase()||r.accountEpoch!==accountEpoch)throw Error('연결 계정이 변경됐습니다');
 if(network!=='testnet'||(checkMarket&&r.marketEpoch!==marketEpoch))throw Error('테스트넷 또는 시장 선택이 변경됐습니다');
 if(Date.now()>r.expires)throw Error('주문 확인이 만료됐습니다. 다시 확인하세요');
 const [chain,ac]=await Promise.all([provider.request({method:'eth_chainId'}),provider.request({method:'eth_accounts'})]);
 if(chain.toLowerCase()!==CHAIN||ac?.[0]?.toLowerCase()!==r.user.toLowerCase())throw Error('지갑 계정 또는 네트워크 불일치');
 if(r.accountEpoch!==accountEpoch||network!=='testnet'||Date.now()>r.expires||(checkMarket&&r.marketEpoch!==marketEpoch))throw Error('주문 확인 상태가 변경됐습니다');
}
function clientFor(r){
 const signer=wallet;
 const guardedWallet={getAddresses:()=>signer.getAddresses(),getChainId:()=>signer.getChainId(),async signTypedData(params){await guard(r,r.marketEpoch!=null);return signer.signTypedData(params)}};
 const transport={isTestnet:true,async request(endpoint,payload){
  if(endpoint!=='exchange'||!['order','cancel','updateLeverage'].includes(payload?.action?.type))throw Error('허용되지 않은 요청');
  await guard(r,r.marketEpoch!=null);
  if(payload.action.type==='order')pending({user:r.user,cloid:r.cloid,expires:r.expires});
  const result=await post('testnet','exchange',payload,15000);
  if(payload.action.type==='order'&&(result.status==='err'||(result.status==='ok'&&result.response?.type==='order'&&result.response.data?.statuses?.length===1)))pending(null);
  return result;
 }};
 return new ExchangeClient({wallet:guardedWallet,transport});
}
function makeReview(){
 if(busy)throw Error('진행 중인 요청을 기다려 주세요');
 if(network!=='testnet')throw Error('메인넷은 조회 전용입니다');
 if(!address||!wallet)throw Error('테스트넷 지갑을 먼저 연결하세요');
 if(uncertain)throw Error('이전 주문 결과를 먼저 확인하세요. 계정 새로고침 후 다시 시도하세요.');
 if(!market||!book||Date.now()-bookAt>10000)throw Error('최신 호가를 확인할 수 없습니다');
 if(!accountData||Date.now()-accountAt>30000)throw Error('계정 새로고침 후 다시 확인하세요');
 const raw=$('orderSize').value.trim();if(!numeric(raw))throw Error('올바른 수량을 입력하세요');
 const size=formatSize(raw,market.sz);if(Number(size)!==Number(raw))throw Error(`수량은 소수점 ${market.sz}자리 이내로 입력하세요`);
 const isMarket=$('orderType').value==='market';let price;
 if(isMarket){
  const slip=Number($('marketSlippage').value);if(!Number.isFinite(slip)||slip<0.1||slip>5)throw Error('가격 허용폭은 0.1~5%입니다');
  const best=Number(book.levels[buy?1:0][0]?.px);if(!(best>0))throw Error('반대편 호가가 없습니다');
  price=formatPrice(best*(buy?1+slip/100:1-slip/100),market.sz,kind);
  // A formatted sell limit must not go below the confirmed slippage floor.
  if(!buy&&Number(price)<best*(1-slip/100)){
   const step=Math.max(10**(-(8-(kind==='perp'?2:0)-market.sz)),10**(Math.floor(Math.log10(Number(price)))-4));
   price=formatPrice(Number(price)+step,market.sz,kind);
  }
 }else{
  if(!numeric($('limitPrice').value.trim()))throw Error('올바른 지정 가격을 입력하세요');
  price=formatPrice($('limitPrice').value.trim(),market.sz,kind);
  if(Number(price)!==Number($('limitPrice').value))throw Error('가격 정밀도를 줄여 주세요. 허용 가격 예: '+price);
 }
 const notional=Number(price)*Number(size);if(!Number.isFinite(notional)||notional<10)throw Error('최소 주문 명목가는 10 USDC입니다');
 const reduce=kind==='perp'&&$('reduceOnly').checked;const lev=Number($('leverage').value);
 if(kind==='spot'){
  const token=buy?'USDC':market.base;const b=accountData.spot.balances.find(x=>x.coin===token);const available=Number(b?.total||0)-Number(b?.hold||0);
  if((buy?notional:Number(size))>available)throw Error('사용 가능한 현물 테스트 잔액이 부족합니다');
 }else if(!reduce){
  if(!Number.isInteger(lev)||lev<1||lev>market.maxLeverage)throw Error('허용 레버리지를 확인하세요');
  if(notional/lev>Number(accountData.perps.withdrawable))throw Error('테스트넷 증거금이 부족합니다');
 }
 if(reduce){const pos=accountData.perps.assetPositions.find(x=>x.position.coin===market.base)?.position;const n=Number(pos?.szi||0);if(n===0||buy===(n>0)||Number(size)>Math.abs(n))throw Error('Reduce-only 방향과 포지션 수량을 확인하세요');}
 return {user:address,accountEpoch,marketEpoch,market:{...market},kind,buy,size,price,notional,reduce,lev,tif:isMarket?'Ioc':$('postOnly').checked?'Alo':'Gtc',isMarket,expires:Date.now()+60000,cloid:'0x'+Array.from(crypto.getRandomValues(new Uint8Array(16)),n=>n.toString(16).padStart(2,'0')).join('')};
}
$('reviewOrder').onclick=()=>{
 try{
  review=makeReview();const r=review;
  $('orderReview').textContent=`네트워크: Hyperliquid TESTNET\n계정: ${r.user}\n시장: ${r.market.label} · ${kind==='perp'?'무기한 선물':'현물'}\n방향: ${buy?'매수':'매도'} · ${r.isMarket?'시장가 IOC':r.tif==='Alo'?'지정가 Post-only':'지정가 GTC'}\n수량: ${r.size} ${r.market.base}\n${r.isMarket?'체결 한계 가격':'지정 가격'}: ${r.price} USDC\n명목가: ${money(r.notional)} · 수수료 별도\n${kind==='perp'?'교차 '+r.lev+'× · Reduce-only '+(r.reduce?'ON':'OFF')+'\n':''}확인 유효시간: 60초`;
  $('submitState').textContent='';$('submitOrder').hidden=false;$('submitOrder').disabled=false;$('orderDialog').showModal();
 }catch(e){window.toast(errorText(e))}
};
$('closeOrder').onclick=()=>{if(busy)return;$('orderDialog').close();review=null};
$('orderDialog').addEventListener('cancel',e=>{if(busy)e.preventDefault();else review=null});
$('submitOrder').onclick=async()=>{
 if(busy||!review)return;busy=true;$('submitOrder').disabled=true;const r=review;review=null;let leverageDone=false;
 try{
  await guard(r);if(Date.now()-bookAt>10000)throw Error('호가가 지연됐습니다. 다시 확인하세요');
  const client=clientFor(r);
  if(r.kind==='perp'&&!r.reduce){$('submitState').textContent='교차 레버리지 설정을 지갑에서 확인하세요.';await client.updateLeverage({asset:r.market.id,isCross:true,leverage:r.lev},{expiresAfter:r.expires});leverageDone=true;}
  $('submitState').textContent='주문 서명을 지갑에서 확인하세요.';
  const result=await client.order({orders:[{a:r.market.id,b:r.buy,p:r.price,s:r.size,r:r.reduce,t:{limit:{tif:r.tif}},c:r.cloid}],grouping:'na'},{expiresAfter:r.expires});
  const status=result.response?.data?.statuses?.[0];
  if(status?.error)throw Error(status.error);
  if(status?.filled)$('submitState').textContent=`테스트넷 체결 완료 · 주문 #${status.filled.oid}\n체결 수량 ${status.filled.totalSz} · 평균 가격 ${status.filled.avgPx}`;
  else if(status?.resting)$('submitState').textContent='테스트넷 지정가 주문 접수 · #'+status.resting.oid;
  else throw Error('주문 응답을 확인하지 못했습니다. 계정에서 상태를 조회하세요.');
  $('submitOrder').hidden=true;
 }catch(e){$('submitState').textContent=(uncertain?'제출 결과 미확인. 자동 재전송하지 않습니다. 계정 새로고침으로 확인하세요.\n':'주문 미완료 · ')+errorText(e)+(leverageDone?'\n레버리지 설정은 이미 반영됐을 수 있습니다.':'');}
 finally{busy=false;await loadAccount();}
};
async function cancelOrder(o){
 if(busy||!address)return;
 if(network!=='testnet')return window.toast('TESTNET으로 전환한 후 취소하세요');
 if(!confirm('테스트넷 주문 #'+o.oid+'를 취소할까요?'))return;
 busy=true;const r={user:address,accountEpoch,expires:Date.now()+60000};
 try{
  // Resolve the order's own asset instead of using whichever market is on screen.
  const [pm,sm]=await Promise.all([info({type:'meta'},'testnet'),info({type:'spotMeta'},'testnet')]);
  const pi=pm.universe.findIndex(m=>m.name===o.coin);const si=sm.universe.find(m=>m.name===o.coin);
  const asset=pi>=0?pi:si?10000+si.index:null;if(asset===null)throw Error('주문 시장을 확인할 수 없습니다');
  const result=await clientFor(r).cancel({cancels:[{a:asset,o:o.oid}]},{expiresAfter:r.expires});
  const status=result.response?.data?.statuses?.[0];if(status!=='success')throw Error(status?.error||'취소 상태 확인 필요');
  window.toast('주문 취소 완료');
 }catch(e){window.toast('취소 확인 필요 · '+errorText(e))}
 finally{busy=false;await loadAccount();}
}
setInterval(()=>{if(!document.hidden)loadBook()},3000);
setInterval(()=>{if(!document.hidden){loadChart();loadAccount()}},15000);
setInterval(()=>{if(bookAt&&Date.now()-bookAt>10000)$('feedState').textContent='호가 지연 · 주문 일시 중지'},1000);
document.addEventListener('visibilitychange',()=>{stopStream();if(!document.hidden){loadBook();loadChart();loadAccount();startStream()}});
window.addEventListener('pagehide',stopStream);
window.addEventListener('pageshow',e=>{if(e.persisted){loadBook();loadChart();loadAccount();startStream()}});
if('serviceWorker' in navigator)navigator.serviceWorker.register('./sw.js').then(r=>r.update()).catch(()=>{});
loadMarkets();
