const {test,expect}=require('@playwright/test');
test('watch-only assets, network reset, failed requests and app search',async({page})=>{
 const calls=[];await page.route('**/info',r=>{const q=r.request().postDataJSON();calls.push(q);const data=q.type==='spotClearinghouseState'?{balances:[{coin:'USDC',total:'100',hold:'10'}]}:q.type==='clearinghouseState'?{marginSummary:{accountValue:'123.45'},assetPositions:[]}:q.type==='userFills'?[{coin:'ETH',dir:'Buy',sz:'1',px:'20',time:Date.now()}]:q.type==='meta'?{universe:[]}:[];return r.fulfill({json:data})});
 await page.goto('/qorvexa-dex/');await page.getByRole('button',{name:'Assets',exact:true}).click();
 await expect(page.locator('#walletValue')).toHaveText('—');await page.getByText('Watch a public address',{exact:true}).click();await page.locator('#walletWatch').fill('bad');await page.getByRole('button',{name:'View address',exact:true}).click();await expect(page.locator('#walletStatus')).toContainText('valid');
 await page.locator('#walletWatch').fill('0x1111111111111111111111111111111111111111');await page.getByRole('button',{name:'View address',exact:true}).click();await expect(page.locator('#walletValue')).toHaveText('123.45 USDC');await expect(page.locator('#walletTokens')).toContainText('100');await expect(page.locator('#walletStatus')).toContainText('Watch-only');await expect(page.locator('#tradeConnect')).toHaveText('Connect wallet');
 await page.screenshot({path:'test-results/beltrix-assets.png',fullPage:true});
 await page.route('https://api.hyperliquid-testnet.xyz/info',r=>r.fulfill({status:503,body:'Unavailable'}));await page.locator('#walletNetwork').selectOption('testnet');await expect(page.locator('#walletStatus')).toContainText('failed');await expect(page.locator('#walletValue')).toHaveText('—');await expect(page.locator('#walletTokens')).toBeEmpty();
 await page.getByRole('button',{name:'Discover',exact:true}).click();await page.locator('#discoverSearch').fill('okx');await expect(page.locator('.dapp-card:visible')).toHaveCount(1);await expect(page.locator('.dapp-card:visible')).toHaveAttribute('href','https://web3.okx.com/');await page.locator('#discoverSearch').fill('nomatch');await expect(page.locator('#discoverEmpty')).toBeVisible();
 await page.setViewportSize({width:390,height:844});await page.getByRole('button',{name:'Assets',exact:true}).click();expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);
 expect(calls.some(x=>x.type==='userFills')).toBe(true);
});
test('OKX provider is selected explicitly and a rejection cannot connect',async({page})=>{
 await page.addInitScript(()=>{window.used=[];window.ethereum={request(){window.used.push('other');throw Error('Wrong provider')}};window.okxwallet={request(x){window.used.push(x.method);throw Error('User rejected')}}});
 await page.goto('/qorvexa-dex/');await page.locator('#walletProvider').selectOption('okx');await page.locator('#tradeConnect').click();await expect(page.locator('#tradeStatus')).toContainText('User rejected');expect(await page.evaluate(()=>window.used)).toEqual(['eth_requestAccounts']);await expect(page.locator('#tradeReview')).toBeDisabled();
});
