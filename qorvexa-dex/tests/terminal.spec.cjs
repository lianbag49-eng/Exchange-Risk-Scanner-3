const {test,expect}=require('@playwright/test');
const user='0x1111111111111111111111111111111111111111';
async function fixture(page,options={}){
 const actions=[];
 await page.routeWebSocket('wss://api.hyperliquid*.xyz/ws',ws=>{
  ws.onMessage(message=>{
   const x=JSON.parse(message);
   if(options.trades&&x.subscription?.type==='trades')ws.send(JSON.stringify({channel:'trades',data:[{coin:x.subscription.coin,time:Date.now(),tid:1,px:'2605',sz:'0.3',side:'B'}]}));
   if(options.stream&&x.subscription?.type==='l2Book')ws.send(JSON.stringify({channel:'l2Book',data:{coin:x.subscription.coin,time:Date.now(),levels:[[{px:'2600',sz:'50',n:1}],[{px:'2601',sz:'50',n:1}]]}}));
  });
 });
 await page.addInitScript(({user,signatureReject})=>{
  window.walletCalls=[];window.signatures=[];window.walletEvents={};window.currentChain='0x66eee';window.currentUser=user;
  window.ethereum={on(event,cb){window.walletEvents[event]=cb},async request({method,params}){
   window.walletCalls.push(method);
   if(method==='eth_requestAccounts'||method==='eth_accounts')return [window.currentUser];
   if(method==='wallet_switchEthereumChain')return null;
   if(method==='eth_chainId')return window.currentChain;
   if(method==='eth_signTypedData_v4'){
    window.signatures.push(JSON.parse(params[1]));
    if(signatureReject)throw {code:4001,message:'User rejected request'};
    return '0x'+'11'.repeat(32)+'22'.repeat(32)+'1b';
   }
   throw Error('Unexpected RPC: '+method);
  }};
 },{user,signatureReject:!!options.signatureReject});
 const pm={universe:[{name:'ETH',szDecimals:3,maxLeverage:50},{name:'BTC',szDecimals:5,maxLeverage:40}]};
 const sm={tokens:[{name:'USDC',index:0,szDecimals:8},{name:'PURR',index:1,szDecimals:2}],universe:[{name:'PURR/USDC',index:0,tokens:[1,0]}]};
 await page.route('https://api.hyperliquid*.xyz/**',async route=>{
  const req=route.request();const b=req.postDataJSON();let data;
  if(req.url().endsWith('/exchange')){
   actions.push({url:req.url(),...b});
   if(options.orderTimeout&&b.action.type==='order'){return route.abort('failed')}
   data=b.action.type==='order'?{status:'ok',response:{type:'order',data:{statuses:[{resting:{oid:777}}]}}}:b.action.type==='cancel'?{status:'ok',response:{type:'cancel',data:{statuses:['success']}}}:{status:'ok',response:{type:'default'}};
  }else switch(b.type){
   case 'metaAndAssetCtxs':data=[pm,[{markPx:'2500',prevDayPx:'2450',dayNtlVlm:'1000000'},{markPx:'80000',prevDayPx:'81000',dayNtlVlm:'2000000'}]];break;
   case 'spotMetaAndAssetCtxs':data=[sm,[{markPx:'1',prevDayPx:'0.98',dayNtlVlm:'12345'}]];break;
   case 'meta':data=pm;break;case 'spotMeta':data=sm;break;
   case 'l2Book':data={coin:b.coin,time:Date.now()-(options.stale?60000:0),levels:[[...Array(8)].map((_,i)=>({px:String(b.coin==='PURR/USDC'?0.99-i*.01:2499-i),sz:'100',n:2})),[...Array(8)].map((_,i)=>({px:String(b.coin==='PURR/USDC'?1.01+i*.01:2501+i),sz:'100',n:2}))]};break;
   case 'candleSnapshot':data=Array.from({length:60},(_,i)=>({s:b.req.coin,i:b.req.interval,t:Math.floor(Date.now()/900000)*900000-(60-i)*900000,o:'2499',h:'2510',l:'2490',c:String(2495+(i%10)),v:'100'}));break;
   case 'clearinghouseState':data={marginSummary:{accountValue:'10000'},withdrawable:'10000',assetPositions:[]};break;
   case 'spotClearinghouseState':data={balances:[{coin:'USDC',total:'10000',hold:'0'},{coin:'PURR',total:'10000',hold:'0'}]};break;
   case 'openOrders':data=options.openOrder?[{coin:'ETH',oid:777,side:'B',sz:'0.1',limitPx:'2500'}]:[];break;
   case 'userFills':data=[];break;
   case 'orderStatus':data={status:'unknownOid'};break;
   default:return route.fulfill({status:400,body:JSON.stringify({error:'Unexpected info '+b.type})});
  }
  await route.fulfill({json:data});
 });
 return actions;
}
async function ready(page,options={}){
 const actions=await fixture(page,options);await page.goto('/qorvexa-dex/dist/');
 await expect(page.locator('#bookStatus')).toContainText('TESTNET');await page.locator('#walletBtn').click();await expect(page.locator('#accountStatus')).toContainText('TESTNET');return actions;
}
async function review(page){await page.locator('#orderSize').fill('0.1');await page.locator('#limitPrice').fill('2500');await page.locator('#reviewOrder').click();await expect(page.locator('#orderDialog')).toBeVisible();}
test('perp order uses reviewed values, signed testnet domain, testnet endpoint only',async({page})=>{
 const actions=await ready(page);await page.screenshot({path:'test-results/terminal-desktop.jpg',type:'jpeg',quality:55,fullPage:true});await review(page);await expect(page.locator('#orderReview')).toContainText('250.00');
 await page.locator('#submitOrder').click();await expect(page.locator('#submitState')).toContainText('주문 접수');
 expect(actions.map(a=>a.action.type)).toEqual(['updateLeverage','order']);
 expect(actions.every(a=>a.url==='https://api.hyperliquid-testnet.xyz/exchange')).toBe(true);
 expect(actions[1].action.orders[0]).toMatchObject({a:0,b:true,p:'2500',s:'0.1',r:false,t:{limit:{tif:'Gtc'}}});
 expect(actions[1].expiresAfter).toBeGreaterThan(Date.now());
 const sig=await page.evaluate(()=>window.signatures);expect(sig.every(s=>s.message.source==='b')).toBe(true);
});
test('spot uses 10000 + index and does not change leverage',async({page})=>{
 const actions=await ready(page);await page.locator('#marketType').selectOption('spot');await expect(page.locator('#market')).toHaveValue('10000');await expect(page.locator('#bookStatus')).toContainText('TESTNET');
 await page.locator('#orderSize').fill('20');await page.locator('#limitPrice').fill('1');await page.locator('#reviewOrder').click();await page.locator('#submitOrder').click();await expect(page.locator('#submitState')).toContainText('접수');
 expect(actions).toHaveLength(1);expect(actions[0].action.orders[0].a).toBe(10000);
});
test('mainnet view cannot submit',async({page})=>{const actions=await ready(page);await page.locator('#dataNetwork').selectOption('mainnet');await page.locator('#reviewOrder').click();await expect(page.locator('#toast')).toContainText('조회 전용');expect(actions).toHaveLength(0);});
test('stale book blocks before signing',async({page})=>{const actions=await fixture(page,{stale:true});await page.goto('/qorvexa-dex/dist/');await expect(page.locator('#feedState')).toContainText('지연');await page.locator('#walletBtn').click();await expect(page.locator('#accountStatus')).toContainText('TESTNET');await page.locator('#reviewOrder').click();await expect(page.locator('#toast')).toContainText('최신 호가');expect(actions).toHaveLength(0);});
test('account change invalidates review',async({page})=>{const actions=await ready(page);await review(page);await page.evaluate(()=>{window.currentUser='0x2222222222222222222222222222222222222222';window.walletEvents.accountsChanged([])});await expect(page.locator('#submitOrder')).toBeDisabled();expect(actions).toHaveLength(0);});
test('signature rejection sends nothing',async({page})=>{const actions=await ready(page,{signatureReject:true});await review(page);await page.locator('#submitOrder').click();await expect(page.locator('#submitState')).toContainText('미완료');expect(actions).toHaveLength(0);});
test('ambiguous order result is not retried and locks later orders',async({page})=>{const actions=await ready(page,{orderTimeout:true});await review(page);await page.locator('#submitOrder').click();await expect(page.locator('#submitState')).toContainText('미확인');await page.locator('#closeOrder').click();await page.locator('#reviewOrder').click();await expect(page.locator('#toast')).toContainText('이전 주문');expect(actions.filter(a=>a.action.type==='order')).toHaveLength(1);});
test('cancel resolves own market when another chart is selected',async({page})=>{const actions=await ready(page,{openOrder:true});await page.locator('#market').selectOption('1');page.once('dialog',d=>d.accept());await page.locator('#openOrders button').click();await expect(page.locator('#toast')).toContainText('취소 완료');expect(actions[0].action.cancels).toEqual([{a:0,o:777}]);});
test('terminal renders mobile without overflow',async({page})=>{await page.setViewportSize({width:390,height:844});await ready(page);expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);await page.screenshot({path:'test-results/terminal-mobile.jpg',type:'jpeg',quality:55,fullPage:true});});

