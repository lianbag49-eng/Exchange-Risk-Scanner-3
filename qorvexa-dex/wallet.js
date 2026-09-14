import QRCode from 'qrcode';
import {erc20Abi,formatUnits,toHex,isAddress,decodeFunctionResult} from 'viem';
import {address,amount,displayAmount,transferRequest,revokeRequest,assertReview,same,short,hashOK,csvCell,cleanText,mergeHistory} from './wallet-core.js';
import {NETWORKS,network,DAPPS,readClient,readToken,currentHoldings,historyPage,checkAllowance,recentApprovals,hipMarkets,defiPools,hyperAccount} from './wallet-data.js';
import {mountWallet,icon} from './wallet-shell.js';

mountWallet();
const $=id=>document.getElementById(id),esc=s=>String(s??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const KEY='beltrix-wallet-v1',JOURNAL='beltrix-transactions-v1';
const money=n=>Number.isFinite(n)?n>0&&n<.01?'<$0.01':new Intl.NumberFormat('en-US',{style:'currency',currency:'USD',maximumFractionDigits:2}).format(n):'—';
const when=t=>t?new Date(t).toLocaleString('en-US',{month:'short',day:'numeric',hour:'numeric',minute:'2-digit'}):'Time unavailable';
const own=(o,k)=>Object.prototype.hasOwnProperty.call(o,k);
let prefs={network:1,hide:false,remember:true,accounts:[],imports:{},contacts:[],seen:0};
try{const p=JSON.parse(localStorage.getItem(KEY)||'null');if(p&&typeof p==='object')prefs={...prefs,network:NETWORKS.some(n=>n.chain.id===p.network)?p.network:1,hide:p.hide===true,remember:p.remember!==false,accounts:Array.isArray(p.accounts)?p.accounts.filter(x=>isAddress(x?.address||'')&&typeof x.name==='string').slice(0,20):[],imports:p.imports&&typeof p.imports==='object'?p.imports:{},contacts:Array.isArray(p.contacts)?p.contacts.filter(x=>isAddress(x?.address||'')&&typeof x.name==='string').slice(0,40):[],seen:Number(p.seen)||0};}catch{}
let journal=[];try{const rows=JSON.parse(localStorage.getItem(JOURNAL)||'[]');if(Array.isArray(rows))journal=rows.filter(r=>isAddress(r?.account||'')&&Number.isInteger(r.chainId)&&typeof r.id==='string'&&['Preparing','Unknown','Pending','Confirmed','Failed','Rejected'].includes(r.status)&&(!r.hash||hashOK(r.hash))).slice(0,200);}catch{}
let s={net:network(prefs.network),account:null,provider:null,providerName:'',watchOnly:true,epoch:0,tokens:[],updated:0,loading:false,busy:false,holdings:null,tab:'crypto',history:[],historyFilter:'all',historySearch:'',historyCursors:{},historyErrors:[],historyLoaded:false,approvals:[]};
let providers=[],modalEpoch=0,pending=null,stockRows=[],pools=[],stocksLoaded=false,poolsLoaded=false,dappFilter='All',historyLoading=false,historyEpoch=0;
const savePrefs=()=>{try{if(prefs.remember)localStorage.setItem(KEY,JSON.stringify(prefs));else localStorage.setItem(KEY,JSON.stringify({remember:false,network:prefs.network,hide:prefs.hide}));}catch{notify('Browser storage is unavailable. Settings last for this session.');}};
const saveJournal=()=>{try{localStorage.setItem(JOURNAL,JSON.stringify(journal.slice(0,200)));return true}catch{return false}};
function notify(text){window.toast?.(cleanText(text,240));}
function errorText(e){return cleanText(e?.shortMessage||e?.message||'The request could not be completed.',220);}
function status(id,text,type=''){const el=$(id);if(el){el.textContent=text;el.className='w-inline-status '+type;}}
function on(id,fn,event='click'){const el=$(id);if(el)el.addEventListener(event,async e=>{try{await fn(e)}catch(err){status('wFormStatus',errorText(err),'error');notify(errorText(err));}});}
function blank(title,body,button='',action='connect',symbol='wallet'){return `<div class="w-empty">${icon(symbol)}<strong>${esc(title)}</strong><p>${esc(body)}</p>${button?`<button class="w-primary" data-action="${action}">${esc(button)}</button>`:''}</div>`;}
function openDialog(title,html,wide=false){modalEpoch++;pending=null;$('wDialogTitle').textContent=title;$('wDialogBody').innerHTML=html;$('wDialog').classList.toggle('w-wide',wide);if(!$('wDialog').open)$('wDialog').showModal();return modalEpoch;}
function closeDialog(){modalEpoch++;pending=null;$('wDialog').close();}
on('wClose',closeDialog);$('wDialog').addEventListener('cancel',()=>{modalEpoch++;pending=null;});
function needed(sign=false){if(!s.account){showAccounts();return false}if(sign&&(!s.provider||s.watchOnly)){showAccounts();notify('Connect this address in your wallet to sign.');return false}return true}
function snapshot(){return {chainId:s.net.chain.id,account:s.account,provider:s.provider,epoch:s.epoch,watchOnly:s.watchOnly};}
function unchanged(epoch,account,chain){return s.epoch===epoch&&same(s.account,account)&&s.net.chain.id===chain;}
function publicClient(){return readClient(s.net,s.watchOnly?null:s.provider);}
function hasUncertain(){return journal.some(r=>r.chainId===s.net.chain.id&&same(r.account,s.account)&&['Preparing','Unknown'].includes(r.status));}
function currentJournal(){return journal.filter(r=>same(r.account,s.account)&&r.chainId===s.net.chain.id);}
function clearAccountData(){s.epoch++;s.tokens=[];s.holdings=null;s.updated=0;s.loading=false;s.history=[];s.historyCursors={};s.historyErrors=[];s.historyLoaded=false;s.approvals=[];historyLoading=false;historyEpoch++;pending=null;renderHome();}
function setAccount(account,watchOnly=true,name=''){
 const next=address(account);s.account=next;s.watchOnly=watchOnly;clearAccountData();
 if(!prefs.accounts.some(x=>same(x.address,next)))prefs.accounts.push({address:next,name:cleanText(name||`Account ${String(prefs.accounts.length+1).padStart(2,'0')}`,30)});
 prefs.accounts=prefs.accounts.slice(-20);savePrefs();renderHome();refreshWallet();
}
function detachProvider(){if(!s.provider)return;s.provider.removeListener?.('accountsChanged',accountEvent);s.provider.removeListener?.('chainChanged',chainEvent);s.provider.removeListener?.('disconnect',disconnectEvent);}
function disconnect(){detachProvider();s.provider=null;s.account=null;s.providerName='';s.watchOnly=true;clearAccountData();closeDialog();notify('Wallet disconnected from BELTRIX.');}
function accountEvent(accounts){closeDialog();if(!accounts?.[0]){disconnect();return}try{setAccount(accounts[0],false)}catch{disconnect()}}
function chainEvent(hex){closeDialog();const net=NETWORKS.find(n=>n.chain.id===Number(hex));if(!net){s.watchOnly=true;clearAccountData();notify('Unsupported signing network. Choose a supported network to reconnect.');return}s.net=net;prefs.network=net.chain.id;savePrefs();clearAccountData();refreshWallet();}
function disconnectEvent(){disconnect();}
function addProvider(provider,name,id){if(!provider?.request||providers.some(p=>p.provider===provider))return;providers.push({provider,name:cleanText(name||'Browser wallet',50),id:id||String(providers.length)});}
window.addEventListener('eip6963:announceProvider',e=>addProvider(e.detail?.provider,e.detail?.info?.name,e.detail?.info?.uuid));
window.dispatchEvent(new Event('eip6963:requestProvider'));
function discover(){addProvider(window.okxwallet,'OKX Wallet','okx');for(const p of window.ethereum?.providers||[])addProvider(p,p.isMetaMask?'MetaMask':p.isOkxWallet?'OKX Wallet':'Browser wallet');addProvider(window.ethereum,window.ethereum?.isMetaMask?'MetaMask':window.ethereum?.isOkxWallet?'OKX Wallet':'Browser wallet');}
async function connect(index){
 if(s.busy)throw Error('Complete the open wallet request first.');discover();const chosen=providers[index];if(!chosen)throw Error('No wallet provider is available.');
 const me=modalEpoch;s.busy=true;status('wFormStatus','Waiting for your wallet…');
 try{const accounts=await chosen.provider.request({method:'eth_requestAccounts'});const account=address(accounts?.[0]);const chainId=Number(await chosen.provider.request({method:'eth_chainId'}));const selected=NETWORKS.find(n=>n.chain.id===chainId);
 if(!selected)throw Error('Choose Ethereum, Arbitrum, Base, Optimism, BNB Chain, Polygon or Sepolia in your wallet, then connect again.');
 if(me!==modalEpoch)throw Error('Connection dismissed. Connect again.');detachProvider();s.provider=chosen.provider;s.providerName=chosen.name;s.net=selected;prefs.network=chainId;
 s.provider.on?.('accountsChanged',accountEvent);s.provider.on?.('chainChanged',chainEvent);s.provider.on?.('disconnect',disconnectEvent);
 setAccount(account,false);closeDialog();notify('Connected to '+chosen.name+'.');
 }finally{s.busy=false;}
}
function showAccounts(){
 discover();const html=`<p class="w-note">Connect a wallet or add a public watch-only address. BELTRIX never asks for your seed phrase.</p><div>${providers.length?providers.map((p,i)=>`<button class="w-provider" data-connect="${i}"><span>${esc(p.name)}<small>Connect through your installed wallet</small></span>${icon('arrow')}</button>`).join(''):`<div class="w-error">No wallet provider detected. On your phone, open BELTRIX inside the OKX Wallet or MetaMask browser. You can still add a watch-only address below.</div><button class="w-secondary full" style="margin-top:12px" data-action="copy-site">Copy BELTRIX URL</button>`}</div>${s.provider?'<button id="wChooseAccount" class="w-text-button">Choose another connected account</button> <button id="wDisconnect" class="w-text-button">Disconnect</button>':''}<hr class="w-divider"><div class="w-list-head"><span>Saved accounts</span><span>Public addresses only</span></div>${prefs.accounts.map((a,i)=>`<div class="w-row"><button class="w-provider" style="margin:5px 0" data-watch="${i}"><span>${esc(a.name)}<small>${esc(short(a.address))}${same(a.address,s.account)?' · Selected':''}</small></span>${icon('chevron')}</button><button class="w-icon-button" data-forget="${i}" aria-label="Remove ${esc(a.name)}">${icon('close')}</button></div>`).join('')}<label class="w-field">Account name<input id="wWatchName" maxlength="30" placeholder="My account"></label><label class="w-field">EVM address<input id="wWatchAddress" placeholder="0x…" autocomplete="off" spellcheck="false"></label><button id="wWatchAdd" class="w-primary full">Add watch-only account</button><p id="wFormStatus" class="w-inline-status" role="status"></p>`;
 openDialog('Your accounts',html);
 $('wDialogBody').querySelectorAll('[data-connect]').forEach(b=>b.onclick=()=>connect(Number(b.dataset.connect)).catch(e=>status('wFormStatus',errorText(e),'error')));
 $('wDialogBody').querySelectorAll('[data-watch]').forEach(b=>b.onclick=async()=>{const a=prefs.accounts[Number(b.dataset.watch)];let canSign=false;if(s.provider){try{const connected=await s.provider.request({method:'eth_accounts'});const chain=Number(await s.provider.request({method:'eth_chainId'}));canSign=same(connected[0],a.address)&&chain===s.net.chain.id;}catch{}}setAccount(a.address,!canSign);closeDialog();});
 $('wDialogBody').querySelectorAll('[data-forget]').forEach(b=>b.onclick=()=>{prefs.accounts.splice(Number(b.dataset.forget),1);savePrefs();showAccounts();});
 on('wWatchAdd',()=>{setAccount($('wWatchAddress').value,true,$('wWatchName').value.trim());closeDialog();});
 on('wDisconnect',disconnect);
 on('wChooseAccount',async()=>{await s.provider.request({method:'wallet_requestPermissions',params:[{eth_accounts:{}}]});const accounts=await s.provider.request({method:'eth_accounts'});setAccount(accounts[0],false);closeDialog();});
}
async function chooseNetwork(id){
 if(s.busy)throw Error('Complete the open wallet request first.');const net=network(id);
 if(s.provider&&!s.watchOnly){s.busy=true;try{await s.provider.request({method:'wallet_switchEthereumChain',params:[{chainId:toHex(net.chain.id)}]});}catch(e){if(e.code!==4902)throw e;await s.provider.request({method:'wallet_addEthereumChain',params:[{chainId:toHex(net.chain.id),chainName:net.name,nativeCurrency:net.chain.nativeCurrency,rpcUrls:[net.rpc],blockExplorerUrls:[net.explorer]}]});}finally{s.busy=false;}
  const actual=Number(await s.provider.request({method:'eth_chainId'}));if(actual!==net.chain.id)throw Error('Wallet did not switch networks.');
 }
 s.net=net;prefs.network=net.chain.id;savePrefs();clearAccountData();closeDialog();if(s.account)refreshWallet();
}
function showNetworks(){openDialog('Choose network',`<p class="w-note">Balances and history are scoped to the selected network. Send and receive on the exact same network.</p><div class="w-network-list">${NETWORKS.map(n=>`<button class="w-network-option ${n.chain.id===s.net.chain.id?'selected':''}" data-network="${n.chain.id}"><span class="w-network-dot" style="background:${n.color}"></span>${esc(n.name)}<small>${n.testnet?'Testnet':esc(n.symbol)}</small></button>`).join('')}</div><p id="wFormStatus" class="w-inline-status" role="status"></p>`);$('wDialogBody').querySelectorAll('[data-network]').forEach(b=>b.onclick=()=>chooseNetwork(Number(b.dataset.network)).catch(e=>status('wFormStatus',errorText(e),'error')));}
const marks={ETH:['#637cf3','◆'],USDT:['#35a886','₮'],USDC:['#337ddd','$'],WBTC:['#ee963d','₿'],BTC:['#ee963d','₿'],CBBTC:['#3267f0','₿'],BNB:['#caa42b','◇'],POL:['#8e68e1','P'],DAI:['#daa93c','◈']};
function tokenMark(t){const [color,letter]=marks[t.symbol?.toUpperCase()]||['#434a51',(t.symbol||'?').slice(0,2)];return `<span class="w-token-mark" style="background:${color}">${esc(letter)}</span>`;}
function renderHome(){
 document.body.classList.toggle('w-hidden-balances',prefs.hide);$('wNetworkName').textContent=s.net.name;$('wNetworkDot').style.background=s.net.color;
 const known=prefs.accounts.find(x=>same(x.address,s.account));$('wAccountName').textContent=known?.name||(s.account?short(s.account):'My wallet');$('wCopyAddress').disabled=!s.account;
 $('wAccountMode').textContent=s.account?`${s.watchOnly?'Watch-only':s.providerName} · ${short(s.account)}${s.net.testnet?' · Testnet':''}`:'Connect a wallet to view your assets';
 $('wAssetCount').textContent=`${s.tokens.length?s.tokens.length+' assets':'Assets'} on ${s.net.name}`;
 const valued=s.tokens.filter(t=>t.raw!==null&&t.price!==null),total=valued.reduce((n,t)=>n+Number(formatUnits(BigInt(t.raw),t.decimals))*t.price,0);
 const unpriced=s.tokens.filter(t=>t.raw!==null&&BigInt(t.raw)>0n&&t.price===null).length;
 $('wTotal').textContent=!s.account||!valued.length||s.net.testnet?'—':money(total);
 $('wTotalMeta').innerHTML=!s.account?'Your keys stay in your wallet.':s.loading?'<span class="live-dot"></span>Refreshing balances…':s.net.testnet?'Testnet assets have no real-world dollar value.':s.updated?`<span class="live-dot"></span>${esc(s.net.name)} · Updated ${esc(when(s.updated))}${unpriced?` · ${unpriced} unpriced assets excluded`:''}`:'Balance data is not available yet.';
 const unread=journal.filter(r=>r.created>prefs.seen).length;$('wUnread').hidden=!unread;$('wUnread').textContent=unread>99?'99+':String(unread);
 if(!s.account){$('wAssets').innerHTML=blank('Your wallet, all in one place','Connect to view your assets, send tokens and track your transfers.','Connect wallet');$('wPriceNote').textContent='';status('wAssetStatus','');return}
 if(!s.tokens.length){$('wAssets').innerHTML=blank(s.loading?'Loading your assets':'Assets unavailable',s.loading?'Reading the selected network.':'Refresh your wallet or import an ERC-20 token by its contract address.',s.loading?'':'Refresh','refresh');return}
 const sorted=[...s.tokens].sort((a,b)=>{if(a.address==='native')return -1;if(b.address==='native')return 1;return Number(BigInt(b.raw||'0')>0n)-Number(BigInt(a.raw||'0')>0n)});
 $('wAssets').innerHTML=sorted.map(t=>{const value=t.price!==null&&t.raw!==null?Number(formatUnits(BigInt(t.raw),t.decimals))*t.price:null;return `<button class="w-token-row" data-token="${esc(t.address)}">${tokenMark(t)}<span class="w-token-copy"><span class="w-token-name">${esc(t.symbol)}${s.net.testnet?'<span class="w-badge test">Testnet</span>':''}</span><span class="w-token-sub private-value">${esc(displayAmount(t.raw,t.decimals))} · ${esc(t.address==='native'?s.net.name:short(t.address))}</span></span><span class="w-token-value"><strong class="private-value">${esc(s.net.testnet?'—':money(value))}</strong><small>${t.price!==null&&!s.net.testnet?esc(money(t.price)):t.raw===null?'Balance unavailable':s.net.testnet?'Testnet token':'Price unavailable'}</small></span></button>`}).join('');
 $('wPriceNote').textContent='Estimated value on this network only. Token names are not proof of authenticity; check the contract address.';
 const notes=[];if(s.holdings?.balanceError)notes.push('Native balance unavailable.');if(s.holdings?.discoveryLimited)notes.push('Token discovery is partial. Import missing tokens by contract.');if(s.holdings?.importError)notes.push('Some imported balances could not be loaded.');status('wAssetStatus',notes.join(' '),notes.length?'error':'');
}
async function refreshWallet(){
 if(!s.account||s.loading)return;const epoch=s.epoch,account=s.account,chain=s.net.chain.id;const client=publicClient();s.loading=true;renderHome();
 try{const imported=Array.isArray(prefs.imports[String(chain)])?prefs.imports[String(chain)].filter(x=>isAddress(x)):[];
 const result=await currentHoldings(client,s.net,account,imported);if(!unchanged(epoch,account,chain))return;s.tokens=result.tokens;s.holdings=result;s.updated=result.updated;
 }catch(e){if(unchanged(epoch,account,chain))status('wAssetStatus',errorText(e),'error');}finally{if(unchanged(epoch,account,chain)){s.loading=false;renderHome();}}
}
async function copy(text){try{await navigator.clipboard.writeText(text);notify('Copied to clipboard.');}catch{openDialog('Copy text',`<p class="w-note">Select and copy this text.</p><textarea class="w-input" readonly rows="4">${esc(text)}</textarea>`);}}
function showToken(contract){
 const t=s.tokens.find(x=>same(x.address,contract)||x.address===contract);if(!t)return;
 openDialog(t.symbol,`${tokenMark(t)}<div class="w-review-amount private-value">${esc(displayAmount(t.raw,t.decimals,18))} ${esc(t.symbol)}</div><dl class="w-review-lines"><div><dt>Network</dt><dd>${esc(s.net.name)}</dd></div><div><dt>Asset</dt><dd>${esc(t.name)}</dd></div><div><dt>Contract</dt><dd>${t.address==='native'?'Native asset':esc(t.address)}</dd></div><div><dt>Price</dt><dd>${esc(money(t.price))}</dd></div></dl><div class="w-form-row"><button id="wTokenSend" class="w-primary">Send</button><button id="wTokenReceive" class="w-secondary">Receive</button></div><p class="w-note">${t.address==='native'?'Native network asset.':'Check the contract address. A familiar symbol does not verify a token.'}</p>${t.address!=='native'?`<a class="w-link" href="${s.net.explorer}/token/${t.address}" target="_blank" rel="noopener noreferrer">View token on explorer ↗</a>`:''}`);
 on('wTokenSend',()=>showSend(t.address));on('wTokenReceive',showReceive);
}
function showImport(){if(!needed())return;openDialog('Import a token',`<p class="w-note">Import an ERC-20 token on <b>${esc(s.net.name)}</b>. Verify the contract with the issuer. The balance is read directly from the token contract.</p><label class="w-field">Token contract address<input id="wTokenContract" placeholder="0x…" spellcheck="false" autocomplete="off"></label><button id="wImportRead" class="w-primary full">Look up token</button><div id="wImportResult"></div><p id="wFormStatus" class="w-inline-status" role="status"></p>`);
 const me=modalEpoch,epoch=s.epoch;
 on('wImportRead',async()=>{const contract=address($('wTokenContract').value);status('wFormStatus','Reading token contract…');const token=await readToken(publicClient(),contract,s.account);if(me!==modalEpoch||epoch!==s.epoch)return;
 $('wImportResult').innerHTML=`<dl class="w-review-lines"><div><dt>Symbol</dt><dd>${esc(token.symbol)}</dd></div><div><dt>Name</dt><dd>${esc(token.name)}</dd></div><div><dt>Decimals</dt><dd>${token.decimals}</dd></div><div><dt>Balance</dt><dd>${esc(displayAmount(token.raw,token.decimals))}</dd></div><div><dt>Contract</dt><dd>${esc(token.address)}</dd></div></dl><button id="wImportConfirm" class="w-secondary full">Add to my assets</button>`;status('wFormStatus','Token read successfully. Verify its contract before adding.');
 on('wImportConfirm',()=>{if(me!==modalEpoch||epoch!==s.epoch)return;const key=String(s.net.chain.id);prefs.imports[key]=[...new Set([...(Array.isArray(prefs.imports[key])?prefs.imports[key]:[]),contract])].slice(-25);savePrefs();s.tokens=[...s.tokens.filter(t=>!same(t.address,contract)),token];renderHome();closeDialog();notify('Token added.');});});
}
function showReceive(){
 if(!needed())return;const recipient=s.account,net=s.net;openDialog('Receive',`<p class="w-note">Receive assets on <b>${esc(net.name)}</b>${net.testnet?' testnet':''}.</p><canvas id="wReceiveQR" class="w-qr" aria-label="Receive address QR code"></canvas><div class="w-address-box" id="wReceiveAddress">${esc(recipient)}</div><button id="wReceiveCopy" class="w-primary full">${icon('copy')}Copy address</button><button id="wReceiveShare" class="w-secondary full" style="margin-top:10px">Share payment address</button><div class="w-error" style="margin-top:18px">Use the ${esc(net.name)} network at the sending wallet or exchange. Other networks may use the same address but have separate balances.</div><p class="w-note">QR includes chain ID ${net.chain.id}. Always check the network in the sending app. This is a wallet address, not a Hyperliquid deposit instruction.</p><a class="w-link" href="${net.explorer}/address/${recipient}" target="_blank" rel="noopener noreferrer">View address on explorer ↗</a>`);
 QRCode.toCanvas($('wReceiveQR'),`ethereum:${recipient}@${net.chain.id}`,{width:240,margin:2,color:{dark:'#111111',light:'#ffffff'}}).catch(()=>notify('QR could not be generated. Copy the address instead.'));
 on('wReceiveCopy',()=>copy(recipient));on('wReceiveShare',async()=>{const text=`${net.name} (${net.chain.id})\n${recipient}`;if(navigator.share){try{await navigator.share({title:'BELTRIX receive address',text});}catch(e){if(e.name!=='AbortError')throw e}}else await copy(text);});
}
async function guard(context){
 if(!context.provider||context.watchOnly)throw Error('Connect your wallet to sign this transaction.');
 const [accounts,chain]=await Promise.all([context.provider.request({method:'eth_accounts'}),context.provider.request({method:'eth_chainId'})]);
 if(!same(accounts?.[0],context.account)||Number(chain)!==context.chainId)throw Error('Wallet account or network changed. Reconnect and review again.');
 if(!unchanged(context.epoch,context.account,context.chainId)||s.provider!==context.provider||s.watchOnly)throw Error('Wallet selection changed. Review again.');
}
function showSend(contract='native'){
 if(!needed(true))return;if(s.busy)return notify('Complete the open wallet request first.');
 if(hasUncertain()){showNotifications();notify('An earlier submission is unconfirmed. Reconcile it before sending again.');return}
 const tokens=s.tokens.filter(t=>t.raw!==null);if(!tokens.length){notify('Load your assets before sending.');refreshWallet();return}
 const token=tokens.find(t=>t.address===contract)||tokens[0];
 openDialog('Send',`<p class="w-note">From ${esc(short(s.account))} · <b>${esc(s.net.name)}</b>${s.net.testnet?' · Testnet':''}</p><label class="w-field">Asset<select id="wSendAsset">${tokens.map(t=>`<option value="${esc(t.address)}" ${t.address===token.address?'selected':''}>${esc(t.symbol)} · ${esc(displayAmount(t.raw,t.decimals))}</option>`).join('')}</select></label><label class="w-field">Recipient address<input id="wSendTo" placeholder="0x…" spellcheck="false" autocomplete="off"></label><div class="w-row"><button class="w-text-button" id="wPasteRecipient">Paste</button><button class="w-text-button" id="wUseContact">Address book</button></div><label class="w-field">Amount<input id="wSendAmount" class="amount-input" inputmode="decimal" placeholder="0.00" autocomplete="off"></label><p class="w-note" id="wSendBalance">Available: ${esc(displayAmount(token.raw,token.decimals,18))} ${esc(token.symbol)}. Keep ${esc(s.net.symbol)} for gas.</p><button id="wSendReview" class="w-primary full">Review transfer</button><p id="wFormStatus" class="w-inline-status" role="status"></p><p class="w-note">${s.net.testnet?'Testnet tokens only.':'This transfers real assets on the selected network.'} Review the full address and amount before approving in your wallet.</p>`);
 on('wSendAsset',()=>{const t=tokens.find(t=>t.address===$('wSendAsset').value);$('wSendBalance').textContent=`Available: ${displayAmount(t.raw,t.decimals,18)} ${t.symbol}. Keep ${s.net.symbol} for gas.`;},'change');
 on('wPasteRecipient',async()=>{$('wSendTo').value=(await navigator.clipboard.readText()).trim();});
 on('wUseContact',()=>{const target=$('wSendTo');if(!prefs.contacts.length){notify('Add a recipient in More → Address book.');return}const box=document.createElement('div');box.className='w-section-card';box.innerHTML=prefs.contacts.map((c,i)=>`<button class="w-provider" data-recipient="${i}"><span>${esc(c.name)}<small>${esc(c.address)}</small></span></button>`).join('');target.parentNode.after(box);box.querySelectorAll('button').forEach(b=>b.onclick=()=>{target.value=prefs.contacts[Number(b.dataset.recipient)].address;box.remove();});});
 on('wSendReview',async()=>{
  if(s.busy)return;const context=snapshot(),me=modalEpoch,selected=tokens.find(t=>t.address===$('wSendAsset').value),recipient=address($('wSendTo').value),input=$('wSendAmount').value;
  if(!selected)throw Error('Choose an asset.');s.busy=true;$('wSendReview').disabled=true;status('wFormStatus','Checking balance and estimating gas…');
  try{await guard(context);const client=readClient(s.net,context.provider);const t=selected.address==='native'?{...selected,raw:(await client.getBalance({address:context.account})).toString()}:await readToken(client,selected.address,context.account);
   const quantity=amount(input,t.decimals);if(quantity>BigInt(t.raw))throw Error('Insufficient token balance.');
   const request=transferRequest({from:context.account,to:recipient,token:t,quantity});
   const review=await prepareReview(context,request,{kind:'Send',token:t,quantity:quantity.toString(),recipient});
   if(me!==modalEpoch)throw Error('Transfer form was dismissed. Review again.');await guard(context);showReview(review);
  }finally{s.busy=false;if($('wSendReview'))$('wSendReview').disabled=false;}
 });
}
async function prepareReview(context,request,detail){
 const net=network(context.chainId),client=readClient(net,context.provider);
 const [gas,price,balance,nonce,code]=await Promise.all([
  client.estimateGas({account:context.account,to:request.to,value:request.value,data:request.data}),client.getGasPrice(),client.getBalance({address:context.account}),client.getTransactionCount({address:context.account,blockTag:'pending'}),detail.kind==='Send'?client.getCode({address:detail.recipient}):Promise.resolve('0x')]);
 if(gas<=0n||price<=0n)throw Error('A reliable gas estimate is unavailable.');
 const gasLimit=gas*120n/100n,gasPrice=price*125n/100n,fee=gasLimit*gasPrice;
 if(balance<request.value+fee)throw Error('Insufficient native balance for the amount and gas budget.');
 if(request.data!=='0x'){
  const simulation=await client.call({account:context.account,to:request.to,value:request.value,data:request.data});
  if(simulation.data&&simulation.data!=='0x'){
   const ok=decodeFunctionResult({abi:erc20Abi,functionName:detail.kind==='Revoke'?'approve':'transfer',data:simulation.data});
   if(ok!==true)throw Error('The token contract rejected this operation.');
  }
 }
 await guard(context);
 return {...context,...detail,request,gas:gasLimit,gasPrice,fee,nonce,contractRecipient:!!code&&code!=='0x',expires:Date.now()+90000};
}
function showReview(review){
 const net=network(review.chainId),isRevoke=review.kind==='Revoke';
 openDialog(isRevoke?'Review approval revocation':'Review transfer',`<span class="w-badge ${net.testnet?'test':'neutral'}">${esc(net.name)}${net.testnet?' · Testnet':' · Mainnet'}</span><div class="w-review-amount">${isRevoke?'Revoke '+esc(review.token.symbol):esc(displayAmount(review.quantity,review.token.decimals,36))+' '+esc(review.token.symbol)}</div><dl class="w-review-lines"><div><dt>From</dt><dd>${esc(review.account)}</dd></div><div><dt>${isRevoke?'Spender':'Recipient'}</dt><dd>${esc(review.recipient)}</dd></div>${review.token.address!=='native'?`<div><dt>Token contract</dt><dd>${esc(review.token.address)}</dd></div>`:''}<div><dt>Network</dt><dd>${esc(net.name)} · ${review.chainId}</dd></div><div><dt>Gas budget</dt><dd>${esc(displayAmount(review.fee,18,12))} ${esc(net.symbol)}</dd></div><div><dt>Nonce</dt><dd>${review.nonce}</dd></div>${isRevoke?'<div><dt>New allowance</dt><dd>0</dd></div>':''}</dl>${review.contractRecipient?'<div class="w-error">Recipient is a smart contract. Confirm that it supports receiving this asset on this network.</div>':''}${same(review.account,review.recipient)?'<div class="w-error">This sends to your own address. Network gas will still be charged.</div>':''}<p class="w-note">Gas budget includes an execution buffer. L2 data fees may be additional; review the final total in your wallet. This review expires after 90 seconds.</p><label class="w-check"><input id="wReviewAck" type="checkbox"><span>I checked the full ${isRevoke?'spender and token contract':'recipient address'}, network and ${isRevoke?'revocation':'amount'}.</span></label><button id="wSignTransfer" class="w-primary full" disabled>${isRevoke?'Revoke in wallet':'Confirm in wallet'}</button><p id="wFormStatus" class="w-inline-status" role="status"></p>`);
 pending=review;on('wReviewAck',()=>{$('wSignTransfer').disabled=!$('wReviewAck').checked||s.busy;},'change');
 on('wSignTransfer',submitReviewed);
}
async function submitReviewed(){
 if(s.busy||!pending||!$('wReviewAck')?.checked)return;const review=pending;pending=null;s.busy=true;$('wSignTransfer').disabled=true;let record=null;
 try{
  assertReview(review,snapshot());await guard(review);if(hasUncertain())throw Error('Reconcile the previous unconfirmed submission first.');
  const client=readClient(network(review.chainId),review.provider);
  const [nonce,balance,gas,price]=await Promise.all([client.getTransactionCount({address:review.account,blockTag:'pending'}),client.getBalance({address:review.account}),client.estimateGas({account:review.account,to:review.request.to,value:review.request.value,data:review.request.data}),client.getGasPrice()]);
  if(nonce!==review.nonce)throw Error('Account nonce changed. Review again.');
  if(gas>review.gas||price>review.gasPrice)throw Error('Network fees changed. Review again.');
  if(balance<review.request.value+review.fee)throw Error('Native balance changed. Review again.');
  if(review.kind==='Send'&&review.token.address!=='native'){
   const tokenBalance=await client.readContract({address:review.token.address,abi:erc20Abi,functionName:'balanceOf',args:[review.account]});
   if(tokenBalance<BigInt(review.quantity))throw Error('Token balance changed. Review again.');
  }
  assertReview(review,snapshot());await guard(review);
  record={id:crypto.randomUUID(),account:review.account,chainId:review.chainId,kind:review.kind,recipient:review.recipient,token:review.token.address,symbol:review.token.symbol,decimals:review.token.decimals,value:review.quantity||'0',created:Date.now(),nonce:review.nonce,status:'Preparing',hash:null};
  journal.unshift(record);journal=journal.slice(0,200);if(!saveJournal()){journal=journal.filter(r=>r!==record);record=null;throw Error('Local transaction tracking is unavailable. Enable browser storage before submitting.');}
  status('wFormStatus','Approve this transaction in your wallet. Do not submit it again while it is pending.');
  const tx={from:review.account,to:review.request.to,value:toHex(review.request.value),data:review.request.data,chainId:toHex(review.chainId),gas:toHex(review.gas),gasPrice:toHex(review.gasPrice),nonce:toHex(review.nonce)};
  // A single wallet-mediated send. Ambiguous responses are never automatically retried.
  const hash=await review.provider.request({method:'eth_sendTransaction',params:[tx]});
  if(!hashOK(hash))throw Error('Wallet returned no verifiable transaction hash.');
  record.hash=hash;record.status='Pending';saveJournal();renderHome();
  openDialog('Transaction submitted',`${blank('Waiting for confirmation','Your wallet returned a transaction hash. Confirmation may take a few minutes.','','','history')}<div class="w-address-box">${esc(hash)}</div><a class="w-primary full" href="${network(record.chainId).explorer}/tx/${hash}" target="_blank" rel="noopener noreferrer">View transaction ↗</a><button class="w-secondary full" style="margin-top:12px" data-action="notifications">Check status</button>`);
  pollJournal().catch(()=>{});
 }catch(e){
  if(record){record.status=e.code===4001||e.cause?.code===4001?'Rejected':'Unknown';record.error=errorText(e);saveJournal();renderHome();}
  status('wFormStatus',errorText(e)+(record?.status==='Unknown'?' Submission may have reached the network. Open Notifications to reconcile it before retrying.':''),'error');
  notify(errorText(e));
 }finally{s.busy=false;}
}
async function pollJournal(){
 const rows=journal.filter(r=>r.hash&&r.status==='Pending').slice(0,20);
 for(const r of rows){try{const client=readClient(network(r.chainId),s.provider&&!s.watchOnly&&s.net.chain.id===r.chainId?s.provider:null);const receipt=await client.getTransactionReceipt({hash:r.hash});if(receipt){r.status=receipt.status==='success'?'Confirmed':'Failed';r.block=receipt.blockNumber.toString();r.updated=Date.now();saveJournal();renderHome();}}catch{}}
}
function showNotifications(){
 prefs.seen=Date.now();savePrefs();renderHome();
 openDialog('Notifications',`<div class="w-list-head"><span>Transactions submitted from this browser</span><button id="wPoll" class="w-text-button">Refresh status</button></div>${journal.length?journal.map((r,i)=>`<div class="w-approval"><div class="w-row"><strong>${esc(r.kind)} ${esc(r.symbol)}</strong><span class="w-badge ${r.status==='Failed'?'test':'neutral'}">${esc(r.status)}</span></div><p class="w-note">${esc(network(r.chainId).name)} · ${esc(when(r.created))}<br>From ${esc(short(r.account))} · Nonce ${r.nonce}</p>${r.hash?`<a class="w-link" href="${network(r.chainId).explorer}/tx/${r.hash}" target="_blank" rel="noopener noreferrer">${esc(short(r.hash))} ↗</a>`:''}${['Preparing','Unknown'].includes(r.status)?`<p class="w-error">The wallet result is unconfirmed. Do not repeat this transfer. Find it in your wallet activity and provide its transaction hash.</p><button class="w-secondary" data-reconcile="${i}">Reconcile submission</button>`:''}</div>`).join(''):blank('No transaction notifications','Transactions submitted through BELTRIX appear here. Blockchain history is available separately.','','','bell')}<button class="w-secondary full" data-action="history">Open blockchain history</button><p id="wFormStatus" class="w-inline-status" role="status"></p>`);
 on('wPoll',async()=>{status('wFormStatus','Checking confirmations…');await pollJournal();showNotifications();});
 $('wDialogBody').querySelectorAll('[data-reconcile]').forEach(b=>b.onclick=()=>showReconcile(journal[Number(b.dataset.reconcile)]));
}
function showReconcile(record){
 openDialog('Reconcile submission',`<p class="w-note">Find the matching transaction in your wallet activity on <b>${esc(network(record.chainId).name)}</b>. BELTRIX verifies its sender and nonce before unlocking further transfers.</p><p class="w-note">Sender: ${esc(record.account)}<br>Nonce: ${record.nonce}</p><label class="w-field">Transaction hash<input id="wReconcileHash" placeholder="0x…" spellcheck="false"></label><button id="wReconcileCheck" class="w-primary full">Verify transaction</button><hr class="w-divider"><label class="w-check"><input id="wReconcileRejected" type="checkbox"><span>I checked my wallet activity and explicitly rejected or cancelled the signature before any transaction was submitted. I understand a delayed transaction could otherwise cause a duplicate transfer.</span></label><button id="wReconcileReject" class="w-secondary full" disabled>Mark as rejected in wallet</button><p id="wFormStatus" class="w-inline-status" role="status"></p>`);
 const me=modalEpoch;
 on('wReconcileCheck',async()=>{const hash=$('wReconcileHash').value.trim();if(!hashOK(hash))throw Error('Enter a valid transaction hash.');const client=readClient(network(record.chainId),s.provider&&!s.watchOnly&&s.net.chain.id===record.chainId?s.provider:null);const tx=await client.getTransaction({hash});if(!same(tx.from,record.account)||tx.nonce!==record.nonce)throw Error('Sender or nonce does not match this submission.');record.hash=hash;record.status='Pending';saveJournal();await pollJournal();if(me===modalEpoch)showNotifications();});
 on('wReconcileRejected',()=>{$('wReconcileReject').disabled=!$('wReconcileRejected').checked;},'change');on('wReconcileReject',()=>{if(!$('wReconcileRejected').checked)return;record.status='Rejected';record.updated=Date.now();saveJournal();showNotifications();});
}
function allHistory(){
 const hashes=new Set(s.history.map(r=>r.hash));
 const local=currentJournal().filter(r=>r.hash&&!hashes.has(r.hash)&&r.status!=='Rejected').map(r=>({id:r.id,hash:r.hash,chainId:r.chainId,from:r.account,to:r.recipient,value:r.value,decimals:r.decimals,symbol:r.symbol,token:r.token,kind:r.kind,direction:'out',status:r.status,time:r.created,source:'local',fee:null,block:r.block}));
 return mergeHistory(s.history,local);
}
function filteredHistory(){
 let rows=allHistory();const q=s.historySearch.toLowerCase();if(q)rows=rows.filter(r=>[r.hash,r.from,r.to,r.symbol].some(v=>String(v).toLowerCase().includes(q)));
 if(s.historyFilter!=='all')rows=rows.filter(r=>s.historyFilter==='send'?r.direction==='out':s.historyFilter==='receive'?r.direction==='in':r.status.toLowerCase()===s.historyFilter);
 const since=$('wHistorySince')?.value;if(since){const min=Date.parse(since+'T00:00:00');rows=rows.filter(r=>r.time>=min);}
 return rows;
}
function historyHTML(r){const incoming=r.direction==='in',signed=incoming?'+':r.direction==='out'?'−':'';return `<button class="w-history-row" data-history-id="${esc(r.id)}"><span class="w-history-symbol ${incoming?'in':''}">${icon(incoming?'receive':r.kind==='Contract'||r.kind==='Revoke'?'shield':'send')}</span><span class="w-history-copy"><strong>${esc(r.kind)} ${esc(r.symbol)}</strong><small>${esc(when(r.time))} · ${esc(short(incoming?r.from:r.to))}</small></span><span class="w-history-amount"><strong>${signed}${esc(displayAmount(r.value,r.decimals))} ${esc(r.symbol)}</strong><small class="${r.status.toLowerCase()}">${esc(r.status)}</small></span></button>`;}
function showHistory(){
 if(!needed())return;openDialog('History',`<div class="w-row"><p class="w-note">${esc(s.net.name)} · ${esc(short(s.account))}</p><button id="wHistoryRefresh" class="w-text-button">Refresh</button></div><div class="w-history-tools"><input id="wHistorySearch" class="w-input" placeholder="Search token, address or TXID" aria-label="Search history" value="${esc(s.historySearch)}"><input id="wHistorySince" class="w-input" type="date" aria-label="History from date"></div><div class="w-pills" id="wHistoryFilters">${[['all','All'],['send','Send'],['receive','Receive'],['pending','Pending'],['failed','Failed']].map(([v,t])=>`<button data-history-filter="${v}" class="${s.historyFilter===v?'active':''}">${t}</button>`).join('')}</div><p id="wHistoryStatus" class="w-inline-status" role="status"></p><div id="wHistoryRows"></div><div class="w-pagination"><button id="wHistoryMore" class="w-secondary">Load earlier history</button></div><div class="w-row"><button id="wHistoryExport" class="w-text-button">${icon('download')} Export loaded CSV</button><a class="w-link" href="${s.net.explorer}/address/${s.account}" target="_blank" rel="noopener noreferrer">Open explorer ↗</a></div><p class="w-note">Normal transfers, ERC-20 transfers and internal native transfers are queried separately. More pages may exist. Explorer indexing can lag; pending transactions sent elsewhere may be absent.</p>`,true);
 on('wHistorySearch',()=>{s.historySearch=$('wHistorySearch').value;renderHistory();},'input');on('wHistorySince',renderHistory,'change');
 $('wHistoryFilters').querySelectorAll('button').forEach(b=>b.onclick=()=>{s.historyFilter=b.dataset.historyFilter;$('wHistoryFilters').querySelectorAll('button').forEach(x=>x.classList.toggle('active',x===b));renderHistory();});
 on('wHistoryRefresh',()=>loadHistory(true));on('wHistoryMore',()=>loadHistory(false));on('wHistoryExport',exportHistory);
 renderHistory();if(!s.historyLoaded&&!historyLoading)loadHistory(true);
}
function renderHistory(){
 if(!$('wHistoryRows'))return;const rows=filteredHistory();
 $('wHistoryRows').innerHTML=rows.length?rows.map(historyHTML).join(''):blank(historyLoading?'Loading transactions':s.historyErrors.length?'History is unavailable':'No matching transfers',historyLoading?'Reading transaction and token-transfer indexes.':s.historyErrors.length?'The data source did not return a complete result. Refresh or open the explorer.':'No transfers match this filter in the loaded pages.','','','history');
 const more=['normal','tokens','internal'].some(k=>s.historyCursors[k]?.next||!own(s.historyCursors,k));$('wHistoryMore').hidden=!more;$('wHistoryMore').disabled=historyLoading;$('wHistoryMore').textContent=historyLoading?'Loading…':'Load earlier history';
 status('wHistoryStatus',historyLoading?'Loading history…':`${allHistory().length} records loaded${s.historyErrors.length?' · Incomplete sources: '+s.historyErrors.join(', '):''}`,s.historyErrors.length?'error':'');
 $('wHistoryRows').querySelectorAll('[data-history-id]').forEach(b=>b.onclick=()=>{const row=allHistory().find(r=>r.id===b.dataset.historyId);if(row)showTransaction(row);});
}
async function loadHistory(reset){
 if(historyLoading||!s.account)return;historyLoading=true;const epoch=s.epoch,account=s.account,net=s.net,ticket=++historyEpoch;
 if(reset){s.history=[];s.historyCursors={};s.historyErrors=[];}renderHistory();
 const kinds=['normal','tokens','internal'].filter(k=>reset||!own(s.historyCursors,k)||s.historyCursors[k]?.next);
 const results=await Promise.allSettled(kinds.map(k=>historyPage(net,account,k,reset?null:s.historyCursors[k])));
 if(!unchanged(epoch,account,net.chain.id)||ticket!==historyEpoch)return;
 results.forEach((r,i)=>{const kind=kinds[i];s.historyErrors=s.historyErrors.filter(x=>x!==kind);if(r.status==='fulfilled'){s.history=mergeHistory(s.history,r.value.items);s.historyCursors[kind]={next:r.value.next,source:r.value.source};}else{s.historyErrors.push(kind);}});
 s.historyLoaded=true;historyLoading=false;renderHistory();
}
function showTransaction(r){
 openDialog('Transaction details',`<span class="w-badge ${r.status==='Failed'?'test':'neutral'}">${esc(r.status)}</span><div class="w-review-amount">${esc(displayAmount(r.value,r.decimals,36))} ${esc(r.symbol)}</div><dl class="w-review-lines"><div><dt>Type</dt><dd>${esc(r.kind)}</dd></div><div><dt>Network</dt><dd>${esc(network(r.chainId).name)}</dd></div><div><dt>From</dt><dd>${esc(r.from)}</dd></div><div><dt>To</dt><dd>${esc(r.to||'Contract creation')}</dd></div><div><dt>TXID</dt><dd>${esc(r.hash)}</dd></div>${r.token!=='native'?`<div><dt>Token</dt><dd>${esc(r.token)}</dd></div>`:''}<div><dt>Time</dt><dd>${r.time?esc(new Date(r.time).toLocaleString('en-US')):'Unavailable'}</dd></div><div><dt>Network fee</dt><dd>${r.fee!==null?esc(displayAmount(r.fee,18,14))+' '+esc(network(r.chainId).symbol):'Not returned by index'}</dd></div><div><dt>Block</dt><dd>${esc(r.block??'Pending / unknown')}</dd></div></dl><a class="w-primary full" href="${network(r.chainId).explorer}/tx/${r.hash}" target="_blank" rel="noopener noreferrer">View on explorer ↗</a><div class="w-form-row" style="margin-top:12px"><button id="wCopyTx" class="w-secondary">Copy TXID</button><button class="w-secondary" data-action="history">Back to history</button></div>`);
 on('wCopyTx',()=>copy(r.hash));
}
function download(text,name,type){const url=URL.createObjectURL(new Blob([text],{type})),a=document.createElement('a');a.href=url;a.download=name;a.click();setTimeout(()=>URL.revokeObjectURL(url),1000);}
function exportHistory(){
 const rows=filteredHistory();if(!rows.length){notify('There are no loaded records to export.');return}
 const header=['time_utc','network','chain_id','type','status','symbol','amount','from','to','tx_hash','token_contract','source'];
 const lines=rows.map(r=>[r.time?new Date(r.time).toISOString():'',network(r.chainId).name,r.chainId,r.kind,r.status,r.symbol,formatUnits(BigInt(r.value),r.decimals),r.from,r.to,r.hash,r.token,r.source].map(csvCell).join(','));
 download([header.join(','),...lines].join('\r\n'),'BELTRIX-'+s.net.chain.id+'-loaded-history.csv','text/csv;charset=utf-8');notify('Exported '+rows.length+' loaded records.');
}
function showApprovals(){
 if(!needed())return;openDialog('Token approvals',`<p class="w-note">${esc(s.net.name)} · ${esc(short(s.account))}. Check ERC-20 allowances by token and spender, or scan the most recent 2,000 blocks.</p><div class="w-form-row"><label class="w-field">Token contract<input id="wApprovalToken" placeholder="0x…" spellcheck="false"></label><label class="w-field">Spender address<input id="wApprovalSpender" placeholder="0x…" spellcheck="false"></label></div><div class="w-form-row"><button id="wApprovalCheck" class="w-primary">Check allowance</button><button id="wApprovalScan" class="w-secondary">Scan recent approvals</button></div><p id="wFormStatus" class="w-inline-status" role="status"></p><div id="wApprovalRows"></div><p class="w-note">This view is not a complete approval audit. The recent scan excludes older approvals and NFT operators. RPC limits can reduce coverage.</p><button class="w-text-button" data-dapp="revoke">Open broader approval explorer ↗</button>`,true);
 const me=modalEpoch,epoch=s.epoch;
 function draw(){if(me!==modalEpoch||epoch!==s.epoch)return;$('wApprovalRows').innerHTML=s.approvals.map((a,i)=>`<div class="w-approval"><div class="w-row"><strong>${esc(a.symbol)}</strong><span class="w-badge neutral">${BigInt(a.allowance)>2n**200n?'Unlimited / very high':esc(displayAmount(a.allowance,a.decimals,18))}</span></div><p class="w-note">Token</p><code>${esc(a.address)}</code><p class="w-note">Spender</p><code>${esc(a.spender)}</code><button class="w-secondary" data-revoke="${i}" ${BigInt(a.allowance)===0n?'disabled':''}>${BigInt(a.allowance)===0n?'No allowance':'Revoke allowance'}</button></div>`).join('');$('wApprovalRows').querySelectorAll('[data-revoke]').forEach(b=>b.onclick=()=>reviewRevoke(s.approvals[Number(b.dataset.revoke)]).catch(e=>status('wFormStatus',errorText(e),'error')));}
 draw();on('wApprovalCheck',async()=>{const token=address($('wApprovalToken').value),spender=address($('wApprovalSpender').value);status('wFormStatus','Reading current allowance…');const row=await checkAllowance(publicClient(),token,s.account,spender);if(me!==modalEpoch||epoch!==s.epoch)return;s.approvals=[row,...s.approvals.filter(a=>!(same(a.address,token)&&same(a.spender,spender)))];draw();status('wFormStatus',BigInt(row.allowance)===0n?'This token currently has no allowance for that spender.':'Current allowance verified on-chain.','success');});
 on('wApprovalScan',async()=>{status('wFormStatus','Scanning recent approval events…');$('wApprovalScan').disabled=true;try{const result=await recentApprovals(publicClient(),s.account);if(me!==modalEpoch||epoch!==s.epoch)return;s.approvals=result.items;draw();status('wFormStatus',`${result.items.length} active allowances found in blocks ${result.start}–${result.end}. Older allowances are not included.${result.failed?' '+result.failed+' contracts could not be checked.':''}${result.truncated?' Results capped at 40 token/spender pairs.':''}`);}finally{if($('wApprovalScan'))$('wApprovalScan').disabled=false;}});
}
async function reviewRevoke(token){
 if(!needed(true)||s.busy)return;if(hasUncertain())throw Error('Reconcile the previous unconfirmed submission first.');const context=snapshot(),me=modalEpoch;s.busy=true;
 try{await guard(context);status('wFormStatus','Simulating allowance revocation…');const allowance=await checkAllowance(publicClient(),token.address,context.account,token.spender);if(BigInt(allowance.allowance)===0n)throw Error('Allowance is already zero.');const request=revokeRequest({from:context.account,token:token.address,spender:token.spender});const review=await prepareReview(context,request,{kind:'Revoke',token:allowance,recipient:token.spender,quantity:'0'});if(me!==modalEpoch)throw Error('Approval view changed. Review again.');showReview(review);}finally{s.busy=false;}
}
function dappHTML(d){return `<button class="w-dapp" data-dapp="${d.id}"><span class="w-token-mark" style="background:${d.color};color:#121417">${esc(d.letter)}</span><span><strong>${esc(d.name)}</strong><small>${esc(d.tag)}</small></span>${icon('external')}</button>`;}
function renderDapps(){const defi=DAPPS.filter(d=>d.category==='DeFi');$('wHomeDapps').innerHTML=DAPPS.slice(0,6).map(dappHTML).join('');$('wPositionDapps').innerHTML=defi.map(dappHTML).join('');$('wDefiDapps').innerHTML=defi.map(dappHTML).join('');$('wExploreDapps').innerHTML=DAPPS.filter(d=>dappFilter==='All'||d.category===dappFilter).map(dappHTML).join('');}
function showDapp(id){
 const d=DAPPS.find(x=>x.id===id);if(!d)return;openDialog('Open '+d.name,`${tokenMark({symbol:d.letter})}<div class="w-section-card"><h2>${esc(d.name)}</h2><p>${esc(d.description)}</p><code class="w-address-box" style="display:block">${esc(new URL(d.url).hostname)}</code></div><p class="w-note">You are opening an external application. Its balances, rates and eligibility rules are provided by that service. Check the domain and wallet approvals.</p><a class="w-primary full" href="${d.url}" target="_blank" rel="noopener noreferrer">Continue to ${esc(d.name)} ↗</a>`);
}
async function loadStocks(force=false){
 if(stocksLoaded&&!force){renderStocks();return}$('wStocks').innerHTML=blank('Loading equity markets','Reading live Hyperliquid builder-market metadata.','','','compass');
 try{stockRows=await hipMarkets();stocksLoaded=true;renderStocks();}catch(e){$('wStocks').innerHTML=blank('Market feed unavailable',errorText(e),'Retry','refresh-stocks','compass');}
}
function renderStocks(){
 $('wStocks').innerHTML=stockRows.length?stockRows.map((r,i)=>`<button class="w-token-row" data-stock="${i}"><span class="w-token-mark">${esc(r.ticker.slice(0,2))}</span><span class="w-token-copy"><span class="w-token-name">${esc(r.ticker)}<span class="w-badge neutral">Perpetual</span></span><span class="w-token-sub">${esc(r.dex)} · ${esc(r.name)}</span></span><span class="w-token-value"><strong>${esc(money(r.price))}</strong><small style="color:${r.change!==null&&r.change>=0?'var(--lime)':'#ff8e98'}">${r.change!==null?(r.change>=0?'+':'')+r.change.toFixed(2)+'% · 24h':'24h change unavailable'}</small></span></button>`).join(''):blank('No supported equity markets returned','The live feed returned no matching active equity symbols. Refresh to check again.','Refresh','refresh-stocks','compass');
}
function showStock(index){const r=stockRows[index];if(!r)return;openDialog(r.ticker+' perpetual',`<div class="w-review-amount">${esc(money(r.price))}</div><dl class="w-review-lines"><div><dt>Market</dt><dd>${esc(r.name)}</dd></div><div><dt>Venue</dt><dd>Hyperliquid / ${esc(r.dex)}</dd></div><div><dt>Instrument</dt><dd>Equity-linked perpetual</dd></div></dl><p class="w-note">This is a derivative with funding and liquidation risk, not a share or a claim on company ownership. Builder-market pricing and availability depend on the venue.</p><button id="wStockCopy" class="w-secondary full">Copy market name</button><button class="w-primary full" style="margin-top:12px" data-dapp="hyperliquid">Open Hyperliquid ↗</button>`);on('wStockCopy',()=>copy(r.name));}
async function loadPools(force=false){
 if(poolsLoaded&&!force){renderPools();return}$('wPools').innerHTML=blank('Loading market rates','Requesting available lending and staking pools.','','','defi');
 try{pools=await defiPools();poolsLoaded=true;renderPools();$('wPoolsUpdated').textContent='Retrieved '+new Date().toLocaleString('en-US')+' · Source: DeFiLlama. Provider rates may lag the protocol.';}catch(e){$('wPools').innerHTML=blank('Rates unavailable',errorText(e),'Retry','refresh-yields','defi');}
}
function renderPools(){
 $('wPools').innerHTML=pools.length?pools.map(p=>`<div class="w-pool-row"><div><strong>${esc(p.symbol)}</strong><small>${esc(p.project)} · ${esc(p.chain)}</small></div><div class="w-pool-apy">${p.apy.toFixed(2)}%<small>Estimated APY</small></div><button data-dapp="${p.dapp}">View market ↗</button></div>`).join(''):blank('No market rates returned','The provider did not return matching lending or staking pools.','Refresh','refresh-yields','defi');
}
function showHyperAccount(testnet=false){
 if(!needed())return;
 openDialog('Hyperliquid account',`<div class="w-row"><span class="w-badge ${testnet?'test':'neutral'}">${testnet?'Testnet':'Mainnet'}</span><button id="wHyperSwitch" class="w-text-button">View ${testnet?'mainnet':'testnet'}</button></div><p class="w-note">${esc(s.account)}. Hyperliquid balances are separate from your ${esc(s.net.name)} wallet balance.</p><div id="wHyperData">${blank('Loading trading account','Reading spot, perpetuals, fills and deposit/withdrawal ledger.','','','defi')}</div><p id="wFormStatus" class="w-inline-status" role="status"></p><button class="w-secondary full" data-dapp="hyperliquid">Deposit or withdraw on Hyperliquid ↗</button>`,true);
 on('wHyperSwitch',()=>showHyperAccount(!testnet));const me=modalEpoch,epoch=s.epoch,account=s.account;
 hyperAccount(account,testnet).then(data=>{
  if(me!==modalEpoch||epoch!==s.epoch)return;
  const spot=Array.isArray(data.spot?.balances)?data.spot.balances:null;
  const positions=Array.isArray(data.perps?.assetPositions)?data.perps.assetPositions:null;
  const fills=Array.isArray(data.fills)?data.fills:null,ledger=Array.isArray(data.ledger)?data.ledger:null;
  $('wHyperData').innerHTML=`<div class="w-section-card"><h2>Perpetual account</h2><p>${data.perps?.marginSummary?esc(data.perps.marginSummary.accountValue)+' USDC':'Account value unavailable'}</p>${positions?positions.length?positions.map(x=>`<div class="w-pool-row"><div><strong>${esc(x.position.coin)} · ${esc(x.position.szi)}</strong><small>Entry ${esc(x.position.entryPx)} · Liquidation ${esc(x.position.liquidationPx??'—')}</small></div><span>PNL ${esc(x.position.unrealizedPnl)}</span></div>`).join(''):'<p>No open positions.</p>':'<p class="w-error">Positions could not be loaded.</p>'}</div><div class="w-section-card"><h2>Spot balances</h2>${spot?spot.length?spot.map(b=>`<div class="w-pool-row"><strong>${esc(b.coin)}</strong><span>${esc(b.total)}<small>Held in orders: ${esc(b.hold??'—')}</small></span></div>`).join(''):'<p>No spot balances returned.</p>':'<p class="w-error">Spot balances could not be loaded.</p>'}</div><div class="w-section-card"><h2>Deposits, withdrawals & transfers</h2>${ledger?ledger.length?ledger.slice(-100).reverse().map(x=>`<div class="w-pool-row"><div><strong>${esc(x.delta?.type||'Ledger update')}</strong><small>${esc(when(Number(x.time)))} · ${esc(short(x.hash||''))}</small></div><span>${esc(x.delta?.usdc??x.delta?.amount??x.delta?.token??'See venue details')}</span></div>`).join(''):'<p>No ledger updates returned.</p>':'<p class="w-error">Deposit and withdrawal ledger unavailable.</p>'}</div><div class="w-section-card"><h2>Recent fills</h2>${fills?fills.length?fills.slice(0,100).map(x=>`<div class="w-pool-row"><div><strong>${esc(x.coin)} · ${esc(x.dir||x.side)}</strong><small>${esc(when(Number(x.time)))}</small></div><span>${esc(x.sz)} @ ${esc(x.px)}</span></div>`).join(''):'<p>No recent fills returned.</p>':'<p class="w-error">Fill history unavailable.</p>'}</div><p class="w-note">This is the recent history returned by Hyperliquid's API (up to 100 displayed per section), not a full account statement. Download a complete statement from the venue for older activity.</p>`;
 }).catch(e=>{if(me===modalEpoch)status('wFormStatus',errorText(e),'error');});
}
function showMore(){openDialog('All features',`<div class="w-quick-grid">${[['send','send','Send'],['receive','receive','Receive'],['history','history','History'],['accounts','wallet','Accounts'],['contacts','copy','Address book'],['import-token','plus','Import token'],['approvals','shield','Approvals'],['bridge','swap','Bridge'],['hyper-account','defi','Trading account'],['explore','compass','DApps'],['preferences','settings','Preferences'],['practice','swap','Practice']].map(([a,i,n])=>`<button data-action="${a}">${icon(i)}${n}</button>`).join('')}</div><div class="w-section-card"><h2>BELTRIX Wallet</h2><p>Connect an EVM wallet to send native assets and ERC-20 tokens. Your wallet keeps your keys and approves every transaction.</p><p>Supported networks: Ethereum, Arbitrum, Base, Optimism, BNB Chain, Polygon and Sepolia. Native Bitcoin and Solana transfers are not supported.</p></div>`);}
function showContacts(){
 openDialog('Address book',`<p class="w-note">Saved locally in this browser. Check the destination network every time you send.</p>${prefs.contacts.map((c,i)=>`<div class="w-approval"><div class="w-row"><strong>${esc(c.name)}</strong><button class="w-icon-button" data-remove-contact="${i}" aria-label="Remove ${esc(c.name)}">${icon('close')}</button></div><code>${esc(c.address)}</code></div>`).join('')}<label class="w-field">Name<input id="wContactName" maxlength="40" placeholder="Recipient name"></label><label class="w-field">EVM address<input id="wContactAddress" placeholder="0x…" spellcheck="false"></label><button id="wContactSave" class="w-primary full">Save recipient</button><p id="wFormStatus" class="w-inline-status" role="status"></p>`);
 $('wDialogBody').querySelectorAll('[data-remove-contact]').forEach(b=>b.onclick=()=>{prefs.contacts.splice(Number(b.dataset.removeContact),1);savePrefs();showContacts();});
 on('wContactSave',()=>{const name=cleanText($('wContactName').value.trim(),40),recipient=address($('wContactAddress').value);if(!name)throw Error('Enter a recipient name.');prefs.contacts=[...prefs.contacts.filter(c=>!same(c.address,recipient)),{name,address:recipient}].slice(-40);savePrefs();showContacts();notify('Recipient saved.');});
}
function showPreferences(){
 openDialog('Wallet preferences',`<dl class="w-review-lines"><div><dt>Language</dt><dd>English</dd></div><div><dt>Currency</dt><dd>USD</dd></div><div><dt>Network</dt><dd>${esc(s.net.name)}</dd></div></dl><label class="w-check"><input id="wHideSetting" type="checkbox" ${prefs.hide?'checked':''}><span>Hide balances on the wallet home</span></label><label class="w-check"><input id="wRemember" type="checkbox" ${prefs.remember?'checked':''}><span>Remember public accounts, imported tokens and address book in this browser</span></label><p class="w-note">Transaction hashes and unresolved submissions are kept locally so transfers can be reconciled. Secret keys and seed phrases are never requested or stored.</p><button class="w-secondary full" data-action="networks">Change network</button><button id="wForgetLocal" class="w-secondary full" style="margin-top:12px">Forget saved accounts & contacts</button><button id="wAdvancedSettings" class="w-text-button">Open practice settings</button><hr class="w-divider"><p class="w-note">Balances use wallet RPC and public chain indexes; valuations use DeFiLlama and explorer data. History coverage varies by network and provider availability. Addresses queried are shared with these data providers.</p><p class="w-note"><a class="w-link" href="https://docs.blockscout.com/devs/apis/rest" target="_blank" rel="noopener noreferrer">Blockscout data</a> · <a class="w-link" href="https://api-docs.defillama.com/" target="_blank" rel="noopener noreferrer">DeFiLlama data</a></p>`);
 on('wHideSetting',()=>{prefs.hide=$('wHideSetting').checked;savePrefs();renderHome();},'change');
 on('wRemember',()=>{prefs.remember=$('wRemember').checked;savePrefs();},'change');
 on('wForgetLocal',()=>{openDialog('Forget saved data',`<p class="w-note">Remove saved public accounts, imported token lists and contacts from this browser? Your wallet funds and blockchain history will remain available on-chain. Local transaction tracking is retained.</p><button id="wForgetConfirm" class="w-primary full">Forget saved accounts & contacts</button>`);on('wForgetConfirm',()=>{prefs.accounts=[];prefs.imports={};prefs.contacts=[];savePrefs();disconnect();});});
 on('wAdvancedSettings',()=>{closeDialog();window.openPage('settings');});
}
function showSearch(){
 openDialog('Search',`<label class="w-field">Crypto, stocks, addresses or DApps<input id="wGlobalSearch" placeholder="Search or paste an EVM address / TXID" autocomplete="off" spellcheck="false"></label><div id="wSearchResults" class="w-search-results"></div><p id="wFormStatus" class="w-inline-status" role="status"></p>`);
 function results(){const q=$('wGlobalSearch').value.trim(),lower=q.toLowerCase();const assets=s.tokens.filter(t=>[t.symbol,t.name,t.address].some(x=>x.toLowerCase().includes(lower))),apps=DAPPS.filter(d=>[d.name,d.category,d.tag].some(x=>x.toLowerCase().includes(lower))),stocks=stockRows.filter(r=>r.ticker.toLowerCase().includes(lower));
 $('wSearchResults').innerHTML=`${isAddress(q)?`<button id="wSearchWatch" class="w-provider"><span>Watch this address<small>${esc(q)}</small></span>${icon('arrow')}</button>`:''}${hashOK(q)?`<a class="w-secondary full" href="${s.net.explorer}/tx/${q}" target="_blank" rel="noopener noreferrer">Open transaction on ${esc(s.net.name)} ↗</a>`:''}${assets.slice(0,10).map(t=>`<button class="w-token-row" data-token="${esc(t.address)}">${tokenMark(t)}<span><strong>${esc(t.symbol)}</strong><small class="w-token-sub">${esc(t.name)}</small></span></button>`).join('')}${stocks.slice(0,6).map(r=>`<button class="w-provider" data-stock="${stockRows.indexOf(r)}"><span>${esc(r.ticker)} perpetual<small>${esc(r.name)}</small></span></button>`).join('')}<div class="w-dapp-grid">${apps.map(dappHTML).join('')}</div>${!assets.length&&!apps.length&&!stocks.length&&!isAddress(q)&&!hashOK(q)?blank('No results','Try a token symbol, supported DApp, EVM address or transaction hash.'):''}`;
 on('wSearchWatch',()=>{setAccount(address(q),true,'Watch '+short(q));closeDialog();window.openPage('wallet');});}
 on('wGlobalSearch',results,'input');results();setTimeout(()=>$('wGlobalSearch')?.focus(),100);
}
function tab(name){if(!['crypto','stocks','positions','dapps','approvals'].includes(name))return;s.tab=name;document.querySelectorAll('[data-tab]').forEach(b=>{const active=b.dataset.tab===name;b.classList.toggle('active',active);b.setAttribute('aria-selected',String(active));});document.querySelectorAll('.w-tab-panel').forEach(p=>p.hidden=p.id!=='wPanel-'+name);if(name==='stocks')loadStocks();}
function navigate(page){closeDialog();window.openPage(page);}
const actions={
 connect:showAccounts,accounts:showAccounts,networks:showNetworks,send:()=>showSend(),receive:showReceive,history:showHistory,more:showMore,search:showSearch,notifications:showNotifications,preferences:showPreferences,contacts:showContacts,approvals:showApprovals,
 'copy-address':()=>s.account&&copy(s.account),'copy-site':()=>copy(location.origin+location.pathname),'hide-balances':()=>{prefs.hide=!prefs.hide;savePrefs();renderHome();},refresh:()=>{notify('Refreshing wallet…');return refreshWallet();},'import-token':showImport,
 'open-defi':()=>navigate('defi'),explore:()=>navigate('explore'),bridge:()=>showDapp('across'),practice:()=>navigate('swap'),'refresh-stocks':()=>loadStocks(true),'refresh-yields':()=>loadPools(true),'hyper-account':()=>showHyperAccount(false),
 'testnet-trade':()=>{navigate('markets');$('marketNetwork').value='testnet';$('marketNetwork').dispatchEvent(new Event('change'));}
};
document.addEventListener('click',e=>{
 const b=e.target.closest('button,[data-dapp]');if(!b||b.disabled)return;
 if(b.dataset.action&&own(actions,b.dataset.action))Promise.resolve().then(()=>actions[b.dataset.action]()).catch(err=>{status('wFormStatus',errorText(err),'error');notify(errorText(err));});
 if(b.dataset.tab)tab(b.dataset.tab);if(b.dataset.token)showToken(b.dataset.token);if(b.dataset.dapp)showDapp(b.dataset.dapp);if(b.dataset.stock!==undefined)showStock(Number(b.dataset.stock));
 if(b.dataset.dappFilter){dappFilter=b.dataset.dappFilter;document.querySelectorAll('[data-dapp-filter]').forEach(x=>x.classList.toggle('active',x===b));renderDapps();}
});
window.addEventListener('beltrix:page',e=>{if(e.detail==='defi')loadPools();});
window.beltrixWallet={get provider(){return s.provider},get account(){return s.account},openAccounts:showAccounts};
renderDapps();renderHome();
const initial=location.hash.slice(1);window.openPage(['markets','swap','settings','wallet','explore','defi','boost'].includes(initial)?initial:'wallet');
// Poll confirmations only; never retry a write or trigger a wallet request in the background.
setInterval(()=>{if(!document.hidden&&!s.busy)pollJournal().catch(()=>{});},30000);
document.addEventListener('visibilitychange',()=>{if(!document.hidden&&s.account&&!s.busy&&Date.now()-s.updated>60000)refreshWallet();});
