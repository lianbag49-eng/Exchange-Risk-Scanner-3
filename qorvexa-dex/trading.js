import {ExchangeClient,HttpTransport,InfoClient} from '@nktkas/hyperliquid';
import {createWalletClient,custom} from 'viem';
import {arbitrumSepolia} from 'viem/chains';
import {makeOrder,freshMarket} from './order-validation.js';
import {boundedPrice,leverageRequest,finite,fundingCashflow,fitsPosition} from './terminal-core.js';
const $=id=>document.getElementById(id),transport=new HttpTransport({isTestnet:true}),info=new InfoClient({transport});
let account=null,provider=null,market=null,client=null,busy=false,pending=null,epoch=0,active=null,positions=[],activeAt=0,refreshing=false;
const JOURNAL='beltrix-trade-submissions-v1';
let journal={};try{journal=JSON.parse(localStorage.getItem(JOURNAL)||'{}');if(!journal||Array.isArray(journal)||typeof journal!=='object')journal={}}catch{}
function status(text){$('tradeStatus').textContent=String(text)}
const errorText=e=>{for(let x=e;x;x=x.cause)if(x.code===4001)return 'Wallet request rejected. Nothing was submitted.';return String(e?.shortMessage||e?.message||'Request failed').slice(0,400)};
const fmt=(v,d=4)=>finite(v)?Number(v).toLocaleString('en-US',{maximumFractionDigits:d}):'—';
const key=()=>account?.toLowerCase();
function unresolved(){return journal[key()]||null}
function saveLock(value,user=account){const k=user.toLowerCase();if(value)journal[k]=value;else delete journal[k];localStorage.setItem(JOURNAL,JSON.stringify(journal));}
function availability(){
 const locked=!!unresolved();$('tradeReview').disabled=busy||!client||!freshMarket(market)||locked;$('tradeConnect').disabled=busy;$('walletProvider').disabled=busy;$('tradeSubmit').disabled=busy;
 $('tradeLeverageReview').disabled=busy||!client||!freshMarket(market)||market?.market?.spot||!Number.isInteger(market?.market?.maxLeverage)||locked;
 $('tradeReconcile').hidden=!locked;$('tradeReconcile').disabled=busy;
 for(const id of ['tradeType','tradeSide','tradeSize','tradePrice','tradeTrigger','tradeSlippage','tradeLeverage','tradeMarginMode'])$(id).disabled=busy;
 $('tradeReduce').disabled=busy||market?.market?.spot||['Stop','TakeProfit'].includes($('tradeType').value);
 $('tradeModeNote').textContent=market?.network==='testnet'?'Orders execute on Hyperliquid testnet.':'Mainnet data is view-only. Select Testnet to trade.';
}
function clearPending(){pending=null;$('tradeDialog').close()}
function clearAccount(){active=null;activeAt=0;positions=[];for(const id of ['tradeBalances','tradeOrders','tradePositions','tradeFills','tradeFundingHistory'])$(id).textContent='No account data loaded';$('tradeAccountStatus').textContent='Connect your testnet trading wallet to load your account.';updateTicket()}
function disconnect(){window.dispatchEvent(new CustomEvent('beltrix:wallet',{detail:{account:null}}));$('tradeConnect').textContent='Connect wallet';epoch++;account=null;client=null;clearPending();$('tradeAccount').textContent='Connect your wallet';clearAccount();availability()}
function currentPosition(){return positions.find(p=>p.coin===market?.market?.value)}
function formKey(){return ['tradeType','tradeSide','tradeSize','tradePrice','tradeTrigger','tradeSlippage','tradeReduce'].map(id=>$(id).type==='checkbox'?$(id).checked:$(id).value).join('|')}
function updateTicket(reset=false){
 const meta=market?.market,spot=!!meta?.spot,type=$('tradeType').value,trigger=['Stop','TakeProfit'].includes(type),isMarket=type==='Market'||trigger;
 $('tradePriceField').hidden=isMarket;$('tradeTriggerField').hidden=!trigger;$('tradeSlippageField').hidden=!isMarket;$('tradeLeveragePanel').hidden=spot;
 for(const o of $('tradeType').options)if(['Stop','TakeProfit'].includes(o.value))o.disabled=spot;
 if(spot&&trigger){$('tradeType').value='Gtc';return updateTicket(reset)}
 if(spot)$('tradeReduce').checked=false;else if(trigger)$('tradeReduce').checked=true;
 $('tradeSizeUnit').textContent=meta?`(${meta.spot?meta.label.split('/')[0]:meta.value})`:'';
 $('tradeLeverageMax').textContent=Number.isInteger(meta?.maxLeverage)?`Allowed 1–${meta.maxLeverage}x · ${meta.onlyIsolated?'Isolated only':'Cross or isolated'}`:'Maximum leverage unavailable';$('tradeLeverage').max=meta?.maxLeverage||1;
 $('tradeMarginMode').querySelector('[value=cross]').disabled=!!meta?.onlyIsolated;if(meta?.onlyIsolated)$('tradeMarginMode').value='isolated';
 if(reset){$('tradeLeverage').value=active?.leverage?.value||1;$('tradeMarginMode').value=active?.leverage?.type||(meta?.onlyIsolated?'isolated':'cross')}
 $('tradeLeverageCurrent').textContent=active?.leverage?`${active.leverage.type} · ${active.leverage.value}x`:'Not loaded';
 const side=$('tradeSide').value==='buy',reference=type==='Market'?market?.book?.levels?.[side?1:0]?.[0]?.px:trigger?$('tradeTrigger').value:$('tradePrice').value;
 const ntl=Number(reference)*Number($('tradeSize').value);$('tradeNotional').textContent=ntl>0?fmt(ntl,2)+' USDC':'—';
 $('tradeMarginEstimate').textContent=spot?'Spot · no leverage':ntl>0&&active?.leverage?fmt(ntl/active.leverage.value,2)+' USDC':'—';
 $('tradeAvailable').textContent=active?.availableToTrade?fmt(active.availableToTrade[side?0:1],2)+' USDC':'—';
 const cash=market?.contextReceived&&Date.now()-market.contextReceived<90000?fundingCashflow(currentPosition()?.szi,market.context?.oraclePx,market.context?.funding):null;
 $('tradeFundingEstimate').textContent=spot?'Not applicable':cash===null?'—':`${cash>=0?'Receive':'Pay'} ${fmt(Math.abs(cash),4)} USDC`;
 availability();
}
window.addEventListener('beltrix:market',e=>{
 const changed=market?.market?.value!==e.detail.market?.value||market?.network!==e.detail.network;market=e.detail;
 if(changed){clearPending();active=null;activeAt=0;positions=[];updateTicket(true);if(account)refresh().catch(e=>status(errorText(e)))}else updateTicket();
});
window.addEventListener('beltrix:book-price',e=>{if(busy||e.detail.coin!==market?.market?.value||e.detail.network!==market?.network)return;clearPending();$('tradeType').value='Gtc';$('tradePrice').value=e.detail.price;updateTicket();});
async function guard(requireFresh=true){if(!client||!account||market?.network!=='testnet'||$('marketNetwork').value!=='testnet'||(requireFresh&&!freshMarket(market)))throw Error('Check the testnet connection and fresh order book');const user=account,version=epoch,p=provider;const accounts=await p.request({method:'eth_accounts'});const chain=await p.request({method:'eth_chainId'});if(version!==epoch||p!==provider||user!==account||accounts[0]?.toLowerCase()!==user.toLowerCase()||chain!=='0x66eee')throw Error('Wallet account or network changed');}
$('tradeConnect').onclick=async()=>{if(busy)return;window.openPage?.('markets');if(client){disconnect();status('Wallet disconnected');return}busy=true;availability();try{
 const chosen=$('walletProvider').value==='okx'?window.okxwallet:(window.beltrixWallet?.provider||window.ethereum);
 if(!chosen?.request)throw Error($('walletProvider').value==='okx'?'Open this page in the OKX Wallet browser or install the OKX Wallet extension.':'Open this page in a compatible wallet browser.');
 if(provider){provider.removeListener?.('accountsChanged',disconnect);provider.removeListener?.('chainChanged',disconnect);provider.removeListener?.('disconnect',disconnect)}
 disconnect();provider=chosen;const accounts=await provider.request({method:'eth_requestAccounts'});const selected=accounts[0];if(!/^0x[0-9a-f]{40}$/i.test(selected))throw Error('Unable to verify wallet address');
 try{await provider.request({method:'wallet_switchEthereumChain',params:[{chainId:'0x66eee'}]})}catch(e){if(e.code!==4902)throw e;await provider.request({method:'wallet_addEthereumChain',params:[{chainId:'0x66eee',chainName:'Arbitrum Sepolia',nativeCurrency:{name:'ETH',symbol:'ETH',decimals:18},rpcUrls:['https://sepolia-rollup.arbitrum.io/rpc'],blockExplorerUrls:['https://sepolia.arbiscan.io']}]})}
 if(await provider.request({method:'eth_chainId'})!=='0x66eee')throw Error('Wallet did not switch to testnet');
 const actual=await provider.request({method:'eth_accounts'});if(actual[0]?.toLowerCase()!==selected.toLowerCase())throw Error('Wallet account changed');account=selected;
 const wallet=createWalletClient({account,chain:arbitrumSepolia,transport:custom(provider)});
 client=new ExchangeClient({transport,wallet,isTestnet:true,defaultExpiresAfter:()=>Date.now()+30000});
 provider.on?.('accountsChanged',disconnect);provider.on?.('chainChanged',disconnect);provider.on?.('disconnect',disconnect);
 $('tradeConnect').textContent=account.slice(0,6)+'…'+account.slice(-4)+' ×';$('tradeAccount').textContent=account+' · Hyperliquid testnet';window.dispatchEvent(new CustomEvent('beltrix:wallet',{detail:{account,provider,chainId:421614}}));status('Connected · Testnet funds required');await refresh();
 }catch(e){status(e.shortMessage||e.message||'Connection cancelled')}finally{busy=false;availability()}};