test('websocket book updates the displayed depth',async({page})=>{await ready(page,{stream:true});await expect(page.locator('#feedState')).toContainText('실시간');await expect(page.locator('#bids')).toContainText('2,600');});
test('expired review cannot sign',async({page})=>{const actions=await ready(page);await review(page);await page.evaluate(()=>{const now=Date.now;Date.now=()=>now()+61000});await page.locator('#submitOrder').click();await expect(page.locator('#submitState')).toContainText('만료');expect(actions).toHaveLength(0);expect(await page.evaluate(()=>window.signatures.length)).toBe(0);});
test('failed market metadata clears previous market values',async({page})=>{await ready(page);await page.route('https://api.hyperliquid-testnet.xyz/info',r=>r.fulfill({status:503,body:'Unavailable'}));await page.locator('#marketType').selectOption('spot');await expect(page.locator('#feedState')).toContainText('실패');await expect(page.locator('#lastPrice')).toHaveText('—');await expect(page.locator('#market')).toHaveValue('');});

test('trade ticks appear within one second and clear on market switch',async({page})=>{await ready(page,{trades:true});await expect(page.locator('#lastPrice')).toHaveText('$2,605',{timeout:2000});await expect(page.locator('#marketClock')).toContainText('1초');await expect(page.locator('#marketTrades')).toContainText('0.3');await page.locator('#market').selectOption('1');await expect(page.locator('#marketTrades')).toContainText('대기');});
