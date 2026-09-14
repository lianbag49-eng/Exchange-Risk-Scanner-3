import {getAddress,isAddress,parseUnits,formatUnits,encodeFunctionData,erc20Abi} from 'viem';

export const ZERO='0x0000000000000000000000000000000000000000';
export const same=(a,b)=>typeof a==='string'&&typeof b==='string'&&a.toLowerCase()===b.toLowerCase();
export const short=a=>typeof a==='string'?a.slice(0,6)+'…'+a.slice(-4):'—';
export const hashOK=h=>/^0x[0-9a-f]{64}$/i.test(h||'');
export function address(value){
 const s=String(value||'').trim();
 if(!isAddress(s,{strict:true})||same(s,ZERO))throw Error('Enter a valid non-zero EVM address. Check every character.');
 return getAddress(s);
}
export function amount(value,decimals){
 const s=String(value||'').trim();
 if(!Number.isInteger(decimals)||decimals<0||decimals>36)throw Error('Unsupported token precision.');
 if(s.length>100||!/^(?:0|[1-9]\d*)(?:\.\d+)?$/.test(s))throw Error('Enter a positive decimal amount without commas or exponents.');
 if((s.split('.')[1]?.length||0)>decimals)throw Error(`This asset supports ${decimals} decimal places.`);
 const n=parseUnits(s,decimals);
 if(n<=0n||n>=2n**256n)throw Error('Amount is outside the supported range.');
 return n;
}
export function displayAmount(raw,decimals,max=8){
 if(raw===null||raw===undefined)return '—';
 try{const s=formatUnits(BigInt(raw),decimals),[w,f]=s.split('.');return w+(f?'.'+f.slice(0,max).replace(/0+$/,''):'').replace(/\.$/,'');}catch{return '—';}
}
export function transferRequest({from,to,token,quantity}){
 const sender=address(from),recipient=address(to);
 if(token.address==='native')return {from:sender,to:recipient,value:quantity,data:'0x'};
 return {from:sender,to:address(token.address),value:0n,data:encodeFunctionData({abi:erc20Abi,functionName:'transfer',args:[recipient,quantity]})};
}
export function revokeRequest({from,token,spender}){
 return {from:address(from),to:address(token),value:0n,data:encodeFunctionData({abi:erc20Abi,functionName:'approve',args:[address(spender),0n]})};
}
export function assertReview(review,context,now=Date.now()){
 if(!review||review.expires<now)throw Error('Review expired. Review the transaction again.');
 if(review.chainId!==context.chainId||!same(review.account,context.account)||review.epoch!==context.epoch||review.provider!==context.provider)throw Error('Account or network changed. Review again.');
 if(context.watchOnly)throw Error('Watch-only accounts cannot sign. Connect this address in your wallet.');
}
export function cleanText(value,max=80){return String(value??'').replace(/[\u0000-\u001f\u007f-\u009f\u202a-\u202e\u2066-\u2069]/g,'').slice(0,max);}
export function csvCell(value){const s=String(value??'');return '"'+(/^[=+\-@\t\r]/.test(s)?"'":'')+s.replace(/"/g,'""')+'"';}
export function normalizeHistory(x,owner,chainId,kind='normal'){
 const hash=x.hash||x.transaction_hash;
 if(!hashOK(hash))return null;
 const from=x.from?.hash||x.from||'',to=x.to?.hash||x.to||'';
 if(!same(from,owner)&&!same(to,owner))return null;
 const token=x.token;
 const isToken=kind==='tokens';
 const decimals=Number(token?.decimals??x.tokenDecimal??18);
 const value=String(x.total?.value??x.value??'0');
 if(!/^\d+$/.test(value)||!Number.isInteger(decimals)||decimals<0||decimals>36)return null;
 const failed=x.isError==='1'||x.txreceipt_status==='0'||x.status==='error';
 const pending=x.status==='pending'||x.block_number===null;
 const self=same(from,to);
 const outgoing=same(from,owner);
 const contract=!isToken&&kind!=='internal'&&value==='0'&&x.input&&x.input!=='0x';
 const time=x.timestamp?Date.parse(x.timestamp):Number(x.timeStamp)*1000;
 const id=[chainId,hash,isToken?'token':kind,x.log_index??x.logIndex??x.traceId??'',isToken?(token?.address_hash||x.contractAddress):'',from,to,value].join(':');
 return {id,hash,chainId,from,to,value,decimals,symbol:cleanText(token?.symbol||x.tokenSymbol||(chainId===56?'BNB':chainId===137?'POL':'ETH'),24),token:token?.address_hash||x.contractAddress||'native',kind:contract?'Contract':self?'Self transfer':outgoing?'Send':'Receive',direction:contract?'contract':self?'self':outgoing?'out':'in',status:failed?'Failed':pending?'Pending':'Confirmed',time:Number.isFinite(time)?time:null,fee:x.fee?.value??(x.gasUsed&&x.gasPrice?(BigInt(x.gasUsed)*BigInt(x.gasPrice)).toString():null),block:x.block_number??x.blockNumber??null,source:kind};
}
export function mergeHistory(...groups){
 const rows=[...new Map(groups.flat().filter(Boolean).map(r=>[r.id,r])).values()];
 // An ERC-20 transaction's zero-value envelope is not a second transfer.
 const transfers=new Set(rows.filter(r=>r.source==='tokens').map(r=>r.hash));
 return rows.filter(r=>!(r.kind==='Contract'&&transfers.has(r.hash))).sort((a,b)=>(b.time||0)-(a.time||0));
}
