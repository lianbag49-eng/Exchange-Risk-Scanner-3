import {createPublicClient,http,custom,erc20Abi,formatUnits,toHex,parseAbiItem,encodeEventTopics} from 'viem';
import {mainnet,base,arbitrum,arbitrumSepolia,optimism,polygon,bsc,sepolia} from 'viem/chains';
import {address,same,normalizeHistory,mergeHistory,cleanText,hashOK} from './wallet-core.js';

export const NETWORKS=[
 {chain:mainnet,name:'Ethereum',symbol:'ETH',color:'#7885f4',rpc:'https://eth.drpc.org',explorer:'https://eth.blockscout.com',route:true,coin:'ethereum'},
 {chain:arbitrum,name:'Arbitrum',symbol:'ETH',color:'#37a9ef',rpc:'https://arb1.arbitrum.io/rpc',explorer:'https://arbitrum.blockscout.com',coin:'ethereum'},
 {chain:base,name:'Base',symbol:'ETH',color:'#3067fc',rpc:'https://mainnet.base.org',explorer:'https://base.blockscout.com',coin:'ethereum'},
 {chain:optimism,name:'Optimism',symbol:'ETH',color:'#f34d5e',rpc:'https://mainnet.optimism.io',explorer:'https://optimism.blockscout.com',coin:'ethereum'},
 {chain:bsc,name:'BNB Smart Chain (BEP20)',symbol:'BNB',color:'#eac347',rpc:'https://bsc-dataseed.bnbchain.org',explorer:'https://bscscan.com',noBlockscout:true,coin:'binancecoin'},
 {chain:polygon,name:'Polygon',symbol:'POL',color:'#9c6bff',rpc:'https://polygon.drpc.org',explorer:'https://polygon.blockscout.com',coin:'matic-network'},
 {chain:sepolia,name:'Sepolia',symbol:'ETH',color:'#8c94b5',rpc:'https://rpc.sepolia.org',explorer:'https://eth-sepolia.blockscout.com',testnet:true,coin:null},
 {chain:arbitrumSepolia,name:'Arbitrum Sepolia',symbol:'ETH',color:'#718aa8',rpc:'https://sepolia-rollup.arbitrum.io/rpc',explorer:'https://sepolia.arbiscan.io',noBlockscout:true,testnet:true,coin:null}
];
export const network=id=>NETWORKS.find(n=>n.chain.id===Number(id))||NETWORKS[0];
export const DAPPS=[
 {id:'hyperliquid',name:'Hyperliquid',tag:'Perpetuals & spot',category:'Trade',url:'https://app.hyperliquid.xyz/',color:'#7de7c4',letter:'H',description:'Open the official Hyperliquid trading application.'},
 {id:'uniswap',name:'Uniswap',tag:'Swap tokens',category:'Trade',url:'https://app.uniswap.org/',color:'#ff6bba',letter:'U',description:'Get an on-chain swap quote in the official Uniswap application.'},
 {id:'aave',name:'Aave',tag:'Supply & borrow',category:'DeFi',url:'https://app.aave.com/',color:'#a695fb',letter:'A',description:'Explore lending markets. Rates and collateral requirements vary by market.'},
 {id:'lido',name:'Lido',tag:'Liquid staking',category:'DeFi',url:'https://stake.lido.fi/',color:'#62a8ff',letter:'L',description:'View Ethereum staking and withdrawal options in the official Lido application.'},
 {id:'compound',name:'Compound',tag:'Lending markets',category:'DeFi',url:'https://app.compound.finance/',color:'#43d7a6',letter:'C',description:'View supply and borrow markets in the official Compound application.'},
 {id:'across',name:'Across',tag:'Bridge networks',category:'Bridge',url:'https://app.across.to/',color:'#79e8cd',letter:'↗',description:'Review destination network, token, fees and bridge completion time before signing.'},
 {id:'revoke',name:'Revoke.cash',tag:'Approval manager',category:'Security',url:'https://revoke.cash/',color:'#f9ad59',letter:'R',description:'Inspect a wider indexed view of approvals in the official Revoke.cash application.'},
 {id:'okx',name:'OKX Wallet',tag:'Web3 discovery',category:'Explore',url:'https://web3.okx.com/',color:'#c7ff35',letter:'O',description:'Open OKX Web3. BELTRIX is an independent application.'}
];
export async function fetchJSON(url,options={}){
 const controller=new AbortController(),timer=setTimeout(()=>controller.abort(),15000);
 try{const r=await fetch(url,{...options,signal:controller.signal});if(!r.ok)throw Error(`Data provider returned ${r.status}. Try again later.`);return await r.json();}
 finally{clearTimeout(timer);}
}
export function readClient(net,provider=null){return createPublicClient({chain:net.chain,transport:provider?custom(provider,{retryCount:0}):http(net.rpc,{retryCount:0,timeout:12000})});}
const uint=v=>{if(typeof v!=='string'||!/^\d+$/.test(v))throw Error('Invalid balance response');return BigInt(v)};
export function nativeToken(net){return {address:'native',name:net.chain.nativeCurrency.name,symbol:net.symbol,decimals:18,raw:null,price:null,verified:true};}
export async function readToken(client,contract,owner){
 const token=address(contract);
 const [decimals,symbol,name,balance]=await Promise.all([
  client.readContract({address:token,abi:erc20Abi,functionName:'decimals'}),client.readContract({address:token,abi:erc20Abi,functionName:'symbol'}),client.readContract({address:token,abi:erc20Abi,functionName:'name'}),client.readContract({address:token,abi:erc20Abi,functionName:'balanceOf',args:[owner]})]);
 if(!Number.isInteger(decimals)||decimals<0||decimals>36)throw Error('Token precision is not supported.');
 return {address:token,name:cleanText(name),symbol:cleanText(symbol,24),decimals,raw:balance.toString(),price:null,verified:false};
}
export async function indexedTokens(net,owner){
 if(net.route){
  const response=await fetchJSON(`https://api.routescan.io/v2/network/mainnet/evm/${net.chain.id}/address/${owner}/erc20-holdings?limit=100`);
  if(!Array.isArray(response.items))throw Error('Token holdings index unavailable.');
  const tokens=response.items.slice(0,100).flatMap(x=>{try{const decimals=Number(x.tokenDecimals);if(!Number.isInteger(decimals)||decimals<0||decimals>36)return [];return [{address:address(x.tokenAddress),name:cleanText(x.tokenName),symbol:cleanText(x.tokenSymbol,24),decimals,raw:uint(x.tokenQuantity).toString(),price:null,verified:false}];}catch{return []}});
  tokens.partial=!!response.links?.next||response.items.length>=100;return tokens;
 }
 if(net.noBlockscout)throw Error('Automatic token discovery is unavailable on this network. Import a token by contract address.');
 const rows=await fetchJSON(net.explorer+'/api/v2/addresses/'+owner+'/token-balances');
 if(!Array.isArray(rows))throw Error('Token index response unavailable.');
 return rows.filter(x=>x.token?.type==='ERC-20').slice(0,200).flatMap(x=>{
  try{const decimals=Number(x.token.decimals);if(!Number.isInteger(decimals)||decimals<0||decimals>36)return [];
  return [{address:address(x.token.address_hash||x.token.address),name:cleanText(x.token.name),symbol:cleanText(x.token.symbol,24),decimals,raw:uint(x.value).toString(),price:Number(x.token.exchange_rate)>0?Number(x.token.exchange_rate):null,verified:false}];}catch{return []}
 });
}
export async function nativeBalance(client,net,owner){
 if(net.route&&client.transport.type!=='custom'){
  try{const p=new URLSearchParams({module:'account',action:'balance',address:owner,tag:'latest'});const r=await fetchJSON(`https://api.routescan.io/v2/network/mainnet/evm/${net.chain.id}/etherscan/api?${p}`);if(r.status==='1')return uint(r.result);}catch{}
 }
 return client.getBalance({address:owner});
}
export async function legacyHistory(net,owner,kind,page=1){
 const action={normal:'txlist',tokens:'tokentx',internal:'txlistinternal'}[kind];
 const params=new URLSearchParams({module:'account',action,address:owner,page:String(page),offset:'50',sort:'desc'});
 const path=`https://api.routescan.io/v2/network/${net.testnet?'testnet':'mainnet'}/evm/${net.chain.id}/etherscan/api?${params}`;
 const data=await fetchJSON(path);
 if(!Array.isArray(data.result))throw Error('History index unavailable for this network.');
 if(data.status!=='1'&&!/no (transactions|records)/i.test(data.message||''))throw Error('History index is temporarily unavailable.');
 return {items:data.result.map(x=>normalizeHistory(x,owner,net.chain.id,kind)).filter(Boolean),next:data.result.length===50?page+1:null,source:'routescan'};
}
export async function historyPage(net,owner,kind,cursor=null){
 if(net.route||net.noBlockscout||cursor?.source==='routescan')return legacyHistory(net,owner,kind,cursor?.next||1);
 const endpoint={normal:'transactions',tokens:'token-transfers',internal:'internal-transactions'}[kind];
 const params=new URLSearchParams(cursor?.next||{});
 const data=await fetchJSON(`${net.explorer}/api/v2/addresses/${owner}/${endpoint}?${params}`);
 if(!Array.isArray(data.items))throw Error('History response is incomplete.');
 return {items:data.items.map(x=>normalizeHistory(x,owner,net.chain.id,kind)).filter(Boolean),next:data.next_page_params||null,source:'blockscout'};
}
export async function nativePrice(net){
 if(!net.coin)return null;
 const data=await fetchJSON(`https://coins.llama.fi/prices/current/coingecko:${net.coin}`);
 const coin=data.coins?.['coingecko:'+net.coin];
 if(!Number.isFinite(coin?.price)||coin.price<=0||!Number.isFinite(coin?.timestamp)||Math.abs(Date.now()/1000-coin.timestamp)>7200)return null;
 return coin.price;
}
export async function tokenPrices(net,tokens){
 const chain={1:'ethereum',42161:'arbitrum',8453:'base',10:'optimism',56:'bsc',137:'polygon'}[net.chain.id];
 if(!chain)return new Map();
 const candidates=tokens.filter(x=>x.address!=='native').slice(0,40);
 if(!candidates.length)return new Map();
 const keys=candidates.map(t=>`${chain}:${t.address.toLowerCase()}`);
 const data=await fetchJSON('https://coins.llama.fi/prices/current/'+keys.join(','));
 return new Map(candidates.map((t,i)=>{const p=data.coins?.[keys[i]];return [t.address.toLowerCase(),Number.isFinite(p?.price)&&p.price>0&&Number.isFinite(p?.timestamp)&&Math.abs(Date.now()/1000-p.timestamp)<7200?p.price:null]}));
}
export async function currentHoldings(client,net,owner,customTokens=[]){
 const result=await Promise.allSettled([nativeBalance(client,net,owner),indexedTokens(net,owner),nativePrice(net)]);
 const native={...nativeToken(net),raw:result[0].status==='fulfilled'?result[0].value.toString():null,price:result[2].status==='fulfilled'?result[2].value:null};
 const tokens=result[1].status==='fulfilled'?result[1].value:[];
 const discovered=new Set(tokens.map(t=>t.address.toLowerCase()));
 const imported=await Promise.allSettled(customTokens.filter(t=>!discovered.has(t.toLowerCase())).slice(0,25).map(t=>readToken(client,t,owner)));
 tokens.push(...imported.filter(r=>r.status==='fulfilled').map(r=>r.value));
 if(result[1].status==='rejected'&&net.route){
  // Discover contracts from indexed transfers, then read balances on-chain.
  try{const page=await legacyHistory(net,owner,'tokens');const contracts=[...new Set(page.items.map(t=>t.token).filter(t=>!discovered.has(t.toLowerCase())))].slice(0,20);
   for(let i=0;i<contracts.length;i+=4){const batch=await Promise.allSettled(contracts.slice(i,i+4).map(c=>readToken(client,c,owner)));tokens.push(...batch.filter(r=>r.status==='fulfilled').map(r=>r.value));}
  }catch{}
 }
 const unique=[...new Map(tokens.map(t=>[t.address.toLowerCase(),t])).values()];
 try{const prices=await tokenPrices(net,unique);unique.forEach(t=>{t.price=prices.get(t.address.toLowerCase())??t.price})}catch{}
 return {tokens:[native,...unique],balanceError:result[0].status==='rejected',discoveryLimited:result[1].status==='rejected'||result[1].value?.partial||tokens.length>=200,importError:imported.some(r=>r.status==='rejected'),updated:Date.now()};
}
export async function checkAllowance(client,token,owner,spender){
 const [details,value]=await Promise.all([readToken(client,token,owner),client.readContract({address:address(token),abi:erc20Abi,functionName:'allowance',args:[address(owner),address(spender)]})]);
 return {...details,spender:address(spender),allowance:value.toString()};
}
export async function recentApprovals(client,owner){
 const end=await client.getBlockNumber(),start=end>2000n?end-2000n:0n;
 const event=parseAbiItem('event Approval(address indexed owner, address indexed spender, uint256 value)');
 const logs=await client.getLogs({event,args:{owner},fromBlock:start,toBlock:end,strict:true});
 const pairs=[...new Map(logs.map(l=>[l.address.toLowerCase()+l.args.spender.toLowerCase(),{token:l.address,spender:l.args.spender}])).values()].slice(-40);
 const found=[];let failed=0;
 for(let i=0;i<pairs.length;i+=4){const rows=await Promise.allSettled(pairs.slice(i,i+4).map(p=>checkAllowance(client,p.token,owner,p.spender)));rows.forEach(r=>{if(r.status==='fulfilled')found.push(r.value);else failed++})}
 return {items:found.filter(x=>BigInt(x.allowance)>0n),start:start.toString(),end:end.toString(),failed,truncated:pairs.length>=40};
}
export async function hipMarkets(){
 const info=body=>fetchJSON('https://api.hyperliquid.xyz/info',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(body)});
 const dexes=await info({type:'perpDexs'});
 if(!Array.isArray(dexes))throw Error('Equity market list unavailable.');
 const candidates=dexes.filter(x=>x?.name).slice(0,8);
 const results=await Promise.allSettled(candidates.map(async d=>{const r=await info({type:'metaAndAssetCtxs',dex:d.name});if(!Array.isArray(r)||!Array.isArray(r[0]?.universe)||!Array.isArray(r[1]))return [];
  const equity=/^(AAPL|AMZN|GOOG|GOOGL|META|MSFT|NVDA|TSLA|COIN|HOOD|PLTR|MSTR|AMD|NFLX|SPY|QQQ|ORCL|BABA|UBER|CRCL|TSM)$/;
  return r[0].universe.flatMap((m,i)=>{const ticker=m.name.split(':').at(-1),p=r[1][i];if(m.isDelisted||!equity.test(ticker)||!Number.isFinite(Number(p?.markPx))||Number(p.markPx)<=0)return [];
   return [{name:m.name,ticker,dex:d.name,price:Number(p.markPx),change:Number(p.prevDayPx)>0?(Number(p.markPx)/Number(p.prevDayPx)-1)*100:null}];});}));
 return results.filter(x=>x.status==='fulfilled').flatMap(x=>x.value);
}
export async function defiPools(){
 const r=await fetchJSON('https://yields.llama.fi/pools');
 if(!Array.isArray(r.data))throw Error('Yield data is unavailable.');
 const protocols={'aave-v3':'aave','lido':'lido','compound-v3':'compound'};
 return r.data.filter(p=>protocols[p.project]&&['Ethereum','Arbitrum','Base','Optimism','Polygon'].includes(p.chain)&&Number.isFinite(p.apy)&&p.apy>=0&&p.apy<100&&p.tvlUsd>1000000).sort((a,b)=>b.tvlUsd-a.tvlUsd).slice(0,24).map(p=>({id:p.pool,symbol:cleanText(p.symbol,36),chain:p.chain,project:p.project,dapp:protocols[p.project],apy:p.apy,tvl:p.tvlUsd}));
}
export async function hyperAccount(owner,testnet=false){
 const info=type=>fetchJSON(`https://api.hyperliquid${testnet?'-testnet':''}.xyz/info`,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({type,user:owner})});
 const results=await Promise.allSettled(['clearinghouseState','spotClearinghouseState','userFills','userNonFundingLedgerUpdates'].map(info));
 return {perps:results[0].status==='fulfilled'?results[0].value:null,spot:results[1].status==='fulfilled'?results[1].value:null,fills:results[2].status==='fulfilled'?results[2].value:null,ledger:results[3].status==='fulfilled'?results[3].value:null};
}
