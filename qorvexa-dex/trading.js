import {ExchangeClient,HttpTransport,InfoClient} from '@nktkas/hyperliquid';
import {createWalletClient,custom} from 'viem';
import {arbitrumSepolia} from 'viem/chains';
import {makeOrder,freshMarket} from './order-validation.js';
const $=id=>document.getElementById(id),transport=new HttpTransport({isTestnet:true}),info=new InfoClient({transport});
let account=null,provider=null,market=null,client=null,busy=false,pending=null,epoch=0;
function status(s){$('tradeStatus').textContent=s}
function availability(){$('tradeReview').disabled=busy||!client||!freshMarket(market);$('tradeConnect').disabled=busy;$('tradeSubmit').disabled=busy;}
function disconnect(){$('tradeConnect').textContent='Connect wallet';epoch++;account=null;client=null;pending=null;$('tradeAccount').textContent='Connect your wallet';$('tradeBalances').replaceChildren();$('tradeOrders').replaceChildren();$('tradePositions').replaceChildren();$('tradeDialog').close();availability()}
function clearPending(){pending=null;$('tradeDialog').close()}
window.addEventListener('beltrix:market',e=>{if(market?.market?.value!==e.detail.market?.value||market?.network!==e.detail.network)clearPending();market=e.detail;availability()});
async function guard(requireFresh=true){if(!client||!account||market?.network!=='testnet'||$('marketNetwork').value!=='testnet'||(requireFresh&&!freshMarket(market)))throw Error('Check the testnet connection and fresh order book');const accounts=await provider.request({method:'eth_accounts'});const chain=await provider.request({method:'eth_chainId'});if(accounts[0]?.toLowerCase()!==account.toLowerCase()||chain!=='0x66eee')throw Error('Wallet account or network changed');}
$('tradeConnect').onclick=async()=>{if(busy)return;window.openPage?.('markets');if(client){disconnect();status('Wallet disconnected');return}busy=true;availability();try{
 if(!window.ethereum)throw Error('On mobile, open this page in a compatible wallet browser');
 if(provider){provider.removeListener?.('accountsChanged',disconnect);provider.removeListener?.('chainChanged',disconnect);provider.removeListener?.('disconnect',disconnect)}
 disconnect();provider=window.ethereum;const accounts=await provider.request({method:'eth_requestAccounts'});const selected=accounts[0];if(!/^0x[0-9a-f]{40}$/i.test(selected))throw Error('Unable to verify wallet address');
 try{await provider.request({method:'wallet_switchEthereumChain',params:[{chainId:'0x66eee'}]})}catch(e){if(e.code!==4902)throw e;await provider.request({method:'wallet_addEthereumChain',params:[{chainId:'0x66eee',chainName:'Arbitrum Sepolia',nativeCurrency:{name:'ETH',symbol:'ETH',decimals:18},rpcUrls:['https://sepolia-rollup.arbitrum.io/rpc'],blockExplorerUrls:['https://sepolia.arbiscan.io']}]})}
 if(await provider.request({method:'eth_chainId'})!=='0x66eee')throw Error('Wallet did not switch to testnet');
 const actual=await provider.request({method:'eth_accounts'});if(actual[0]?.toLowerCase()!==selected.toLowerCase())throw Error('Wallet account changed');account=selected;
 const wallet=createWalletClient({account,chain:arbitrumSepolia,transport:custom(provider)});
 client=new ExchangeClient({transport,wallet,isTestnet:true,defaultExpiresAfter:()=>Date.now()+30000});
 provider.on?.('accountsChanged',disconnect);provider.on?.('chainChanged',disconnect);provider.on?.('disconnect',disconnect);
 $('tradeConnect').textContent=account.slice(0,6)+'…'+account.slice(-4)+' ×';$('tradeAccount').textContent=account+' · Hyperliquid testnet';status('Connected · Testnet funds required');await refresh();
 }catch(e){status(e.shortMessage||e.message||'Connection cancelled')}finally{busy=false;availability()}};
$('tradeReview').onclick=async()=>{if(busy)return;try{await guard();const order=makeOrder(market.market,$('tradePrice').value.trim(),$('tradeSize').value.trim(),$('tradeSide').value==='buy',$('tradeReduce').checked,$('tradeType').value);pending={order,account,coin:market.market.value,expires:Date.now()+30000,epoch};$('tradeSummary').textContent=`${market.market.label} · ${order.b?'Buy':'Sell'} · Price ${order.p} · Size ${order.s} · ${order.t.limit.tif} · Reduce only ${order.r?'Yes':'No'} · Testnet only`; $('tradeDialog').showModal()}catch(e){status(e.message)}};
$('tradeSubmit').onclick=async()=>{if(busy||!pending)return;const p=pending;busy=true;availability();try{
 await guard();if(p.epoch!==epoch||p.account!==account||p.coin!==market.market.value||Date.now()>p.expires)throw Error('Order review expired. Review again');
 const localClient=client;clearPending();status('Waiting for wallet signature and server response');const result=await localClient.order({orders:[p.order],grouping:'na'});const states=result.response?.data?.statuses||[];
 if(result.status!=='ok'||states.length!==1)throw Error('Unconfirmed response. Check open orders before retrying');
 const state=states[0];if(state.error)throw Error(state.error);if(state.resting)status('Order placed · ID '+state.resting.oid);else if(state.filled)status('Order filled · ID '+state.filled.oid+' · Average price '+state.filled.avgPx);else status('Check order status');await refresh();
 }catch(e){status((e.shortMessage||e.message||'Order error')+' · If submission failed, check open orders before retrying')}finally{busy=false;availability()}};
$('tradeClose').onclick=clearPending;$('tradeDialog').addEventListener('cancel',()=>pending=null);
async function refresh(){if(!account)return;const user=account,version=epoch;const [orders,perps,spot]=await Promise.all([info.openOrders({user}),info.clearinghouseState({user}),info.spotClearinghouseState({user})]);if(version!==epoch)return;
 $('tradeBalances').textContent='Perpetual account value: '+perps.marginSummary.accountValue+' USDC · Spot: '+spot.balances.map(b=>b.coin+' '+b.total).join(', ');
 $('tradePositions').textContent='Positions: '+(perps.assetPositions.map(x=>`${x.position.coin} ${x.position.szi} · Unrealized PnL ${x.position.unrealizedPnl}`).join(' / ')||'None');
 const list=$('tradeOrders');list.replaceChildren();if(!orders.length)list.textContent='No open orders';
 for(const o of orders){const row=document.createElement('div');row.className='pair';row.textContent=`${o.coin} · ${o.side} · ${o.sz} @ ${o.limitPx} `;const btn=document.createElement('button');btn.className='wallet';btn.textContent='Cancel';btn.onclick=async()=>{if(busy)return;busy=true;availability();try{await guard(false);if(o.coin!==market.market.value)throw Error('Select the order market before cancelling');const r=await client.cancel({cancels:[{a:market.market.asset,o:o.oid}]});const state=r.response?.data?.statuses?.[0];if(state!=='success')throw Error(state?.error||'Check cancellation status');status('Order cancelled');await refresh()}catch(e){status(e.message)}finally{busy=false;availability()}};row.append(btn);list.append(row)}
}
$('tradeRefresh').onclick=()=>refresh().catch(e=>status(e.message));setInterval(()=>{if(account&&!busy&&!document.hidden)refresh().catch(()=>{})},15000);