function reviewDialog(p,text){pending={...p,account,coin:market.market.value,asset:market.market.asset,expires:Date.now()+30000,epoch,form:formKey()};$('tradeDialog').querySelector('h3').textContent=p.kind==='leverage'?'Confirm margin & leverage':'Confirm testnet order';$('tradeSummary').textContent=text+'\nHyperliquid TESTNET · Review expires in 30 seconds';$('tradeDialog').showModal();}
async function loadActive(){const user=account,coin=market?.market?.value,version=epoch;if(!user||!coin||market.network!=='testnet'||market.market.spot)return null;const a=await info.activeAssetData({user,coin});if(version!==epoch||user!==account||coin!==market?.market?.value)throw Error('Account or market changed');if(a?.coin!==coin||!a.leverage||!['cross','isolated'].includes(a.leverage.type)||!Number.isInteger(a.leverage.value))throw Error('Current leverage unavailable');active=a;activeAt=Date.now();return a;}
async function loadPosition(){const user=account,coin=market?.market?.value,version=epoch;const data=await info.clearinghouseState({user});if(version!==epoch||user!==account||coin!==market?.market?.value)throw Error('Account or market changed');positions=(data.assetPositions||[]).map(x=>x.position);return currentPosition();}
function validateReduce(order,pos){if(!order.r)return;if(!pos||!fitsPosition(order.s,pos.szi,order.b))throw Error('Reduce-only size and side must match your existing position');}
$('tradeReview').onclick=async()=>{
 if(busy)return;busy=true;availability();try{
  await guard();if(unresolved())throw Error('Resolve the previous submission first');const reviewEpoch=epoch,reviewUser=account,reviewCoin=market.market.value,reviewForm=formKey();const meta=market.market,type=$('tradeType').value,buy=$('tradeSide').value==='buy',trigger=['Stop','TakeProfit'].includes(type);let price=$('tradePrice').value.trim(),tif=type;
  if(type==='Market'){const reference=market.book.levels[buy?1:0]?.[0]?.px;if(!reference)throw Error('No executable liquidity');price=boundedPrice(meta,reference,buy,$('tradeSlippage').value.trim());if(buy?Number(price)<Number(reference):Number(price)>Number(reference))throw Error('Slippage is smaller than a price tick');tif='Ioc'}
  if(trigger){if(meta.spot)throw Error('TP/SL is perpetual-only');price=boundedPrice(meta,$('tradeTrigger').value.trim(),buy,$('tradeSlippage').value.trim());tif='Ioc'}
  const order=makeOrder(meta,price,$('tradeSize').value.trim(),buy,$('tradeReduce').checked,tif);
  let leverage=null;if(!meta.spot){const a=await loadActive();leverage={...a.leverage};if(!leverage)throw Error('Read leverage before ordering')}
  if(order.r)validateReduce(order,await loadPosition());
  if(trigger){const triggerPx=$('tradeTrigger').value.trim();makeOrder(meta,triggerPx,order.s,buy,true,'Gtc');const mark=Number(active?.markPx||market.context?.markPx);if(!(mark>0))throw Error('Current mark price unavailable');const above=type==='TakeProfit'?!buy:buy;if(above?Number(triggerPx)<=mark:Number(triggerPx)>=mark)throw Error('Trigger price is on the wrong side of the current mark');order.t={trigger:{isMarket:true,triggerPx,tpsl:type==='Stop'?'sl':'tp'}};}
  await guard();if(reviewEpoch!==epoch||reviewUser!==account||reviewCoin!==market.market.value||reviewForm!==formKey())throw Error('Selection changed. Review again');
  const cloid='0x'+Array.from(crypto.getRandomValues(new Uint8Array(16)),x=>x.toString(16).padStart(2,'0')).join('');order.c=cloid;
  reviewDialog({kind:'order',order,leverage},`${meta.label}\n${buy?'Buy / Long':'Sell / Short'} · ${type}\n${type==='Market'||trigger?'Execution price bound':'Limit price'}: ${price}\nSize: ${order.s}${trigger?'\nTrigger: '+order.t.trigger.triggerPx:''}\nReduce only: ${order.r?'Yes':'No'}${leverage?'\nMargin: '+leverage.type+' · '+leverage.value+'x':''}${type==='Market'?'\nImmediate or cancel · Unfilled quantity is cancelled.':''}${trigger?'\nStandalone trigger uses the mark price. It does not cancel another TP/SL order. Slippage bounds may prevent a fill.':''}`);
 }catch(e){status(errorText(e))}finally{busy=false;availability()}
};
$('tradeLeverageReview').onclick=async()=>{if(busy)return;busy=true;availability();try{await guard();if(unresolved())throw Error('Resolve the previous submission first');const reviewCoin=market.market.value,reviewEpoch=epoch;const request=leverageRequest(market.market,$('tradeLeverage').value,$('tradeMarginMode').value);await loadActive();await guard();if(reviewCoin!==market.market.value||reviewEpoch!==epoch)throw Error('Selection changed. Review again');reviewDialog({kind:'leverage',request,previous:{...active.leverage}},`${market.market.label}\nCurrent: ${active.leverage.type} · ${active.leverage.value}x\nNew: ${request.isCross?'Cross':'Isolated'} · ${request.leverage}x\nThis changes margin settings for this market, including an existing position. No order is placed.`)}catch(e){status(errorText(e))}finally{busy=false;availability()}};
function explicitRejection(e){for(let x=e;x;x=x.cause)if(x.code===4001)return true;return e?.name==='ApiRequestError'&&(e.response?.status==='err'||e.response?.response?.data?.statuses?.[0]?.error)}
$('tradeSubmit').onclick=async()=>{
 if(busy||!pending)return;const p=pending;busy=true;availability();let submitted=false;
 try{await guard();if(p.epoch!==epoch||p.account!==account||p.coin!==market.market.value||p.asset!==market.market.asset||Date.now()>p.expires||(p.kind==='order'&&p.form!==formKey()))throw Error('Order review expired or changed. Review again');
  const localClient=client;if(p.kind==='leverage'){
   const a=await loadActive();if(a.leverage.type!==p.previous.type||a.leverage.value!==p.previous.value)throw Error('Leverage changed. Review again');await guard();if(p.coin!==market.market.value||p.epoch!==epoch||p.account!==account||Date.now()>p.expires)throw Error('Selection changed. Review again');const request=leverageRequest(market.market,p.request.leverage,p.request.isCross?'cross':'isolated');clearPending();status('Waiting for margin-setting signature');const r=await localClient.updateLeverage(request);if(r.status!=='ok')throw Error('Leverage update unconfirmed');await loadActive();updateTicket(true);status(`Applied ${active.leverage.type} ${active.leverage.value}x`);return;
  }
  if(unresolved())throw Error('Resolve the previous submission first');
  if(p.leverage){const a=await loadActive();if(a.leverage.type!==p.leverage.type||a.leverage.value!==p.leverage.value)throw Error('Leverage changed. Review again');}
  if(p.order.r)validateReduce(p.order,await loadPosition());await guard();if(p.epoch!==epoch||p.account!==account||p.coin!==market.market.value||p.asset!==market.market.asset||Date.now()>p.expires||p.form!==formKey())throw Error('Selection changed. Review again');
  saveLock({cloid:p.order.c,coin:p.coin,created:Date.now()},p.account);submitted=true;clearPending();status('Waiting for wallet signature and server response');
  const r=await localClient.order({orders:[p.order],grouping:'na'});const states=r.response?.data?.statuses||[];if(r.status!=='ok'||states.length!==1)throw Error('Unconfirmed order response');const state=states[0];if(state.error){saveLock(null,p.account);throw Error(state.error)}
  if(state.resting){saveLock(null,p.account);status('Order placed · ID '+state.resting.oid)}else if(state.filled){saveLock(null,p.account);status(`Order filled · ${state.filled.totalSz} · ID ${state.filled.oid} · Average price ${state.filled.avgPx}`)}else if(state==='waitingForTrigger'){saveLock(null,p.account);status('Trigger order accepted')}else throw Error('Order response needs reconciliation');await refresh();
 }catch(e){if(submitted&&explicitRejection(e))saveLock(null,p.account);status(errorText(e)+(submitted&&journal[p.account.toLowerCase()]?' · Submission unresolved. Check status before another order.':''))}finally{busy=false;availability()}
};
$('tradeReconcile').onclick=async()=>{if(busy||!unresolved())return;busy=true;availability();try{await guard(false);const user=account,p=unresolved();const r=await info.orderStatus({user,oid:p.cloid});if(r.status==='order'&&r.order?.order?.coin===p.coin&&r.order.order.cloid===p.cloid){saveLock(null,user);status('Verified order status: '+r.order.status);await refresh()}else status('Not yet verified. Keep the submission locked and check the testnet venue. No retry was sent.')}catch(e){status(errorText(e))}finally{busy=false;availability()}};
$('tradeClose').onclick=clearPending;$('tradeDialog').addEventListener('cancel',()=>pending=null);
for(const id of ['tradeType','tradeSide','tradeSize','tradePrice','tradeTrigger','tradeSlippage','tradeReduce','tradeLeverage','tradeMarginMode'])$(id).addEventListener('input',()=>{clearPending();updateTicket()});
function table(target,headers,rows){const root=$(target);root.replaceChildren();if(!rows.length){root.className='terminal-empty';root.textContent='No records';return}root.className='terminal-table-wrap';const t=document.createElement('table');t.className='terminal-table';const head=t.createTHead().insertRow();for(const title of headers){const th=document.createElement('th');th.textContent=title;head.append(th)}const body=t.createTBody();for(const row of rows){const tr=body.insertRow();for(const value of row){const td=tr.insertCell();if(value instanceof Node)td.append(value);else td.textContent=String(value??'—')}}root.append(t)}
function button(label,fn){const b=document.createElement('button');b.className='wallet';b.textContent=label;b.onclick=()=>{if(!busy)Promise.resolve(fn()).catch(e=>status(errorText(e)))};return b}
function positionAction(pos,type){if(market?.network!=='testnet'||market.market?.value!==pos.coin)throw Error('Select '+pos.coin+' on testnet first');$('tradeSide').value=Number(pos.szi)>0?'sell':'buy';$('tradeType').value=type;$('tradeSize').value=String(pos.szi).replace(/^-/,'');$('tradeReduce').checked=true;clearPending();updateTicket();$('tradeSize').scrollIntoView({block:'center',behavior:'smooth'});status(type==='Market'?'Review the reduce-only close order before signing.':'Enter the trigger price, then review.');}
async function refresh(){
 if(!account||refreshing)return;if(market?.network!=='testnet'){clearAccount();$('tradeAccountStatus').textContent='Account panels are for testnet. Select Testnet to view balances and positions.';return}
 refreshing=true;const user=account,version=epoch,coin=market?.market?.value;const valid=()=>version===epoch&&account===user&&market?.network==='testnet'&&coin===market?.market?.value;
 try{
 const jobs=[info.frontendOpenOrders({user}),info.clearinghouseState({user}),info.spotClearinghouseState({user}),info.userFills({user}),info.userFunding({user,startTime:Date.now()-7*86400000}),market?.market?.spot?Promise.resolve(null):info.activeAssetData({user,coin})];
 const all=await Promise.allSettled(jobs);if(!valid())return;const data=i=>all[i].status==='fulfilled'?all[i].value:null;const orders=data(0),perps=data(1),spot=data(2),fills=data(3),funding=data(4),asset=data(5);
 if(asset?.coin===coin&&asset.leverage){active=asset;activeAt=Date.now();}else{active=null;activeAt=0}
 if(perps?.assetPositions){positions=perps.assetPositions.map(x=>x.position).filter(x=>Number(x.szi)!==0);table('tradePositions',['Market','Size','Entry','Mark','Liquidation','Margin','Unrealized PnL','Funding since open','Actions'],positions.map(p=>{const buttons=document.createElement('div');for(const [label,type] of [['Close','Market'],['TP','TakeProfit'],['SL','Stop']])buttons.append(button(label,()=>positionAction(p,type)));return [p.coin,p.szi,p.entryPx,p.coin===coin?fmt(market.context?.markPx):'—',p.liquidationPx??'—',`${p.leverage?.type||'—'} ${p.leverage?.value||'—'}x · ${fmt(p.marginUsed,2)} USDC`,fmt(p.unrealizedPnl,2),fmt(p.cumFunding?.sinceOpen,4),buttons]}))}else{$('tradePositions').textContent='Position data unavailable';positions=[]}
 if(Array.isArray(orders))table('tradeOrders',['Market','Side','Size','Limit price','Trigger','Order ID','Action'],orders.map(o=>[o.coin,o.side==='B'?'Buy':'Sell',o.sz,o.limitPx,o.isTrigger?o.triggerCondition||o.triggerPx:'—',o.oid,button('Cancel',async()=>{busy=true;availability();try{await guard(false);if(o.coin!==market.market.value)throw Error('Select '+o.coin+' before cancelling');const r=await client.cancel({cancels:[{a:market.market.asset,o:o.oid}]});if(r.response?.data?.statuses?.[0]!=='success')throw Error('Cancellation unconfirmed');status('Order cancelled');await refresh()}finally{busy=false;availability()}})]));else $('tradeOrders').textContent='Open orders unavailable';
 if(Array.isArray(fills))table('tradeFills',['Time','Market','Direction','Price','Size','Fee','Closed PnL'],fills.slice(0,100).map(f=>[new Date(f.time).toLocaleString('en-US'),f.coin,f.dir||f.side,f.px,f.sz,`${f.fee} ${f.feeToken||'USDC'}`,f.closedPnl]));else $('tradeFills').textContent='Trade history unavailable';
 if(Array.isArray(funding))table('tradeFundingHistory',['Time','Market','Rate','Position size','Payment (USDC)'],funding.slice(-100).reverse().map(f=>[new Date(f.time).toLocaleString('en-US'),f.delta?.coin,finite(f.delta?.fundingRate)?(Number(f.delta.fundingRate)*100).toFixed(4)+'%':'—',f.delta?.szi,finite(f.delta?.usdc)?`${Number(f.delta.usdc)>=0?'+':''}${f.delta.usdc}`:'—']));else $('tradeFundingHistory').textContent='Funding history unavailable';
 table('tradeBalances',['Account / asset','Balance','Available'],[['Perpetual equity',perps?.marginSummary?.accountValue??'Unavailable',perps?.withdrawable??'—'],...(spot?.balances||[]).map(b=>[b.coin,b.total,finite(b.total)&&finite(b.hold)?fmt(Number(b.total)-Number(b.hold),8):'—'])]);
 $('tradeAccountStatus').textContent=`Hyperliquid testnet · Updated ${new Date().toLocaleTimeString('en-US')} · Latest 100 fills / funding rows${all.some(x=>x.status==='rejected')?' · Some sources unavailable':''}`;updateTicket();
 }finally{refreshing=false}
}
$('tradeRefresh').onclick=()=>refresh().catch(e=>status(errorText(e)));setInterval(()=>{if(account&&!busy&&!document.hidden)refresh().catch(()=>{$('tradeAccountStatus').textContent='Account refresh failed. Shown data may be stale.'})},15000);
$('walletProvider').onchange=()=>{if(busy)return;disconnect();status('Wallet provider changed. Connect to continue.')};
for(const tab of document.querySelectorAll('[data-account-tab]'))tab.onclick=()=>{for(const b of document.querySelectorAll('[data-account-tab]')){const selected=b===tab;b.setAttribute('aria-selected',String(selected));$('account-'+b.dataset.accountTab).hidden=!selected}};
updateTicket();

for(const b of document.querySelectorAll('[data-terminal-jump]'))b.onclick=()=>document.querySelector('.'+b.dataset.terminalJump).scrollIntoView({block:'start',behavior:'smooth'});
