import test from 'node:test';import assert from 'node:assert/strict';
import {fundingView,leverageRequest,boundedPrice,fundingCashflow,fitsPosition,makeTwap} from '../terminal-core.js';
import {makeOrder} from '../order-validation.js';
const meta={asset:0,szDecimals:4,maxLeverage:20,spot:false};
test('hourly funding direction, rollover, unavailable and position cashflow',()=>{
 const t=Date.UTC(2026,8,15,10,59,59);assert.equal(fundingView('.0001',t).countdown,'00:01');assert.equal(fundingView('.0001',t).direction,'Longs pay shorts');assert.equal(fundingView('-.0001',t).direction,'Shorts pay longs');assert.equal(fundingView(null,t).label,'Unavailable');assert.equal(fundingView(0,t).label,'+0.0000%');assert.equal(fundingView(0,t+1000).countdown,'60:00');assert.equal(fundingCashflow(2,2500,.0001),-.5);assert.equal(fundingCashflow(-2,2500,.0001),.5);assert.equal(fundingCashflow(2,null,.0001),null);
});
test('leverage obeys market metadata and margin restrictions',()=>{
 assert.deepEqual(leverageRequest(meta,20,'isolated'),{asset:0,leverage:20,isCross:false});for(const n of [0,21,1.5,NaN])assert.throws(()=>leverageRequest(meta,n,'cross'));assert.throws(()=>leverageRequest({...meta,spot:true},2,'cross'));assert.throws(()=>leverageRequest({...meta,onlyIsolated:true},2,'cross'));assert.throws(()=>leverageRequest({...meta,maxLeverage:undefined},2,'cross'));
});
test('market IOC bound stays inside requested slippage and respects price precision',()=>{
 for(const buy of [true,false]){const p=boundedPrice(meta,'2510.1',buy,'0.5');assert.ok(buy?Number(p)<=2510.1*1.005:Number(p)>=2510.1*.995);const o=makeOrder(meta,p,'1',buy,false,'Ioc');assert.equal(o.t.limit.tif,'Ioc');}
 assert.equal(boundedPrice(meta,'21',true,'0.5'),'21.1');assert.equal(boundedPrice(meta,'20',false,'0.5'),'19.9');for(const slip of ['0','-1','6','NaN'])assert.throws(()=>boundedPrice(meta,'20',true,slip));
 const tiny={...meta,szDecimals:0,spot:true};assert.ok(Number(boundedPrice(tiny,'0.000012345',false,'0.5'))>=.000012345*.995);
});

test('reduce-only validation uses exact decimal quantities',()=>{assert.equal(fitsPosition('1.000000000000000001','1',false),false);assert.equal(fitsPosition('1.000000000000000001','-1.000000000000000002',true),true);assert.equal(fitsPosition('1','-2',false),false);});

test('TWAP size and duration bounds are checked before a wallet request',()=>{assert.deepEqual(makeTwap(meta,'10','21',true,false,30,true),{a:0,b:true,s:'10',r:false,m:30,t:true});assert.throws(()=>makeTwap(meta,'1','21',true,false,30,false));assert.throws(()=>makeTwap(meta,'10','21',true,false,1441,false));assert.throws(()=>makeTwap(meta,'10.12345','21',true,false,30,false));});
