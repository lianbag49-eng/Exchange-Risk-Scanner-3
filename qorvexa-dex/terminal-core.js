// Pure display and order-bound calculations. No wallet or network access.
export const finite=v=>v!==null&&v!==undefined&&v!==''&&Number.isFinite(Number(v));
export function fundingView(rate,now=Date.now()){
 const next=(Math.floor(now/3600000)+1)*3600000,seconds=Math.ceil((next-now)/1000);
 if(!finite(rate))return {label:'Unavailable',direction:'Funding data unavailable',next,countdown:`${String(Math.floor(seconds/60)).padStart(2,'0')}:${String(seconds%60).padStart(2,'0')}`};
 const n=Number(rate);return {label:`${n>=0?'+':''}${(n*100).toFixed(4)}%`,direction:n>0?'Longs pay shorts':n<0?'Shorts pay longs':'No payment at current rate',next,countdown:`${String(Math.floor(seconds/60)).padStart(2,'0')}:${String(seconds%60).padStart(2,'0')}`};
}
export function leverageRequest(meta,value,mode){
 const n=Number(value);if(!meta||meta.spot||!Number.isInteger(meta.asset))throw Error('Leverage is available for perpetuals only');
 if(!Number.isInteger(meta.maxLeverage)||meta.maxLeverage<1)throw Error('Maximum leverage is unavailable');
 if(!Number.isInteger(n)||n<1||n>meta.maxLeverage)throw Error(`Choose whole-number leverage from 1 to ${meta.maxLeverage}x`);
 if(!['cross','isolated'].includes(mode)||(meta.onlyIsolated&&mode==='cross'))throw Error('This market requires isolated margin');
 return {asset:meta.asset,leverage:n,isCross:mode==='cross'};
}
function decimal(s){s=String(s);if(!/^(0|[1-9]\d*)(\.\d+)?$/.test(s)||s.length>60)throw Error('Invalid decimal');const [a,b='']=s.split('.');return [BigInt(a+b),b.length];}
export function boundedPrice(meta,reference,isBuy,slippage){
 const [p,d]=decimal(reference),[sl,sd]=decimal(slippage);const scale=100n*10n**BigInt(sd);
 if(p<=0n||sl<=0n||sl>5n*10n**BigInt(sd))throw Error('Slippage must be above 0 and at most 5%');
 if(!Number.isInteger(meta?.szDecimals))throw Error('Market precision unavailable');
 const max=(meta.spot?8:6)-meta.szDecimals;if(max<0||max>8)throw Error('Invalid market precision');
 const num=p*(isBuy?scale+sl:scale-sl),den=10n**BigInt(d)*scale;
 const approximate=Number(num)/Number(den);const decimals=Math.min(max,Math.max(0,4-Math.floor(Math.log10(approximate))));
 const power=10n**BigInt(decimals);const raw=num*power;const units=isBuy?raw/den:(raw+den-1n)/den;
 if(units<=0n)throw Error('Price is below market precision');
 let s=units.toString().padStart(decimals+1,'0');if(decimals)s=s.slice(0,-decimals)+'.'+s.slice(-decimals);return s.includes('.')?s.replace(/0+$/,'').replace(/\.$/,''):s;
}
export function fundingCashflow(position,oracle,rate){if(![position,oracle,rate].every(finite))return null;return -Number(position)*Number(oracle)*Number(rate);}
