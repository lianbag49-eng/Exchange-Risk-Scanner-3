export function makeOrder(market,price,size,isBuy,reduceOnly,tif){
 if(!market||!Number.isInteger(market.asset)||!Number.isInteger(market.szDecimals)||market.szDecimals<0)throw Error('Market metadata unavailable');
 const numeric=/^(0|[1-9]\d*)(\.\d+)?$/;
 if(!numeric.test(price)||!numeric.test(size)||!Number.isFinite(Number(price))||!Number.isFinite(Number(size))||Number(price)<=0||Number(size)<=0)throw Error('Price and size must be positive');
 const digits=s=>s.includes('.')?s.split('.')[1].replace(/0+$/,'').length:0;
 if(digits(size)>market.szDecimals)throw Error('Maximum size decimals: '+market.szDecimals+'');
 if(digits(price)>(market.spot?8:6)-market.szDecimals)throw Error('Check price decimal precision');
 if(!Number.isInteger(Number(price))&&price.replace('.','').replace(/^0+/,'').replace(/0+$/,'').length>5)throw Error('Price supports up to 5 significant figures');
 if(Number(price)*Number(size)<10)throw Error('Minimum order value is 10 USDC');
 if(!['Gtc','Alo','Ioc'].includes(tif))throw Error('Unsupported order type');
 if(market.spot&&reduceOnly)throw Error('Reduce only is not available for spot');
 const canonical=s=>s.includes('.')?s.replace(/0+$/,'').replace(/\.$/,''):s;
 return {a:market.asset,b:isBuy,p:canonical(price),s:canonical(size),r:reduceOnly,t:{limit:{tif}}};
}
export function freshMarket(m){return m?.network==='testnet'&&m?.book?.coin===m?.market?.value&&Date.now()-m.received<5000&&Math.abs(Date.now()-m.book.time)<10000;}
