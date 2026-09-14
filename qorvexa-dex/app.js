
const $=id=>document.getElementById(id);
const PRICES={ETH:2506.18,USDC:1,DAI:0.9998,cbBTC:78442.1};
const INITIAL={ETH:10,USDC:10000,DAI:1000,cbBTC:0.1};
const KEY='qorvexa-preview-v2';
let state={balances:{...INITIAL},records:[],slippage:0.5,persist:true};
let pending=null,timer;
try{
 const x=JSON.parse(localStorage.getItem(KEY));
 if(x && Object.keys(INITIAL).every(k=>Number.isFinite(x.balances?.[k])&&x.balances[k]>=0) && Array.isArray(x.records)){
 state={balances:x.balances,records:x.records.filter(r=>PRICES[r.from]&&PRICES[r.to]&&Number.isFinite(r.amount)&&Number.isFinite(r.output)&&typeof r.time==='string').slice(0,200),slippage:Number.isFinite(x.slippage)&&x.slippage>=0.1&&x.slippage<=5?x.slippage:0.5,persist:true};
 }
}catch{}
function toast(s){$('toast').textContent=s;$('toast').classList.add('show');clearTimeout(timer);timer=setTimeout(()=>$('toast').classList.remove('show'),4000)}
window.toast=toast;
window.openPage=id=>{
 document.querySelectorAll('.page').forEach(x=>x.classList.toggle('active',x.id===id));
 document.querySelectorAll('.nav button').forEach(x=>{x.classList.toggle('active',x.dataset.page===id);x.setAttribute('aria-current',x.dataset.page===id?'page':'false')});
};
document.querySelectorAll('.nav button').forEach(b=>b.onclick=()=>window.openPage(b.dataset.page));
function save(){try{if(state.persist)localStorage.setItem(KEY,JSON.stringify(state));else localStorage.removeItem(KEY)}catch{toast('Storage unavailable: changes last for this session only')}}
function validAmount(value){return /^(?:0|[1-9]\d*)(?:\.\d{1,8})?$/.test(value)&&Number(value)>0&&Number.isFinite(Number(value))}
function quote(){
 const from=$('from').value,to=$('to').value,n=Number($('pay').value);
 return n*PRICES[from]/PRICES[to]*0.9995;
}
function render(){
 const from=$('from').value,to=$('to').value;
 const ok=validAmount($('pay').value)&&from!==to;
 $('receive').value=ok?quote().toFixed(8):'';
 $('balance').textContent='Paper balance '+state.balances[from].toFixed(8)+' '+from;
 $('rate').textContent='1 '+from+' = '+(PRICES[from]/PRICES[to]).toFixed(6)+' '+to+' (reference)';
 $('swapBtn').textContent='REVIEW SIMULATION';
 $('portfolioValue').textContent='$'+Object.keys(PRICES).reduce((n,k)=>n+state.balances[k]*PRICES[k],0).toFixed(2);
 $('activity').replaceChildren();$('activity').classList.remove('empty');
 if(!state.records.length){$('activity').textContent='No practice trades yet.';$('activity').classList.add('empty')}
 for(const r of state.records){
 const div=document.createElement('div');div.className='pair';
 div.textContent=r.amount+' '+r.from+' → '+r.output.toFixed(8)+' '+r.to+' · SIMULATED · '+new Date(r.time).toLocaleString("en-US");
 $('activity').append(div);
 }
}
for(const id of ['pay','from','to'])$(id).addEventListener('input',render);
$('pay').setAttribute('aria-label','Paper token amount to pay');
$('receive').setAttribute('aria-label','Estimated amount received');
$('from').setAttribute('aria-label','Token to pay');$('to').setAttribute('aria-label','Token to receive');
$('flip').onclick=()=>{const a=$('from').value;$('from').value=$('to').value;$('to').value=a;render()};
$('swapBtn').onclick=()=>{
 const from=$('from').value,to=$('to').value,n=Number($('pay').value);
 if(!validAmount($('pay').value))return toast('Enter an amount greater than zero with up to 8 decimal places');
 if(from===to)return toast('Choose different tokens');
 if(n>state.balances[from])return toast('Insufficient paper balance');
 const out=quote();if(!Number.isFinite(out)||out<=0)return toast('Invalid quote');
 pending={from,to,amount:n,output:out,expires:Date.now()+30000};
 $('dialogTitle').textContent='Confirm practice swap';
 $('resultText').textContent=n+' '+from+' → '+out.toFixed(8)+' '+to+' · Minimum received '+(out*(1-state.slippage/100)).toFixed(8)+' · Practice fee 0.05% · Quote expires in 30s';
 $('confirmBtn').hidden=false;$('confirmBtn').disabled=false;$('result').showModal();
};
$('confirmBtn').onclick=()=>{
 if(!pending)return;
 const r=pending;pending=null;
 if(Date.now()>r.expires){$('result').close();return toast('Quote expired. Review it again')}
 if(r.amount>state.balances[r.from]){ $('result').close();return toast('Insufficient paper balance') }
 state.balances[r.from]-=r.amount;state.balances[r.to]+=r.output;
 state.records.unshift({...r,time:new Date().toISOString()});state.records=state.records.slice(0,200);
 save();render();$('confirmBtn').hidden=true;$('dialogTitle').textContent='SIMULATION COMPLETE';
 toast('Practice swap complete. No real assets moved');
};
$('closeBtn').onclick=()=>{$('result').close();pending=null};
$('result').addEventListener('cancel',()=>{pending=null});
$('slippage').value=state.slippage;
$('slippage').onchange=()=>{const v=Number($('slippage').value);if(!Number.isFinite(v)||v<0.1||v>5){$('slippage').value=state.slippage;return toast('Enter a value from 0.1% to 5%')}state.slippage=v;save()};
$('persist').checked=state.persist;$('persist').onchange=()=>{state.persist=$('persist').checked;save()};
$('clearBtn').onclick=()=>{if(confirm('Clear practice history? Paper balances will stay unchanged.')){state.records=[];save();render()}};
$('resetBtn').onclick=()=>{if(confirm('Reset paper balances and practice history?')){state.balances={...INITIAL};state.records=[];pending=null;save();render()}};
$('exportBtn').onclick=()=>{
 const csv=['time,from,to,amount,output,mode',...state.records.map(r=>[r.time,r.from,r.to,r.amount,r.output,'SIMULATION'].join(','))].join('\n');
 const url=URL.createObjectURL(new Blob([csv],{type:'text/csv;charset=utf-8'}));const a=document.createElement('a');a.href=url;a.download='beltrix-practice.csv';a.click();setTimeout(()=>URL.revokeObjectURL(url),1000);
};
window.openPage('markets');render();
if('serviceWorker' in navigator)navigator.serviceWorker.register('./sw.js').catch(()=>{});
