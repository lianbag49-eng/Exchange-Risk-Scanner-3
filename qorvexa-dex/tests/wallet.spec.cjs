const {test,expect}=require('@playwright/test');
const {decodeFunctionData,erc20Abi}=require('viem');
const {fixture,connect,review,A,B,T,HASH}=require('./wallet-fixture.cjs');
test.beforeEach(async({page})=>fixture(page));

test('wallet home uses real empty state and all five navigation destinations work on mobile',async({page})=>{
 await page.setViewportSize({width:390,height:844});await page.goto('/qorvexa-dex/');await expect(page.locator('#wTotal')).toHaveText('—');await expect(page.locator('#wAssets')).toContainText('Connect wallet');
 for(const dest of ['explore','defi','boost','markets','wallet']){await page.locator(`.bottom-nav [data-page="${dest}"]`).click();await expect(page.locator('body')).toHaveAttribute('data-page',dest);expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);}
 expect(await page.locator('body').innerText()).not.toMatch(/[가-힣]/);await page.screenshot({path:'test-results/wallet-empty-mobile.jpg',type:'jpeg',quality:72,fullPage:true});
});
test('connects real provider API, shows native and ERC20 balances, receive address and QR',async({page})=>{
 await connect(page);await expect(page.locator('#wAssets')).toContainText('USDC');await expect(page.locator('#wAssets')).toContainText('100');await expect(page.locator('#wAccountMode')).toContainText('Browser wallet');
 await page.locator('.w-actions [data-action="receive"]').click();await expect(page.locator('#wReceiveAddress')).toHaveText(A);expect(await page.locator('#wReceiveQR').evaluate(c=>c.width)).toBeGreaterThan(200);await expect(page.locator('#wDialogBody')).toContainText('chain ID 1');
 await page.locator('#wClose').click();await expect(page.locator('#toast')).not.toHaveClass(/show/);await page.setViewportSize({width:390,height:844});expect(await page.locator('#wAssets .w-token-row').first().evaluate(el=>el.getBoundingClientRect().bottom<document.querySelector('.bottom-nav').getBoundingClientRect().top)).toBe(true);await page.screenshot({path:'test-results/wallet-mobile.jpg',type:'jpeg',quality:72,fullPage:true});await page.setViewportSize({width:1280,height:900});await page.screenshot({path:'test-results/wallet-desktop.jpg',type:'jpeg',quality:72,fullPage:true});
});
test('native send requires explicit review and submits one exact chain-bound transaction',async({page})=>{
 await connect(page);await review(page);await expect(page.locator('#wSignTransfer')).toBeDisabled();expect(await page.evaluate(()=>walletFixture.sent.length)).toBe(0);await page.locator('#wReviewAck').check();await page.locator('#wSignTransfer').click();await expect(page.locator('#wDialogTitle')).toHaveText('Transaction submitted');
 const sent=await page.evaluate(()=>walletFixture.sent);expect(sent).toHaveLength(1);expect(sent[0]).toMatchObject({from:A,to:B,chainId:'0x1',value:'0x16345785d8a0000',data:'0x',nonce:'0x5'});await expect(page.locator('#wDialogBody')).toContainText(HASH);
});
test('ERC20 send encodes exact six-decimal quantity and never requests an allowance',async({page})=>{
 await connect(page);await review(page,{token:T,quantity:'1.234567'});await page.locator('#wReviewAck').check();await page.locator('#wSignTransfer').click();await expect(page.locator('#wDialogTitle')).toHaveText('Transaction submitted');
 const tx=await page.evaluate(()=>walletFixture.sent[0]);expect(tx.to).toBe(T);expect(tx.value).toBe('0x0');const decoded=decodeFunctionData({abi:erc20Abi,data:tx.data});expect(decoded.functionName).toBe('transfer');expect(decoded.args).toEqual([B,1234567n]);
});
test('invalid precision and insufficient gas are blocked before the wallet can send',async({page})=>{
 await connect(page);await page.locator('.w-actions [data-action="send"]').click();await page.locator('#wSendAsset').selectOption(T);await page.locator('#wSendTo').fill(B);await page.locator('#wSendAmount').fill('1.1234567');await page.locator('#wSendReview').click();await expect(page.locator('#wFormStatus')).toContainText('6 decimal');expect(await page.evaluate(()=>walletFixture.sent.length)).toBe(0);
 await page.locator('#wSendAsset').selectOption('native');await page.locator('#wSendAmount').fill('2');await page.locator('#wSendReview').click();await expect(page.locator('#wFormStatus')).toContainText('gas budget');
});
test('a token returning false from transfer simulation is blocked',async({page})=>{
 await connect(page);await page.evaluate(()=>walletFixture.failTransfer=true);await page.locator('.w-actions [data-action="send"]').click();await page.locator('#wSendAsset').selectOption(T);await page.locator('#wSendTo').fill(B);await page.locator('#wSendAmount').fill('1');await page.locator('#wSendReview').click();await expect(page.locator('#wFormStatus')).toContainText('rejected this operation');expect(await page.evaluate(()=>walletFixture.sent.length)).toBe(0);
});
for(const condition of ['chain','account','nonce'])test(`changing ${condition} after review blocks submission`,async({page})=>{
 await connect(page);await review(page);await page.evaluate(c=>{if(c==='chain')walletFixture.chain='0xa';if(c==='account')walletFixture.account=walletFixture.B;if(c==='nonce')walletFixture.nonce='0x6';},condition);await page.locator('#wReviewAck').check();await page.locator('#wSignTransfer').click();await expect(page.locator('#wFormStatus')).toContainText(/changed/i);expect(await page.evaluate(()=>walletFixture.sent.length)).toBe(0);
});
test('expired review cannot submit a transaction',async({page})=>{
 await connect(page);await review(page);await page.evaluate(()=>{const old=Date.now;Date.now=()=>old()+100000;});await page.locator('#wReviewAck').check();await page.locator('#wSignTransfer').click();await expect(page.locator('#wFormStatus')).toContainText('expired');expect(await page.evaluate(()=>walletFixture.sent.length)).toBe(0);
});
test('ambiguous wallet response persists a submission lock across reloads',async({page})=>{
 await connect(page);await page.evaluate(()=>walletFixture.unknown=true);await review(page);await page.locator('#wReviewAck').check();await page.locator('#wSignTransfer').click();await expect(page.locator('#wFormStatus')).toContainText('reconcile');await page.locator('#wClose').click();await page.locator('.w-actions [data-action="send"]').click();await expect(page.locator('#wDialogTitle')).toHaveText('Notifications');expect(await page.evaluate(()=>walletFixture.sent.length)).toBe(1);
 await page.reload();await page.locator('#wAssets [data-action="connect"]').click();await page.locator('[data-connect="0"]').click();await expect(page.locator('#wTotal')).toHaveText('$5,100.00');await page.locator('.w-actions [data-action="send"]').click();await expect(page.locator('#wDialogTitle')).toHaveText('Notifications');await expect(page.locator('#wDialogBody')).toContainText('Unknown');
});
test('an explicit wallet rejection is shown as rejected and not confirmed',async({page})=>{
 await connect(page);await page.evaluate(()=>walletFixture.rejected=true);await review(page);await page.locator('#wReviewAck').check();await page.locator('#wSignTransfer').click();await expect(page.locator('#wFormStatus')).toContainText('rejected');await page.locator('#wClose').click();await page.locator('[aria-label="Notifications"]').click();await expect(page.locator('#wDialogBody')).toContainText('Rejected');await expect(page.locator('#wDialogBody')).not.toContainText('Confirmed');
});
test('history combines sends, receives, failed transfers and paginates older entries',async({page})=>{
 await connect(page);await page.locator('.w-actions [data-action="history"]').click();await expect(page.locator('#wHistoryStatus')).toContainText('51 records loaded');await page.locator('[data-history-filter="failed"]').click();await expect(page.locator('#wHistoryRows .w-history-row')).toHaveCount(1);await expect(page.locator('#wHistoryRows')).toContainText('Failed');await page.locator('[data-history-filter="receive"]').click();await expect(page.locator('#wHistoryRows')).toContainText('Receive USDC');await page.locator('#wHistoryMore').click();await expect(page.locator('#wHistoryStatus')).toContainText('52 records loaded');
 await page.locator('#wHistorySearch').fill('USDC');const download=page.waitForEvent('download');await page.locator('#wHistoryExport').click();expect((await download).suggestedFilename()).toContain('loaded-history.csv');await page.locator('#wHistoryRows .w-history-row').first().click();await expect(page.locator('#wDialogTitle')).toHaveText('Transaction details');await expect(page.locator('#wDialogBody')).toContainText('Token');
});
test('history provider failure is explicitly incomplete, never presented as an empty account',async({page})=>{
 await page.route('https://api.routescan.io/**',r=>['txlist','tokentx','txlistinternal'].includes(new URL(r.request().url()).searchParams.get('action'))?r.fulfill({status:503,body:'Unavailable'}):r.fallback());await connect(page);await page.locator('.w-actions [data-action="history"]').click();await expect(page.locator('#wHistoryStatus')).toContainText('Incomplete sources');await expect(page.locator('#wHistoryRows')).toContainText('History is unavailable');
});
test('approval revocation uses the exact spender and a zero allowance',async({page})=>{
 await connect(page);await page.locator('[data-tab="approvals"]').click();await page.locator('#wPanel-approvals [data-action="approvals"]').click();await page.locator('#wApprovalToken').fill(T);await page.locator('#wApprovalSpender').fill(B);await page.locator('#wApprovalCheck').click();await expect(page.locator('#wApprovalRows')).toContainText('25');await page.locator('[data-revoke="0"]').click();await expect(page.locator('#wDialogTitle')).toContainText('revocation');await page.locator('#wReviewAck').check();await page.locator('#wSignTransfer').click();await expect(page.locator('#wDialogTitle')).toHaveText('Transaction submitted');const tx=await page.evaluate(()=>walletFixture.sent[0]);const decoded=decodeFunctionData({abi:erc20Abi,data:tx.data});expect(decoded.functionName).toBe('approve');expect(decoded.args).toEqual([B,0n]);
});
test('watch-only accounts cannot sign and account updates clear old balances',async({page})=>{
 await page.goto('/qorvexa-dex/');await page.locator('#wAccount').click();await page.locator('#wWatchName').fill('Treasury watch');await page.locator('#wWatchAddress').fill(A);await page.locator('#wWatchAdd').click();await expect(page.locator('#wAccountMode')).toContainText('Watch-only');await page.locator('.w-actions [data-action="send"]').click();await expect(page.locator('#wDialogTitle')).toHaveText('Your accounts');expect(await page.evaluate(()=>walletFixture.sent.length)).toBe(0);
});
test('stock, DApp, DeFi and Hyperliquid ledger views are connected to provider data',async({page})=>{
 await connect(page);await page.locator('[data-tab="stocks"]').click();await expect(page.locator('#wStocks')).toContainText('AAPL');await page.locator('[data-stock="0"]').click();await expect(page.locator('#wDialogBody')).toContainText('not a share');await page.locator('#wClose').click();await page.locator('.bottom-nav [data-page="defi"]').click();await expect(page.locator('#wPools')).toContainText('3.25%');await page.locator('#wDefiDapps [data-dapp="aave"]').click();await expect(page.locator('#wDialogBody a')).toHaveAttribute('href','https://app.aave.com/');await page.locator('#wClose').click();await page.locator('#defi [data-action="hyper-account"]').click();await expect(page.locator('#wHyperData')).toContainText('deposit');await expect(page.locator('#wHyperData')).toContainText('1000 USDC');
});
test('preferences respect account-storage opt-out after reload',async({page})=>{
 await connect(page);await page.locator('[aria-label="Wallet preferences"]').click();await page.locator('#wRemember').uncheck();await page.reload();await page.locator('[aria-label="Wallet preferences"]').click();await expect(page.locator('#wRemember')).not.toBeChecked();expect(await page.evaluate(()=>JSON.parse(localStorage.getItem('beltrix-wallet-v1')).accounts)).toBeUndefined();
});
test('rapid duplicate clicks still send only one wallet transaction',async({page})=>{
 await connect(page);await review(page);await page.locator('#wReviewAck').check();await page.locator('#wSignTransfer').evaluate(b=>{b.click();b.click();});await expect(page.locator('#wDialogTitle')).toHaveText('Transaction submitted');expect(await page.evaluate(()=>walletFixture.sent.length)).toBe(1);
});
for(const [receipt,expected] of [['success','Confirmed'],['failed','Failed'],['mismatch','Unknown']])test(`receipt ${receipt} produces only its verified status`,async({page})=>{
 await connect(page);await page.evaluate(value=>walletFixture.receipt=value,receipt);await review(page);await page.locator('#wReviewAck').check();await page.locator('#wSignTransfer').click();await expect.poll(()=>page.evaluate(()=>JSON.parse(localStorage.getItem('beltrix-transactions-v1')||'[]')[0]?.status)).toBe(expected);
});
