export function makeOrder(market,price,size,isBuy,reduceOnly,tif){
 if(!market||!Number.isInteger(market.asset)||!Number.isInteger(market.szDecimals)||market.szDecimals<0)throw Error('종목 메타데이터 확인 불가');
 const numeric=/^(0|[1-9]\d*)(\.\d+)?$/;
 if(!numeric.test(price)||!numeric.test(size)||!Number.isFinite(Number(price))||!Number.isFinite(Number(size))||Number(price)<=0||Number(size)<=0)throw Error('가격과 수량은 양수여야 합니다');
 const digits=s=>s.includes('.')?s.split('.')[1].replace(/0+$/,'').length:0;
 if(digits(size)>market.szDecimals)throw Error('수량 소수점 최대 '+market.szDecimals+'자리');
 if(digits(price)>(market.spot?8:6)-market.szDecimals)throw Error('가격 소수점 단위를 확인하세요');
 if(!Number.isInteger(Number(price))&&price.replace('.','').replace(/^0+/,'').replace(/0+$/,'').length>5)throw Error('가격은 유효숫자 최대 5자리');
 if(Number(price)*Number(size)<10)throw Error('주문 금액은 최소 10 USDC');
 if(!['Gtc','Alo'].includes(tif))throw Error('지원하지 않는 주문 유형');
 if(market.spot&&reduceOnly)throw Error('현물은 포지션 축소 전용을 지원하지 않습니다');
 const canonical=s=>s.includes('.')?s.replace(/0+$/,'').replace(/\.$/,''):s;
 return {a:market.asset,b:isBuy,p:canonical(price),s:canonical(size),r:reduceOnly,t:{limit:{tif}}};
}
export function freshMarket(m){return m?.network==='testnet'&&m?.book?.coin===m?.market?.value&&Date.now()-m.received<5000&&Math.abs(Date.now()-m.book.time)<10000;}
