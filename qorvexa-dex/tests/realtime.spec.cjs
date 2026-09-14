const {test,expect}=require('@playwright/test');
test('one-second trade and book refresh, network switch and stale order lock',async({page})=>{
 await page.route('**/info',r=>{const q=r.request().postDataJSON();return r.fulfill({json:q.type==='meta'?{universe:[{name:'ETH',szDecimals:4}]}:q.type==='spotMeta'?{tokens:[{index:0,name:'USDC'},{index:1,name:'TEST',szDecimals:2}],universe:[{index:4,name:'@4',tokens:[1,0]}]}:[{t:Date.now()-60000,o:20,h:22,l:19,c:21,v:100}]})});
 await page.addInitScript(()=>{window.sockets=[];window.WebSocket=class {constructor(url){this.url=url;this.readyState=1;window.sockets.push(this);setTimeout(()=>this.onopen?.(),0)}send(s){const x=JSON.parse(s);if(x.subscription?.type==='l2Book'){this.coin=x.subscription.coin;const msg={channel:'l2Book',data:{coin:this.coin,time:Date.now(),levels:[[{px:'20',sz:'3'}],[{px:'21',sz:'4'}]]}};this.onmessage?.({data:JSON.stringify(msg)})}}close(){this.readyState=3}emit(x){this.onmessage?.({data:JSON.stringify(x)})}}});
 await page.goto('/qorvexa-dex/');await page.getByRole('button',{name:'MARKETS',exact:true}).click();
 await expect(page.locator('#marketBids')).toContainText('20');
 await page.evaluate(()=>window.sockets.at(-1).emit({channel:'trades',data:[{coin:'ETH',time:Date.now(),tid:1,px:'25',sz:'1',side:'B'}]}));
 await expect(page.locator('#marketPrice')).toHaveText('25',{timeout:1800});await expect(page.locator('#marketClock')).toContainText('1초');
 await expect(page.locator('#tradeReview')).toBeDisabled();
 await page.locator('#marketNetwork').selectOption('testnet');await expect.poll(()=>page.evaluate(()=>window.sockets.at(-1).url)).toContain('hyperliquid-testnet');
 await page.locator('#marketType').selectOption('spot');await expect(page.locator('#marketSymbol')).toHaveValue('@4');await expect(page.locator('#marketTrades')).not.toContainText('25');
 await expect(page.locator('#marketStatus')).toContainText('주문 잠금',{timeout:8000});
});
test('order precision and asset mapping validation',async()=>{
 const {makeOrder}=await import('../order-validation.js');
 const spot={asset:10004,szDecimals:2,spot:true};expect(makeOrder(spot,'20','1',true,false,'Alo').a).toBe(10004);
 expect(()=>makeOrder(spot,'20','1.001',true,false,'Gtc')).toThrow();expect(()=>makeOrder(spot,'20','1',true,true,'Gtc')).toThrow();expect(()=>makeOrder(spot,'20','0.01',true,false,'Gtc')).toThrow();
});
