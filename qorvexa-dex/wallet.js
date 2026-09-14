const $=id=>document.getElementById(id);
const endpoints={mainnet:'https://api.hyperliquid.xyz/info',testnet:'https://api.hyperliquid-testnet.xyz/info'};
let connected=null,viewed=null,generation=0,controller=null,loading=false;
function clear(){for(const id of ['walletTokens','walletPositions','walletActivity'])$(id).replaceChildren();$('walletValue').textContent='—';$('walletAddress').textContent=viewed||'No address selected';$('walletCopy').disabled=!viewed;}
function row(parent,title,detail){const node=document.createElement('div');node.className='pair row space';const a=document.createElement('b'),b=document.createElement('span');a.textContent=title;b.textContent=detail;b.className='muted';node.append(a,b);$(parent).append(node);}
async function refresh(){
 const request=++generation;controller?.abort();controller=new AbortController();clear();
 if(!viewed){loading=false;$('walletStatus').textContent='Connect a wallet or enter a public address to view Hyperliquid assets.';return}
 const user=viewed,network=$('walletNetwork').value,signal=controller.signal;loading=true;$('walletStatus').textContent='Loading '+network+' account…';
 const timeout=setTimeout(()=>controller?.signal===signal&&controller.abort(),12000);
 const info=async type=>{const r=await fetch(endpoints[network],{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({type,user}),signal});if(!r.ok)throw Error('Account request failed ('+r.status+')');return r.json()};
 try{
  const [spot,perps,fills]=await Promise.all([info('spotClearinghouseState'),info('clearinghouseState'),info('userFills')]);
  if(request!==generation)return;
  if(!Array.isArray(spot.balances)||!Array.isArray(perps.assetPositions)||!Array.isArray(fills)||!Number.isFinite(Number(perps.marginSummary?.accountValue)))throw Error('Invalid account response');
  $('walletValue').textContent=Number(perps.marginSummary.accountValue).toLocaleString('en-US',{maximumFractionDigits:2})+' USDC';
  for(const b of spot.balances)row('walletTokens',b.coin,String(b.total)+' · Hold '+String(b.hold??'0'));
  if(!spot.balances.length)row('walletTokens','No spot balances','On this network');
  for(const {position:p} of perps.assetPositions)row('walletPositions',p.coin,String(p.szi)+' · PnL '+String(p.unrealizedPnl)+' USDC');
  if(!perps.assetPositions.length)row('walletPositions','No open positions','');
  for(const f of fills.slice(0,20))row('walletActivity',f.coin+' · '+f.dir,String(f.sz)+' @ '+String(f.px)+' · '+new Date(f.time).toLocaleString('en-US'));
  if(!fills.length)row('walletActivity','No recent fills','');
  $('walletStatus').textContent=(connected?.toLowerCase()===user.toLowerCase()?'Connected address':'Watch-only address')+' · '+network+' · Updated '+new Date().toLocaleTimeString('en-US');
 }catch(e){if(request===generation){clear();$('walletStatus').textContent=e.name==='AbortError'?'Account request timed out. Refresh to retry.':e.message}}
 finally{clearTimeout(timeout);if(request===generation)loading=false}
}
window.addEventListener('beltrix:wallet',e=>{connected=e.detail.account||null;viewed=connected;$('walletWatch').value='';refresh()});
$('walletNetwork').onchange=refresh;$('walletRefresh').onclick=refresh;
$('walletWatchForm').onsubmit=e=>{e.preventDefault();const a=$('walletWatch').value.trim();if(!/^0x[0-9a-fA-F]{40}$/.test(a)){ $('walletStatus').textContent='Enter a valid 0x public address.';return}viewed=a;refresh()};
$('walletUseConnected').onclick=()=>{viewed=connected;$('walletWatch').value='';refresh()};
$('walletCopy').onclick=async()=>{if(!viewed)return;try{await navigator.clipboard.writeText(viewed);$('walletStatus').textContent='Address copied. Use the official deposit flow for Hyperliquid funding.'}catch{$('walletStatus').textContent='Copy unavailable. Select the address above to copy it.'}};
$('walletConnectAction').onclick=()=>$('tradeConnect').click();
$('walletTrade').onclick=()=>window.openPage('markets');
$('discoverSearch').oninput=()=>{const q=$('discoverSearch').value.toLowerCase();document.querySelectorAll('.dapp-card').forEach(x=>x.hidden=!x.textContent.toLowerCase().includes(q));$('discoverEmpty').hidden=Array.from(document.querySelectorAll('.dapp-card')).some(x=>!x.hidden)};
setInterval(()=>{if(viewed&&!loading&&!document.hidden&&$('assets').classList.contains('active'))refresh()},15000);
refresh();
