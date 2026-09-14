
const $=id=>document.getElementById(id);
const PRICES={ETH:2506.18,USDC:1,DAI:0.9998,cbBTC:78442.1};
const INITIAL={ETH:10,USDC:10000,DAI:1000,cbBTC:0.1};
const KEY='qorvexa-preview-v2';
let state={balances:{...INITIAL},records:[],slippage:0.5,persist:true};
let account=null,provider=null,connecting=false,pending=null,timer;
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
function save(){try{if(state.persist)localStorage.setItem(KEY,JSON.stringify(state));else localStorage.removeItem(KEY)}catch{toast('브라우저 저장 실패: 현재 세션에서만 유지됩니다')}}
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
 if(!state.records.length){$('activity').textContent='아직 모의 거래가 없습니다.';$('activity').classList.add('empty')}
 for(const r of state.records){
 const div=document.createElement('div');div.className='pair';
 div.textContent=r.amount+' '+r.from+' → '+r.output.toFixed(8)+' '+r.to+' · SIMULATED · '+new Date(r.time).toLocaleString();
 $('activity').append(div);
 }
}
for(const id of ['pay','from','to'])$(id).addEventListener('input',render);
$('pay').setAttribute('aria-label','보낼 모의 토큰 수량');
$('receive').setAttribute('aria-label','예상 수령량');
$('from').setAttribute('aria-label','보낼 토큰');$('to').setAttribute('aria-label','받을 토큰');
$('flip').onclick=()=>{const a=$('from').value;$('from').value=$('to').value;$('to').value=a;render()};
$('swapBtn').onclick=()=>{
 const from=$('from').value,to=$('to').value,n=Number($('pay').value);
 if(!validAmount($('pay').value))return toast('0보다 큰 수량을 소수점 8자리 이내로 입력하세요');
 if(from===to)return toast('서로 다른 토큰을 선택하세요');
 if(n>state.balances[from])return toast('모의 잔액이 부족합니다');
 const out=quote();if(!Number.isFinite(out)||out<=0)return toast('유효하지 않은 견적입니다');
 pending={from,to,amount:n,output:out,expires:Date.now()+30000};
 $('dialogTitle').textContent='모의 거래 확인';
 $('resultText').textContent=n+' '+from+' → '+out.toFixed(8)+' '+to+' · 최소 수령 '+(out*(1-state.slippage/100)).toFixed(8)+' · 모의 수수료 0.05% · 견적 유효기간 30초';
 $('confirmBtn').hidden=false;$('confirmBtn').disabled=false;$('result').showModal();
};
$('confirmBtn').onclick=()=>{
 if(!pending)return;
 const r=pending;pending=null;
 if(Date.now()>r.expires){$('result').close();return toast('견적이 만료됐습니다. 다시 확인하세요')}
 if(r.amount>state.balances[r.from]){ $('result').close();return toast('모의 잔액이 부족합니다') }
 state.balances[r.from]-=r.amount;state.balances[r.to]+=r.output;
 state.records.unshift({...r,time:new Date().toISOString()});state.records=state.records.slice(0,200);
 save();render();$('confirmBtn').hidden=true;$('dialogTitle').textContent='SIMULATION COMPLETE';
 toast('모의 거래가 완료됐습니다. 실제 자산은 이동하지 않았습니다');
};
$('closeBtn').onclick=()=>{$('result').close();pending=null};
$('result').addEventListener('cancel',()=>{pending=null});
$('slippage').value=state.slippage;
$('slippage').onchange=()=>{const v=Number($('slippage').value);if(!Number.isFinite(v)||v<0.1||v>5){$('slippage').value=state.slippage;return toast('0.1~5% 범위로 입력하세요')}state.slippage=v;save()};
$('persist').checked=state.persist;$('persist').onchange=()=>{state.persist=$('persist').checked;save()};
$('clearBtn').onclick=()=>{if(confirm('저장된 모의 거래 기록을 삭제할까요? 잔액은 유지됩니다.')){state.records=[];save();render()}};
$('resetBtn').onclick=()=>{if(confirm('모의 잔액과 거래 기록을 초기화할까요?')){state.balances={...INITIAL};state.records=[];pending=null;save();render()}};
$('exportBtn').onclick=()=>{
 const csv=['time,from,to,amount,output,mode',...state.records.map(r=>[r.time,r.from,r.to,r.amount,r.output,'SIMULATION'].join(','))].join('\n');
 const url=URL.createObjectURL(new Blob([csv],{type:'text/csv;charset=utf-8'}));const a=document.createElement('a');a.href=url;a.download='qorvexa-simulation.csv';a.click();setTimeout(()=>URL.revokeObjectURL(url),1000);
};
render();
