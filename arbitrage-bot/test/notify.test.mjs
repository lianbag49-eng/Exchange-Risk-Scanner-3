import test from 'node:test';
import assert from 'node:assert/strict';
import { telegramNotifier, notifierFromEnv, silentNotifier, tradeMessage } from '../src/notify.mjs';
import { createEngine, DEFAULTS } from '../src/engine.mjs';
import { paperExecutor } from '../src/executor.mjs';
import { RiskManager } from '../src/risk.mjs';

const TOKEN = '123456:ABCdefGHIjklMNOpqrSTUvwxYZ_0123456789';

test('messages go to the Bot API in order, and a failing send never throws or leaks the token', async () => {
  const sent = [], logs = [];
  let fail = false;
  const fetchFn = async (url, init) => {
    if (fail) throw Error(`connect ${url}`);
    sent.push({ url, body: JSON.parse(init.body), redirect: init.redirect });
    return { ok: true, status: 200 };
  };
  const n = telegramNotifier({ token: TOKEN, chatId: '42', fetchFn, minIntervalMs: 0, log: m => logs.push(m) });
  n.send('one'); n.send('two');
  await n.flush();
  assert.deepEqual(sent.map(s => s.body.text), ['one', 'two']);
  assert.equal(sent[0].url, `https://api.telegram.org/bot${TOKEN}/sendMessage`);
  assert.equal(sent[0].body.chat_id, '42');
  assert.equal(sent[0].redirect, 'error');
  fail = true;
  await n.send('three');
  assert.equal(logs.length, 1);
  assert.ok(!logs[0].includes(TOKEN), 'token must not be logged');
});

test('sends are spaced by the rate limit', async () => {
  const waits = [];
  const n = telegramNotifier({ token: TOKEN, chatId: '1', fetchFn: async () => ({ ok: true }), minIntervalMs: 1000, wait: async ms => { waits.push(ms); } });
  n.send('a'); n.send('b');
  await n.flush();
  assert.equal(waits.length, 1);
  assert.ok(waits[0] > 900 && waits[0] <= 1000);
});

test('environment config is all-or-nothing and validates the token shape', () => {
  assert.equal(notifierFromEnv({}), silentNotifier);
  assert.throws(() => notifierFromEnv({ TELEGRAM_BOT_TOKEN: TOKEN }), /both/);
  assert.throws(() => notifierFromEnv({ TELEGRAM_BOT_TOKEN: 'nope', TELEGRAM_CHAT_ID: '1' }), /bot token/);
  assert.notEqual(notifierFromEnv({ TELEGRAM_BOT_TOKEN: TOKEN, TELEGRAM_CHAT_ID: '1' }), silentNotifier);
});

test('trade messages flag one-legged and unknown outcomes', () => {
  const opp = { symbol: 'BTC/USDT', buy: 'binance', sell: 'bybit', qty: 0.001, expectedProfit: 0.12, edgeBps: 20 };
  assert.match(tradeMessage('paper', opp, { realizedPnl: 0.12, unhedgedQty: 0 }), /\[모의\] ✅ 체결 BTC\/USDT/);
  assert.match(tradeMessage('live', opp, { realizedPnl: 0, unhedgedQty: 0.001 }), /\[실거래\] ⚠️ 한쪽만 체결[\s\S]*미헤지 수량 0.001/);
  assert.match(tradeMessage('live', opp, { realizedPnl: 0, unhedgedQty: 0, uncertain: true }), /주문 결과 불명/);
});

test('the engine announces start, each trade, a halt exactly once, and nothing else', async () => {
  const market = { lotStep: 0.0001, minQty: 0.0001, tickSize: 0.01, minNotional: 5 };
  const fake = b => ({ fetchMarket: async () => market, fetchBook: async () => structuredClone(b) });
  const cfg = { ...DEFAULTS, exchanges: ['lo', 'hi'], symbols: ['X/USDT'], fees: { lo: 0.001, hi: 0.001 }, maxNotional: 50, journalPath: null, intervalMs: 1, dailyLossLimit: 1e9 };
  const msgs = [];
  const notify = { send: async m => { msgs.push(m); }, flush: async () => {} };
  const risk = new RiskManager({ ...cfg, cooldownMs: 0 });
  const executor = paperExecutor({ balances: { lo: { USDT: 1000, X: 0 }, hi: { USDT: 0, X: 5 } }, fees: cfg.fees });
  const engine = createEngine({
    cfg, risk, executor, notify, log: () => {},
    adapters: { lo: fake({ bids: [[99.8, 5]], asks: [[100, 3]] }), hi: fake({ bids: [[100.6, 3]], asks: [[100.8, 5]] }) },
  });
  const ac = new AbortController();
  let ticks = 0;
  const origRecord = risk.recordTrade.bind(risk);
  risk.recordTrade = (o, r) => { origRecord(o, r); if (++ticks === 2) risk.halt('test halt'); };
  await engine.run({ signal: ac.signal });
  assert.match(msgs[0], /^▶️ \[모의\] 시작/);
  assert.equal(msgs.filter(m => m.includes('✅ 체결')).length, 2);
  assert.equal(msgs.filter(m => m.includes('봇 정지: test halt')).length, 1);
  assert.ok(!msgs.some(m => m.includes('종료')), 'a halted run reports the halt, not a normal stop');
});
