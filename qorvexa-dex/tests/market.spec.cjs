const {test,expect}=require('@playwright/test');
test('market selection loads spot metadata, candles, and shows failures',async({page})=>{
 const requests=[];
 await page.route('https://api.hyperliquid.xyz/info',async route=>{const body=route.request().postDataJSON();requests.push(body);let data;
 if(body.type==='meta')data={universe:[{name:'ETH'},{name:'BTC'}]};
 else if(body.type==='spotMeta')data={tokens:[{name:'USDC',index:0},{name:'HYPE',index:150}],universe:[{name:'@107',tokens:[150,0]}]};
 else data=[{t:Date.now()-60000,o:'20',h:'22',l:'19',c:'21',v:'100',s:body.req.coin,i:body.req.interval}];
 await route.fulfill({json:data});});
 await page.goto('/qorvexa-dex/');await page.getByRole('button',{name:'MARKETS',exact:true}).click();
 await expect(page.locator('#marketPrice')).toHaveText('21');
 await page.locator('#marketType').selectOption('spot');await expect(page.locator('#marketSymbol')).toHaveValue('@107');await expect(page.locator('#marketPrice')).toHaveText('21');
 expect(requests.some(x=>x.req?.coin==='@107')).toBe(true);
 await page.setViewportSize({width:390,height:844});expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);
 await page.screenshot({path:'test-results/beltrix-markets.png',fullPage:true});
 await page.route('https://api.hyperliquid.xyz/info',r=>r.fulfill({status:503,body:'Unavailable'}));
 await page.locator('#marketRefresh').click();await expect(page.locator('#marketStatus')).toContainText('실패');await expect(page.locator('#marketPrice')).toHaveText('—');
});
