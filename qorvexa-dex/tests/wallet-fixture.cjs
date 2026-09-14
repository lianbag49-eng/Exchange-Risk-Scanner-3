const {encodeAbiParameters}=require('viem');
const A='0x1111111111111111111111111111111111111111',B='0x2222222222222222222222222222222222222222',T='0x3333333333333333333333333333333333333333',HASH='0x'+'a'.repeat(64);
const uint=n=>encodeAbiParameters([{type:'uint256'}],[BigInt(n)]),string=s=>encodeAbiParameters([{type:'string'}],[s]);
async function fixture(page,options={}){
 const config={A,B,T,HASH,decimals:uint(6),symbol:string('USDC'),name:string('USD Coin'),tokenBalance:uint(100000000),allowance:uint(25000000),yes:uint(1),no:uint(0),...options};
 await page.addInitScript(cfg=>{
  window.walletFixture={...cfg,chain:'0x1',account:cfg.A,balance:'0x1bc16d674ec80000',nonce:'0x5',sent:[],calls:[],handlers:{},rejected:false,unknown:false,failTransfer:false,gas:'0x186a0'};
  window.fixtureRPC=async x=>{const f=window.walletFixture;f.calls.push(x);const method=x.method;
   if(method==='eth_accounts'||method==='eth_requestAccounts')return [f.account];
   if(method==='eth_chainId')return f.chain;
   if(method==='wallet_switchEthereumChain'){f.chain=x.params[0].chainId;for(const fn of f.handlers.chainChanged||[])fn(f.chain);return null;}
   if(method==='wallet_requestPermissions')return [{parentCapability:'eth_accounts'}];
   if(method==='eth_getBalance')return f.balance;
   if(method==='eth_getTransactionCount')return f.nonce;
   if(method==='eth_estimateGas')return f.gas;
   if(method==='eth_gasPrice')return '0x3b9aca00';
   if(method==='eth_getCode')return '0x';
   if(method==='eth_call'){const data=x.params[0].data||'';if(data.startsWith('0x313ce567'))return f.decimals;if(data.startsWith('0x95d89b41'))return f.symbol;if(data.startsWith('0x06fdde03'))return f.name;if(data.startsWith('0x70a08231'))return f.tokenBalance;if(data.startsWith('0xdd62ed3e'))return f.allowance;if(data.startsWith('0xa9059cbb'))return f.failTransfer?f.no:f.yes;if(data.startsWith('0x095ea7b3'))return f.yes;throw Error('Unexpected call '+data);}
   if(method==='eth_sendTransaction'){f.sent.push(x.params[0]);if(f.rejected)throw {code:4001,message:'User rejected the request'};if(f.unknown)throw {code:-32002,message:'Wallet response unavailable'};return f.HASH;}
   if(method==='eth_getTransactionReceipt')return null;
   if(method==='eth_blockNumber')return '0x10000';
   if(method==='eth_getLogs')return [];
   throw Error('Unexpected RPC '+method);
  };
  window.ethereum={request:window.fixtureRPC,on(name,fn){(window.walletFixture.handlers[name]??=[]).push(fn);},removeListener(name,fn){window.walletFixture.handlers[name]=(window.walletFixture.handlers[name]||[]).filter(x=>x!==fn)}};
  window.WebSocket=class{constructor(){this.readyState=1;setTimeout(()=>this.onopen?.(),0)}send(){}close(){this.readyState=3}};
 },config);
 await page.route('**/*',async route=>{
  const req=route.request(),url=new URL(req.url());if(url.hostname==='127.0.0.1')return route.continue();
  if(url.pathname==='/info'){
   const q=req.postDataJSON();let r;
   if(q.type==='meta')r={universe:[{name:'ETH',szDecimals:4}]};else if(q.type==='perpDexs')r=[null,{name:'xyz'}];else if(q.type==='metaAndAssetCtxs')r=[{universe:[{name:'xyz:AAPL'},{name:'xyz:TSLA'}]},[{markPx:'220',prevDayPx:'200'},{markPx:'400',prevDayPx:'390'}]];
   else if(q.type==='clearinghouseState')r={marginSummary:{accountValue:'1000'},assetPositions:[]};else if(q.type==='spotClearinghouseState')r={balances:[{coin:'USDC',total:'25',hold:'0'}]};else if(q.type==='userFills')r=[];else if(q.type==='userNonFundingLedgerUpdates')r=[{time:Date.now(),hash:HASH,delta:{type:'deposit',usdc:'100'}}];else if(q.type==='openOrders')r=[];else r=[{t:Date.now()-60000,o:20,h:22,l:19,c:21,v:10}];return route.fulfill({json:r});
  }
  if(url.hostname==='coins.llama.fi'){const keys=decodeURIComponent(url.pathname.split('/').at(-1)).split(',');return route.fulfill({json:{coins:Object.fromEntries(keys.map(k=>[k,{price:k.startsWith('coingecko')?2500:1,timestamp:Date.now()/1000}]))}});}
  if(url.hostname==='yields.llama.fi')return route.fulfill({json:{data:[{pool:'fixture-pool',project:'aave-v3',chain:'Ethereum',symbol:'USDC',apy:3.25,tvlUsd:100000000}]}});
  if(url.pathname.includes('/token-balances'))return route.fulfill({json:[{value:'100000000',token:{type:'ERC-20',address_hash:T,name:'USD Coin',symbol:'USDC',decimals:'6',exchange_rate:'1'}}]});
  if(url.hostname==='api.routescan.io'){
   if(url.pathname.includes('/erc20-holdings'))return route.fulfill({json:{items:[{tokenQuantity:'100000000',tokenAddress:T,tokenName:'USD Coin',tokenSymbol:'USDC',tokenDecimals:6}]}});
   if(url.searchParams.get('action')==='balance')return route.fulfill({json:{status:'1',message:'OK',result:'2000000000000000000'}});
   if(options.historyFail)return route.fulfill({status:503,body:'Unavailable'});
   const action=url.searchParams.get('action'),p=Number(url.searchParams.get('page')||1),tx=(i)=>({hash:'0x'+i.toString(16).padStart(64,'0'),from:i%2?A:B,to:i%2?B:A,value:'10000000000000000',timeStamp:String(1700000000+i),isError:i===2?'1':'0',blockNumber:'100',input:'0x',gasUsed:'21000',gasPrice:'1000000000'});
   const result=action==='txlist'?(p===1?Array.from({length:50},(_,i)=>tx(i+1)):[tx(51)]):action==='tokentx'?[{...tx(900),value:'1200000',tokenDecimal:'6',tokenSymbol:'USDC',contractAddress:T,logIndex:'1'}]:[];
   return route.fulfill({json:{status:result.length?'1':'0',message:result.length?'OK':'No transactions found',result}});
  }
  if(req.method()==='POST'){let q;try{q=req.postDataJSON()}catch{}if(q?.method){let result;const methods={eth_getBalance:'0x1bc16d674ec80000',eth_chainId:'0x1',eth_blockNumber:'0x10000',eth_getLogs:[]};if(q.method in methods)result=methods[q.method];else result='0x';return route.fulfill({json:{jsonrpc:'2.0',id:q.id,result}});}}
  return route.abort();
 });
}
async function connect(page){await page.goto('/qorvexa-dex/');await page.locator('#wAssets [data-action="connect"]').click();await page.locator('[data-connect="0"]').click();await require('@playwright/test').expect(page.locator('#wTotal')).toHaveText('$5,100.00');}
async function review(page,{token='native',quantity='0.1',to=B}={}){await page.locator('.w-actions [data-action="send"]').click();await page.locator('#wSendAsset').selectOption(token);await page.locator('#wSendTo').fill(to);await page.locator('#wSendAmount').fill(quantity);await page.locator('#wSendReview').click();await require('@playwright/test').expect(page.locator('#wReviewAck')).toBeVisible();}
module.exports={fixture,connect,review,A,B,T,HASH};
