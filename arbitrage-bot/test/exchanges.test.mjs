import test from 'node:test';
import assert from 'node:assert/strict';
import { createHmac } from 'node:crypto';
import { mkdtempSync, readFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { binance, bybit, okx, credsFromEnv } from '../src/exchanges.mjs';
import { createEngine, DEFAULTS, validateConfig, resolveFees } from '../src/engine.mjs';
import { paperExecutor } from '../src/executor.mjs';
import { RiskManager } from '../src/risk.mjs';
import { loadConfig } from '../cli.mjs';

const NOW = 1761091200000;
const creds = { apiKey: 'test-key', apiSecret: 'test-secret', passphrase: 'pp' };
const respond = (body, status = 200) => ({ ok: status < 400, status, json: async () => body });

/** Route fake responses by path prefix and record every request. */
function fakeFetch(routes) {
  const calls = [];
  const fetchFn = async (url, init = {}) => {
    const u = new URL(url);
    calls.push({ url: u, ...init });
    const key = Object.keys(routes).find(k => `${init.method ?? 'GET'} ${u.pathname}`.startsWith(k));
    if (!key) throw Error(`unrouted ${u.pathname}`);
    const r = routes[key];
    return typeof r === 'function' ? r(u, init, calls) : respond(r);
  };
  return { fetchFn, calls };
}

test('public books and market filters are parsed per exchange and symbols are mapped', async () => {
  const f = fakeFetch({
    'GET /api/v3/depth': { bids: [['100', '1']], asks: [['101', '2']] },
    'GET /api/v3/exchangeInfo': { symbols: [{ status: 'TRADING', filters: [
      { filterType: 'PRICE_FILTER', tickSize: '0.01000000' }, { filterType: 'LOT_SIZE', stepSize: '0.00001000', minQty: '0.00001000' },
      { filterType: 'NOTIONAL', minNotional: '5.00000000' }] }] },
    'GET /v5/market/orderbook': { retCode: 0, result: { b: [['100', '1']], a: [['101', '2']], ts: NOW } },
    'GET /v5/market/instruments-info': { retCode: 0, result: { list: [{ status: 'Trading', lotSizeFilter: { basePrecision: '0.000001', minOrderQty: '0.000048', minOrderAmt: '1' }, priceFilter: { tickSize: '0.01' } }] } },
    'GET /api/v5/market/books': { code: '0', data: [{ bids: [['100', '1', '0', '1']], asks: [['101', '2', '0', '1']], ts: String(NOW) }] },
    'GET /api/v5/public/instruments': { code: '0', data: [{ state: 'live', lotSz: '0.00000001', minSz: '0.00001', tickSz: '0.1' }] },
  });
  for (const make of [binance, bybit, okx]) {
    const ex = make({ fetchFn: f.fetchFn });
    const b = await ex.fetchBook('BTC/USDT', 5);
    assert.deepEqual([b.bids[0], b.asks[0]], [[100, 1], [101, 2]]);
    const m = await ex.fetchMarket('BTC/USDT');
    assert.ok(m.lotStep > 0 && m.tickSize > 0 && m.minQty > 0);
  }
  assert.deepEqual(await binance({ fetchFn: f.fetchFn }).fetchMarket('BTC/USDT'), { lotStep: 0.00001, minQty: 0.00001, tickSize: 0.01, minNotional: 5 });
  const symbols = f.calls.map(c => c.url.searchParams.get('symbol') ?? c.url.searchParams.get('instId'));
  assert.ok(symbols.includes('BTCUSDT') && symbols.includes('BTC-USDT'));
  assert.ok(f.calls.every(c => c.redirect === 'error' && c.url.protocol === 'https:'));
  assert.ok(f.calls.every(c => ['api.binance.com', 'api.bybit.com', 'www.okx.com'].includes(c.url.host)));
});

test('exchange errors surface without leaking credentials', async () => {
  const f = fakeFetch({ 'GET /v5/market/orderbook': { retCode: 10001, retMsg: 'params error' }, 'GET /api/v3/account': () => respond({ code: -2015, msg: 'Invalid API-key' }, 401) });
  await assert.rejects(bybit({ fetchFn: f.fetchFn }).fetchBook('BTC/USDT'), /retCode 10001/);
  await assert.rejects(binance({ fetchFn: f.fetchFn, creds, now: () => NOW }).fetchBalances(), e => e.httpStatus === 401 && !e.message.includes('test-secret') && !e.message.includes('test-key'));
  await assert.rejects(binance({ fetchFn: async () => { throw Object.assign(Error('t'), { name: 'TimeoutError' }); } }).fetchBook('BTC/USDT'), e => e.code === 'network');
  await assert.rejects(binance({}).fetchBalances(), /missing credential apiKey/);
  await assert.rejects(binance().fetchBook('btcusdt'), /symbol must look like/);
});

test('Binance orders are HMAC-signed IOC limits and keys with withdrawal permission are refused', async () => {
  const f = fakeFetch({
    'POST /api/v3/order': { orderId: 7, status: 'FILLED', executedQty: '0.01000000', cummulativeQuoteQty: '1000.50000000' },
    'GET /sapi/v1/account/apiRestrictions': { enableWithdrawals: true, enableSpotAndMarginTrading: true },
  });
  const ex = binance({ fetchFn: f.fetchFn, creds, now: () => NOW });
  const r = await ex.placeIoc({ symbol: 'BTC/USDT', side: 'buy', qty: '0.01000', price: '100050.00' });
  assert.deepEqual(r, { orderId: '7', status: 'FILLED', filledQty: 0.01, filledQuote: 1000.5 });
  const { url, headers } = f.calls[0];
  const q = url.search.slice(1).replace(/&signature=.*$/, '');
  assert.equal(url.searchParams.get('signature'), createHmac('sha256', 'test-secret').update(q).digest('hex'));
  assert.equal(headers['X-MBX-APIKEY'], 'test-key');
  assert.equal(url.searchParams.get('timeInForce'), 'IOC');
  assert.equal(url.searchParams.get('timestamp'), String(NOW));
  assert.equal((await ex.checkKeySafety()).ok, false);
});

test('Bybit signs ts+key+recv+payload, polls the IOC to a terminal state, and refuses Withdraw keys', async () => {
  let polls = 0;
  const f = fakeFetch({
    'POST /v5/order/create': { retCode: 0, result: { orderId: 'abc' } },
    'GET /v5/order/realtime': () => respond({ retCode: 0, result: { list: [++polls < 2 ? { orderStatus: 'New' } : { orderStatus: 'PartiallyFilledCanceled', cumExecQty: '0.004', cumExecValue: '400.2' }] } }),
    'GET /v5/user/query-api': { retCode: 0, result: { readOnly: 0, permissions: { Wallet: ['AccountTransfer', 'Withdraw'], Spot: ['SpotTrade'] } } },
  });
  const ex = bybit({ fetchFn: f.fetchFn, creds, now: () => NOW, wait: async () => {} });
  const r = await ex.placeIoc({ symbol: 'BTC/USDT', side: 'sell', qty: '0.005', price: '100000.00' });
  assert.deepEqual(r, { orderId: 'abc', status: 'PartiallyFilledCanceled', filledQty: 0.004, filledQuote: 400.2 });
  const create = f.calls[0];
  assert.deepEqual(JSON.parse(create.body), { category: 'spot', symbol: 'BTCUSDT', side: 'Sell', orderType: 'Limit', qty: '0.005', price: '100000.00', timeInForce: 'IOC' });
  assert.equal(create.headers['X-BAPI-SIGN'], createHmac('sha256', 'test-secret').update(`${NOW}test-key5000${create.body}`).digest('hex'));
  const poll = f.calls[1];
  assert.equal(poll.headers['X-BAPI-SIGN'], createHmac('sha256', 'test-secret').update(`${NOW}test-key5000${poll.url.search.slice(1)}`).digest('hex'));
  assert.match((await ex.checkKeySafety()).reason, /withdrawals/);

  const stuck = bybit({ creds, now: () => NOW, wait: async () => {}, fetchFn: fakeFetch({ 'POST /v5/order/create': { retCode: 0, result: { orderId: 'x' } }, 'GET /v5/order/realtime': { retCode: 0, result: { list: [{ orderStatus: 'New' }] } } }).fetchFn });
  await assert.rejects(stuck.placeIoc({ symbol: 'BTC/USDT', side: 'buy', qty: '1', price: '1' }), e => e.code === 'unsettled');
});

test('OKX signs base64(ts+method+path+body) with passphrase and reads accFillSz/avgPx', async () => {
  const f = fakeFetch({
    'POST /api/v5/trade/order': { code: '0', data: [{ ordId: '9', sCode: '0' }] },
    'GET /api/v5/trade/order': { code: '0', data: [{ state: 'filled', accFillSz: '0.002', avgPx: '100010' }] },
    'GET /api/v5/account/config': { code: '0', data: [{ perm: 'read_only,trade' }] },
    'GET /api/v5/account/balance': { code: '0', data: [{ details: [{ ccy: 'USDT', availBal: '512.5' }, { ccy: 'BTC', availBal: '0.01' }] }] },
  });
  const ex = okx({ fetchFn: f.fetchFn, creds, now: () => NOW, wait: async () => {} });
  const r = await ex.placeIoc({ symbol: 'BTC/USDT', side: 'buy', qty: '0.002', price: '100020.0' });
  assert.equal(r.filledQty, 0.002);
  assert.ok(Math.abs(r.filledQuote - 200.02) < 1e-9);
  const c = f.calls[0], ts = new Date(NOW).toISOString();
  assert.deepEqual(JSON.parse(c.body), { instId: 'BTC-USDT', tdMode: 'cash', side: 'buy', ordType: 'ioc', px: '100020.0', sz: '0.002' });
  assert.equal(c.headers['OK-ACCESS-SIGN'], createHmac('sha256', 'test-secret').update(ts + 'POST/api/v5/trade/order' + c.body).digest('base64'));
  assert.equal(c.headers['OK-ACCESS-PASSPHRASE'], 'pp');
  const g = f.calls[1];
  assert.equal(g.headers['OK-ACCESS-SIGN'], createHmac('sha256', 'test-secret').update(ts + 'GET' + g.url.pathname + g.url.search).digest('base64'));
  assert.deepEqual(await ex.checkKeySafety(), { ok: true });
  assert.deepEqual(await ex.fetchBalances(), { USDT: 512.5, BTC: 0.01 });
});

test('credentials only come from the environment; live mode needs explicit confirmation', () => {
  assert.equal(credsFromEnv('binance', {}), undefined);
  assert.deepEqual(credsFromEnv('okx', { OKX_API_KEY: 'k', OKX_API_SECRET: 's', OKX_PASSPHRASE: 'p' }), { apiKey: 'k', apiSecret: 's', passphrase: 'p' });
  assert.throws(() => loadConfig({ live: true }, {}), /ARB_LIVE_CONFIRM/);
  assert.ok(loadConfig({ live: true }, { ARB_LIVE_CONFIRM: 'I_ACCEPT_REAL_FUNDS_RISK' }));
  assert.throws(() => loadConfig({ exchanges: 'binance,kraken' }), /unsupported exchange kraken/);
  assert.throws(() => validateConfig({ ...DEFAULTS, minEdgeBps: 1 }), /minEdgeBps/);
  assert.throws(() => validateConfig({ ...DEFAULTS, exchanges: ['binance'] }), /two exchanges/);
});

test('engine end to end in paper mode: trades a real cross, journals it, then the cooldown blocks a repeat', async () => {
  const dir = mkdtempSync(join(tmpdir(), 'arb-'));
  const market = { lotStep: 0.0001, minQty: 0.0001, tickSize: 0.01, minNotional: 5 };
  const book = { lo: { bids: [[99.8, 5]], asks: [[100, 3]] }, hi: { bids: [[100.6, 3]], asks: [[100.8, 5]] }, flat: { bids: [[100.1, 5]], asks: [[100.2, 5]] } };
  const fake = b => ({ fetchMarket: async () => market, fetchBook: async () => structuredClone(b) });
  const cfg = { ...DEFAULTS, exchanges: ['lo', 'hi', 'flat'], symbols: ['X/USDT'], fees: { lo: 0.001, hi: 0.001, flat: 0.001 }, maxNotional: 150, journalPath: join(dir, 'j.jsonl') };
  const balances = { lo: { USDT: 1000, X: 0 }, hi: { USDT: 0, X: 5 }, flat: { USDT: 1000, X: 5 } };
  let t = NOW;
  const risk = new RiskManager(cfg, { statePath: join(dir, 'risk.json'), now: () => t });
  const logs = [];
  const engine = createEngine({ cfg, adapters: { lo: fake(book.lo), hi: fake(book.hi), flat: fake(book.flat) }, executor: paperExecutor({ balances, fees: cfg.fees }), risk, log: m => logs.push(m), now: () => t });
  await engine.init();
  const [first] = await engine.runOnce();
  assert.equal(first.opp.buy, 'lo');
  assert.equal(first.opp.sell, 'hi');
  assert.ok(first.opp.expectedCost <= 150 && first.result.realizedPnl > 0);
  assert.equal(balances.hi.X, 5 - first.opp.qty);
  const journal = readFileSync(cfg.journalPath, 'utf8').trim().split('\n').map(JSON.parse);
  assert.equal(journal.length, 1);
  assert.equal(journal[0].mode, 'paper');
  const [second] = await engine.runOnce();
  assert.equal(second.skipped, 'pair cooldown');
  assert.equal(JSON.parse(readFileSync(join(dir, 'risk.json'), 'utf8')).tradesToday, 1);
});

test('account taker fees are read per exchange and the bot never uses a fee below the real one', async () => {
  const f = fakeFetch({
    'GET /api/v3/account/commission': { standardCommission: { taker: '0.00100000' }, taxCommission: { taker: '0.00000000' }, specialCommission: { taker: '0.00000000' }, discount: { enabledForAccount: true, discount: '0.75000000' } },
    'GET /v5/account/fee-rate': { retCode: 0, result: { list: [{ symbol: 'BTCUSDT', takerFeeRate: '0.00045', makerFeeRate: '0.0003' }] } },
    'GET /api/v5/account/trade-fee': { code: '0', data: [{ instType: 'SPOT', taker: '-0.002', maker: '-0.001' }] },
  });
  const ex = { binance: binance({ fetchFn: f.fetchFn, creds, now: () => NOW }), bybit: bybit({ fetchFn: f.fetchFn, creds, now: () => NOW }), okx: okx({ fetchFn: f.fetchFn, creds, now: () => NOW }) };
  assert.equal(await ex.binance.fetchTakerFee('BTC/USDT'), 0.001);
  assert.equal(await ex.bybit.fetchTakerFee('BTC/USDT'), 0.00045);
  assert.equal(await ex.okx.fetchTakerFee('BTC/USDT'), 0.002, 'OKX charged fee is negative in the API');
  const cfg = { ...DEFAULTS, symbols: ['BTC/USDT'], fees: { binance: 0.001, bybit: 0.001, okx: 0.001 } };
  const { fees, rows } = await resolveFees({ cfg, adapters: ex });
  assert.deepEqual(fees, { binance: 0.001, bybit: 0.001, okx: 0.002 }, 'max(configured, actual)');
  assert.equal(rows.find(r => r.exchange === 'okx').actual, 0.002);
  await assert.rejects(resolveFees({ cfg, adapters: { ...ex, okx: { fetchTakerFee: async () => NaN } } }), /implausible/);
});
