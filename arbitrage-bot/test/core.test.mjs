import test from 'node:test';
import assert from 'node:assert/strict';
import { quantize, commonStep, crossDepth, normaliseBook, fillAgainst, formatStep } from '../src/book.mjs';
import { sizePair, bestOpportunity, spreads } from '../src/opportunity.mjs';
import { RiskManager } from '../src/risk.mjs';
import { paperExecutor, liveExecutor } from '../src/executor.mjs';
import { ExchangeError } from '../src/exchanges.mjs';

const market = { lotStep: 0.0001, minQty: 0.0001, tickSize: 0.01, minNotional: 5 };
const fees = { a: 0.001, b: 0.001 };
const books = () => ({
  a: normaliseBook({ bids: [[99.9, 5]], asks: [[100, 1], [100.1, 1], [100.5, 10]] }),
  b: normaliseBook({ bids: [[100.6, 0.5], [100.5, 1], [100, 10]], asks: [[100.7, 5]] }),
});
const ctx = (over = {}) => ({
  symbol: 'X/USDT', books: books(), markets: { a: market, b: market }, fees,
  balances: { a: { base: 0, quote: 10000 }, b: { base: 10, quote: 0 } }, minEdgeBps: 15, maxNotional: 1000, ...over,
});

test('quantization never rounds a size up or a buy limit down, and has no float residue', () => {
  assert.equal(quantize(0.123456789, 0.0001), 0.1234);
  assert.equal(quantize(0.3, 0.1), 0.3);
  assert.equal(quantize(100.001, 0.01, 'ceil'), 100.01);
  assert.equal(quantize(100.1, 0.01, 'ceil'), 100.1);
  assert.equal(formatStep(1e-7, 0.0000001), '0.0000001');
  assert.equal(commonStep(0.00001, 0.000001), 0.00001);
  assert.throws(() => commonStep(0.0003, 0.0002));
});

test('crossed or empty books are rejected; levels are sorted', () => {
  assert.throws(() => normaliseBook({ bids: [[101, 1]], asks: [[100, 1]] }));
  assert.throws(() => normaliseBook({ bids: [], asks: [[100, 1]] }));
  const b = normaliseBook({ bids: [['1', '1'], ['2', '1']], asks: [['4', '1'], ['3', '1']] });
  assert.deepEqual(b.bids.map(l => l[0]), [2, 1]);
  assert.deepEqual(b.asks.map(l => l[0]), [3, 4]);
});

test('depth walk stops where the marginal edge after both fees drops below threshold', () => {
  const { asks } = books().a, { bids } = books().b;
  const r = crossDepth({ asks, bids, buyFee: 0.001, sellFee: 0.001, minEdge: 0.0015, maxQty: 100 });
  // 100 -> 100.6 (≈40bps), 100 -> 100.5 (≈30bps) and 100.1 -> 100.5 (≈20bps) pass; 100.1 -> 100 does not.
  assert.equal(r.buyLimit, 100.1);
  assert.equal(r.sellLimit, 100.5);
  assert.ok(Math.abs(r.qty - 1.5) < 1e-12);
});

test('sizing respects fees, depth, IOC limits and reports a positive net edge', () => {
  const o = sizePair({ ...ctx(), buy: 'a', sell: 'b' });
  assert.equal(o.qty, 1.5);
  assert.equal(o.buyLimit, 100.1);
  assert.equal(o.sellLimit, 100.5);
  const net = o.expectedProceeds * 0.999 - o.expectedCost * 1.001;
  assert.ok(Math.abs(net - o.expectedProfit) < 1e-9 && o.expectedProfit > 0 && o.edgeBps >= 15);
  assert.equal(sizePair({ ...ctx(), buy: 'b', sell: 'a' }), null, 'reverse direction is unprofitable');
});

test('balance and notional caps shrink the trade; no inventory means no trade', () => {
  const capped = sizePair({ ...ctx({ maxNotional: 60 }), buy: 'a', sell: 'b' });
  assert.ok(capped.expectedCost <= 60 && capped.qty === 0.6);
  const lowQuote = sizePair({ ...ctx({ balances: { a: { quote: 50.5 }, b: { base: 10 } } }), buy: 'a', sell: 'b' });
  assert.ok(lowQuote.expectedCost * 1.001 <= 50.5);
  const lowBase = sizePair({ ...ctx({ balances: { a: { quote: 10000 }, b: { base: 0.2 } } }), buy: 'a', sell: 'b' });
  assert.equal(lowBase.qty, 0.2);
  assert.equal(sizePair({ ...ctx({ balances: { a: { quote: 0 }, b: { base: 10 } } }), buy: 'a', sell: 'b' }), null);
  assert.equal(sizePair({ ...ctx({ balances: { a: { quote: 10000 }, b: { base: 0 } } }), buy: 'a', sell: 'b' }), null);
});

test('min notional and min qty below exchange limits produce no order', () => {
  assert.equal(sizePair({ ...ctx({ markets: { a: { ...market, minNotional: 500 }, b: market } }), buy: 'a', sell: 'b' }), null);
  assert.equal(sizePair({ ...ctx({ markets: { a: market, b: { ...market, minQty: 5 } } }), buy: 'a', sell: 'b' }), null);
});

