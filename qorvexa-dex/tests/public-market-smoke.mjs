// Public read-only API integration check. No user address, keys or exchange writes.
for(const base of ['https://api.hyperliquid.xyz','https://api.hyperliquid-testnet.xyz']){
 const read=async body=>{const r=await fetch(base+'/info',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(body),signal:AbortSignal.timeout(15000)});if(!r.ok)throw Error('Public info HTTP '+r.status);return r.json()};
 const [meta,contexts]=await read({type:'metaAndAssetCtxs'});const i=meta.universe.findIndex(x=>x.name==='ETH');if(i<0||!Number.isInteger(meta.universe[i].maxLeverage)||!Number.isInteger(meta.universe[i].szDecimals))throw Error('ETH market metadata missing');
 const context=contexts[i];for(const key of ['funding','markPx','oraclePx'])if(context[key]==null||!Number.isFinite(Number(context[key])))throw Error('Invalid context '+key);
 const book=await read({type:'l2Book',coin:'ETH'});if(book.coin!=='ETH'||!book.levels?.[0]?.length||!book.levels?.[1]?.length||!(Number(book.levels[0][0].px)<Number(book.levels[1][0].px)))throw Error('Invalid order book');
 console.log(new URL(base).hostname+': ETH funding, mark/oracle, leverage metadata and live book verified');
}
