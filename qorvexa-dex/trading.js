import {ExchangeClient,HttpTransport,InfoClient} from '@nktkas/hyperliquid';
import {createWalletClient,custom} from 'viem';
import {arbitrumSepolia} from 'viem/chains';
import {makeOrder,freshMarket} from './order-validation.js';
const $=id=>document.getElementById(id),transport=new HttpTransport({isTestnet:true}),info=new InfoClient({transport});
let account=null,provider=null,market=null,client=null,busy=false,pending=null,epoch=0;
function status(s){$('tradeStatus').textContent=s}
function availability(){$('tradeReview').disabled=busy||!client||!freshMarket(market);$('tradeConnect').disabled=busy;$('tradeSubmit').disabled=busy;}
function disconnect(){epoch++;account=null;client=null;pending=null;$('tradeAccount').textContent='지갑을 연결하세요';$('tradeBalances').replaceChildren();$('tradeOrders').replaceChildren();$('tradePositions').replaceChildren();$('tradeDialog').close();availability()}
function clearPending(){pending=null;$('tradeDialog').close()}
window.addEventListener('beltrix:market',e=>{if(market?.market?.value!==e.detail.market?.value||market?.network!==e.detail.network)clearPending();market=e.detail;availability()});
async function guard(requireFresh=true){if(!client||!account||market?.network!=='testnet'||$('marketNetwork').value!=='testnet'||(requireFresh&&!freshMarket(market)))throw Error('테스트넷 연결과 최신 호가를 확인하세요');const accounts=await provider.request({method:'eth_accounts'});const chain=await provider.request({method:'eth_chainId'});if(accounts[0]?.toLowerCase()!==account.toLowerCase()||chain!=='0x66eee')throw Error('지갑 계정 또는 네트워크가 변경되었습니다');}
$('tradeConnect').onclick=async()=>{if(busy)return;busy=true;availability();try{
 if(!window.ethereum)throw Error('모바일은 호환 지갑 앱의 브라우저에서 열어주세요');
 if(provider){provider.removeListener?.('accountsChanged',disconnect);provider.removeListener?.('chainChanged',disconnect);provider.removeListener?.('disconnect',disconnect)}
 disconnect();provider=window.ethereum;const accounts=await provider.request({method:'eth_requestAccounts'});const selected=accounts[0];if(!/^0x[0-9a-f]{40}$/i.test(selected))throw Error('지갑 주소 확인 실패');
 try{await provider.request({method:'wallet_switchEthereumChain',params:[{chainId:'0x66eee'}]})}catch(e){if(e.code!==4902)throw e;await provider.request({method:'wallet_addEthereumChain',params:[{chainId:'0x66eee',chainName:'Arbitrum Sepolia',nativeCurrency:{name:'ETH',symbol:'ETH',decimals:18},rpcUrls:['https://sepolia-rollup.arbitrum.io/rpc'],blockExplorerUrls:['https://sepolia.arbiscan.io']}]})}
 if(await provider.request({method:'eth_chainId'})!=='0x66eee')throw Error('테스트넷으로 전환되지 않았습니다');
 const actual=await provider.request({method:'eth_accounts'});if(actual[0]?.toLowerCase()!==selected.toLowerCase())throw Error('지갑 계정이 변경되었습니다');account=selected;
 const wallet=createWalletClient({account,chain:arbitrumSepolia,transport:custom(provider)});
 client=new ExchangeClient({transport,wallet,isTestnet:true,defaultExpiresAfter:()=>Date.now()+30000});
 provider.on?.('accountsChanged',disconnect);provider.on?.('chainChanged',disconnect);provider.on?.('disconnect',disconnect);
 $('tradeAccount').textContent=account+' · Hyperliquid 테스트넷';status('연결 완료 · 테스트넷 잔액 필요');await refresh();
 }catch(e){status(e.shortMessage||e.message||'연결 취소')}finally{busy=false;availability()}};
$('tradeReview').onclick=async()=>{if(busy)return;try{await guard();const order=makeOrder(market.market,$('tradePrice').value.trim(),$('tradeSize').value.trim(),$('tradeSide').value==='buy',$('tradeReduce').checked,$('tradeType').value);pending={order,account,coin:market.market.value,expires:Date.now()+30000,epoch};$('tradeSummary').textContent=`${market.market.label} · ${order.b?'매수':'매도'} · 가격 ${order.p} · 수량 ${order.s} · ${order.t.limit.tif} · 축소 전용 ${order.r?'예':'아니오'} · 테스트넷 전용`; $('tradeDialog').showModal()}catch(e){status(e.message)}};
$('tradeSubmit').onclick=async()=>{if(busy||!pending)return;const p=pending;busy=true;availability();try{
 await guard();if(p.epoch!==epoch||p.account!==account||p.coin!==market.market.value||Date.now()>p.expires)throw Error('주문 확인이 만료됐습니다. 다시 검토하세요');
 const localClient=client;clearPending();status('지갑 서명·서버 응답 대기 중');const result=await localClient.order({orders:[p.order],grouping:'na'});const states=result.response?.data?.statuses||[];
 if(result.status!=='ok'||states.length!==1)throw Error('주문 응답 확인 필요. 재주문 전에 미체결을 조회하세요');
 const state=states[0];if(state.error)throw Error(state.error);if(state.resting)status('미체결 주문 접수 · 주문번호 '+state.resting.oid);else if(state.filled)status('체결 완료 · 주문번호 '+state.filled.oid+' · 평균가 '+state.filled.avgPx);else status('주문 상태 확인 필요');await refresh();
 }catch(e){status((e.shortMessage||e.message||'주문 오류')+' · 전송 오류 시 재주문 전에 미체결을 조회하세요')}finally{busy=false;availability()}};
$('tradeClose').onclick=clearPending;$('tradeDialog').addEventListener('cancel',()=>pending=null);
async function refresh(){if(!account)return;const user=account,version=epoch;const [orders,perps,spot]=await Promise.all([info.openOrders({user}),info.clearinghouseState({user}),info.spotClearinghouseState({user})]);if(version!==epoch)return;
 $('tradeBalances').textContent='선물 계정 가치: '+perps.marginSummary.accountValue+' USDC · 현물: '+spot.balances.map(b=>b.coin+' '+b.total).join(', ');
 $('tradePositions').textContent='포지션: '+(perps.assetPositions.map(x=>`${x.position.coin} ${x.position.szi} · 미실현 손익 ${x.position.unrealizedPnl}`).join(' / ')||'없음');
 const list=$('tradeOrders');list.replaceChildren();if(!orders.length)list.textContent='미체결 주문 없음';
 for(const o of orders){const row=document.createElement('div');row.className='pair';row.textContent=`${o.coin} · ${o.side} · ${o.sz} @ ${o.limitPx} `;const btn=document.createElement('button');btn.className='wallet';btn.textContent='취소';btn.onclick=async()=>{if(busy)return;busy=true;availability();try{await guard(false);if(o.coin!==market.market.value)throw Error('취소할 주문 종목을 먼저 선택하세요');const r=await client.cancel({cancels:[{a:market.market.asset,o:o.oid}]});const state=r.response?.data?.statuses?.[0];if(state!=='success')throw Error(state?.error||'취소 결과 확인 필요');status('주문 취소 완료');await refresh()}catch(e){status(e.message)}finally{busy=false;availability()}};row.append(btn);list.append(row)}
}
$('tradeRefresh').onclick=()=>refresh().catch(e=>status(e.message));setInterval(()=>{if(account&&!busy&&!document.hidden)refresh().catch(()=>{})},15000);