test('a high threshold or fees larger than the spread yield nothing', () => {
  assert.equal(bestOpportunity(ctx({ minEdgeBps: 60 })), null);
  assert.equal(bestOpportunity(ctx({ fees: { a: 0.003, b: 0.003 } })), null);
  assert.equal(bestOpportunity(ctx()).buy, 'a');
  const s = spreads({ books: books(), fees });
  assert.equal(s[0].buy, 'a');
  assert.ok(s[0].grossBps > s[0].netBps);
});

test('paper fills move balances by exactly the fee-adjusted amounts', async () => {
  const balances = { a: { USDT: 10000, X: 0 }, b: { USDT: 0, X: 10 } };
  const ex = paperExecutor({ balances, fees });
  const c = ctx(), o = bestOpportunity(c);
  const r = await ex.execute(o, { books: c.books, base: 'X', quote: 'USDT' });
  assert.equal(r.unhedgedQty, 0);
  assert.ok(Math.abs(r.realizedPnl - o.expectedProfit) < 1e-9);
  const total = q => balances.a[q] + balances.b[q];
  assert.ok(Math.abs(total('X') - 10) < 1e-12, 'base inventory is only moved between venues');
  assert.ok(Math.abs(total('USDT') - 10000 - r.realizedPnl) < 1e-9);
});

const fakeLive = (buyResult, sellResult) => liveExecutor({
  fees,
  adapters: {
    a: { placeIoc: async p => { if (buyResult instanceof Error) throw buyResult; return { ...buyResult, args: p }; } },
    b: { placeIoc: async p => { if (sellResult instanceof Error) throw sellResult; return { ...sellResult, args: p }; } },
  },
});

test('live executor sends both legs as exchange-formatted IOC limits', async () => {
  const o = bestOpportunity(ctx());
  const r = await fakeLive({ filledQty: 1.5, filledQuote: 150.05 }, { filledQty: 1.5, filledQuote: 150.75 }).execute(o);
  assert.deepEqual(r.buyLeg.args, { symbol: 'X/USDT', side: 'buy', qty: '1.5000', price: '100.10' });
  assert.deepEqual(r.sellLeg.args, { symbol: 'X/USDT', side: 'sell', qty: '1.5000', price: '100.50' });
  assert.equal(r.unhedgedQty, 0);
  assert.ok(!r.uncertain);
});

test('one-legged fills and unknown outcomes are flagged, and the risk gate halts until reset', async () => {
  const o = bestOpportunity(ctx());
  const partial = await fakeLive({ filledQty: 1.5, filledQuote: 150 }, { filledQty: 0.5, filledQuote: 50.3 }).execute(o);
  assert.equal(partial.unhedgedQty, 1);
  assert.equal(partial.hedgedQty, 0.5);
  const rejected = await fakeLive({ filledQty: 1.5, filledQuote: 150 }, new ExchangeError('b', 'insufficient balance', 170131, 400)).execute(o);
  assert.equal(rejected.unhedgedQty, 1.5);
  assert.ok(!rejected.uncertain, 'a clean 4xx rejection is a known zero fill');
  for (const err of [new ExchangeError('b', 'timeout', 'network'), new ExchangeError('b', 'busy', -1001, 503), new TypeError('bug')])
    assert.ok((await fakeLive({ filledQty: 0, filledQuote: 0 }, err).execute(o)).uncertain);

  let t = Date.parse('2026-10-22T00:00:00Z');
  const risk = new RiskManager({ dailyLossLimit: 10, maxTradesPerDay: 100, cooldownMs: 1000, maxConsecutiveErrors: 3 }, { now: () => t });
  assert.ok(risk.allow(o).ok);
  risk.recordTrade(o, partial);
  assert.ok(risk.state.halted && /unhedged/.test(risk.state.haltReason));
  t += 60_000;
  assert.equal(risk.allow(o).ok, false);
  risk.reset();
  assert.ok(risk.allow(o).ok);
});

test('risk gate enforces cooldown, daily loss, trade count and consecutive errors; the day rolls over', () => {
  let t = Date.parse('2026-10-22T10:00:00Z');
  const risk = new RiskManager({ dailyLossLimit: 10, maxTradesPerDay: 2, cooldownMs: 1000, maxConsecutiveErrors: 2 }, { now: () => t });
  const o = { symbol: 'X/USDT', buy: 'a', sell: 'b' };
  assert.ok(new RiskManager({ dailyLossLimit: 10, maxTradesPerDay: 2, cooldownMs: 5000, maxConsecutiveErrors: 2 }, { now: () => 1000 }).allow(o).ok, 'a route that never traded has no cooldown, even with a clock near zero');
  risk.recordTrade(o, { realizedPnl: -4, unhedgedQty: 0 });
  assert.equal(risk.allow(o).reason, 'pair cooldown');
  t += 2000;
  risk.recordTrade(o, { realizedPnl: -7, unhedgedQty: 0 });
  assert.ok(risk.state.halted && /daily loss/.test(risk.state.haltReason));
  risk.reset();
  t += 2000;
  assert.equal(risk.allow(o).reason, 'daily loss limit reached');
  t += 86_400_000;
  assert.ok(risk.allow(o).ok, 'new UTC day resets PnL and count');
  risk.recordError(Error('x'));
  assert.ok(!risk.state.halted);
  risk.recordError(Error('y'));
  assert.ok(risk.state.halted);
});

test('fill simulation honours the IOC limit price', () => {
  const { asks } = books().a;
  assert.deepEqual(fillAgainst(asks, 5, 100.1, 'buy'), { filled: 2, notional: 200.1 });
});
