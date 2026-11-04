import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, writeFileSync, readFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { execFileSync } from 'node:child_process';
import { backtest, readRecording, formatReport } from '../src/backtest.mjs';
import { record } from '../src/recorder.mjs';
import { DEFAULTS } from '../src/engine.mjs';

const market = { lotStep: 0.0001, minQty: 0.0001, tickSize: 0.01, minNotional: 5 };
const cfg = (over = {}) => ({
  ...DEFAULTS, exchanges: ['lo', 'hi'], symbols: ['X/USDT'], fees: { lo: 0.001, hi: 0.001 },
  intervalMs: 1000, maxBookAgeMs: 800, cooldownMs: 3000, maxNotional: 100, journalPath: null, statePath: null,
  paperBalances: { lo: { USDT: 1000, X: 0 }, hi: { USDT: 0, X: 10 } }, ...over,
});
const snap = (t, ex, bid, ask, sym = 'X/USDT', q = 5) => ({ t, ex, symbol: sym, bids: [[bid, q]], asks: [[ask, q]] });
const markets = ['lo', 'hi'].map(ex => ({ type: 'market', ex, symbol: 'X/USDT', market }));
const steady = (ms, gap = 0.6) => {
  const r = [...markets];
  for (let t = 0; t <= ms; t += 200) r.push(snap(t, 'lo', 99.9, 100), snap(t + 50, 'hi', 100 + gap, 100.1 + gap));
  return r;
};

test('a persistent gap trades at the expected edge, respects cooldown and moves inventory between venues', async () => {
  const { summary, balances, trades } = await backtest(steady(20_000), cfg(), { latencyMs: 300 });
  assert.ok(summary.trades >= 5 && summary.trades <= 7, `cooldown 3s over 20s, got ${summary.trades}`);
  assert.equal(summary.oneLegged, 0);
  assert.equal(summary.fullyHedged, summary.trades);
  assert.ok(Math.abs(summary.realizedPnl - summary.expectedPnl) < 1e-9);
  assert.ok(summary.realizedPnl > 0 && summary.winRate === 1);
  for (const t of trades) assert.ok(t.at - t.decidedAt === 300);
  assert.ok(Math.abs(balances.lo.X + balances.hi.X - 10) < 1e-9, 'base only moves between venues');
  assert.ok(balances.lo.USDT >= 0 && balances.hi.X >= 0);
  assert.ok(summary.inventoryDrift.lo.X > 0 && summary.inventoryDrift.hi.X < 0);
});

test('a gap that closes during order latency becomes a one-legged fill whose flatten cost is charged', async () => {
  const records = [...markets, snap(0, 'lo', 99.9, 100), snap(0, 'hi', 100, 100.1), snap(900, 'lo', 99.9, 100), snap(900, 'hi', 100.6, 100.7), snap(1200, 'hi', 100, 100.1)];
  const slow = (await backtest(records, cfg(), { latencyMs: 300 })).summary;
  assert.equal(slow.trades, 1);
  assert.equal(slow.oneLegged, 1);
  assert.ok(slow.expectedPnl > 0 && slow.realizedPnl < 0, 'bought at 100, had to dump at 99.9 after fees');
  assert.ok(slow.flattenCost > 0);
  assert.match(formatReport(slow), /한쪽만 체결 1/);
  assert.doesNotMatch(formatReport((await backtest(steady(5000), cfg(), { latencyMs: 300 })).summary), /갱신 간격/);
  assert.match(formatReport((await backtest(steady(5000), cfg(), { latencyMs: 50 })).summary), /갱신 간격\(평균 200ms\)/);
  const fast = (await backtest(records, cfg(), { latencyMs: 0 })).summary;
  assert.equal(fast.oneLegged, 0);
  assert.ok(fast.realizedPnl > 0);
});

test('stale books are ignored, out-of-order rows are counted, and nothing trades below threshold', async () => {
  const stale = [...markets, snap(0, 'hi', 100.6, 100.7), snap(1000, 'lo', 99.9, 100), snap(1900, 'lo', 99.9, 100)];
  assert.equal((await backtest(stale, cfg())).summary.trades, 0, 'hi book is 2s old at the decision');
  const disorder = [...markets, snap(500, 'lo', 99.9, 100), snap(100, 'hi', 100.6, 100.7)];
  assert.equal((await backtest(disorder, cfg())).summary.outOfOrder, 1);
  assert.equal((await backtest(steady(10_000, 0.1), cfg())).summary.trades, 0, '10bps gap < fees + 15bps');
});

test('funds committed to in-flight orders are not reused across symbols in the same tick', async () => {
  const two = cfg({ symbols: ['X/USDT', 'Y/USDT'], maxNotional: 100, paperBalances: { lo: { USDT: 120, X: 0, Y: 0 }, hi: { USDT: 0, X: 10, Y: 10 } } });
  const r = [...markets, ...['lo', 'hi'].map(ex => ({ type: 'market', ex, symbol: 'Y/USDT', market }))];
  for (let t = 0; t <= 1000; t += 200) r.push(snap(t, 'lo', 99.9, 100), snap(t, 'hi', 100.6, 100.7), snap(t, 'lo', 99.9, 100, 'Y/USDT'), snap(t, 'hi', 100.6, 100.7, 'Y/USDT'));
  const { trades, balances } = await backtest(r, two, { latencyMs: 300 });
  assert.equal(trades.length, 2);
  assert.ok(balances.lo.USDT >= -1e-9, `USDT went negative: ${balances.lo.USDT}`);
  assert.ok(trades[1].qty < trades[0].qty, 'second symbol sized to what was left');
});

test('daily loss limit stops the replay the same way it stops the live loop', async () => {
  const r = [...markets];
  for (let k = 0; k < 10; k++) {
    const t0 = k * 5000;
    r.push(snap(t0, 'lo', 99.9, 100), snap(t0, 'hi', 100, 100.1), snap(t0 + 900, 'lo', 99.9, 100), snap(t0 + 900, 'hi', 100.6, 100.7), snap(t0 + 1200, 'hi', 100, 100.1));
  }
  const { summary } = await backtest(r, cfg({ dailyLossLimit: 0.2 }), { latencyMs: 300 });
  assert.ok(summary.haltReason && /daily loss/.test(summary.haltReason));
  assert.ok(summary.trades < 10);
});

test('recorder output replays through the CLI backtest', async () => {
  const dir = mkdtempSync(join(tmpdir(), 'arb-bt-'));
  const out = join(dir, 'books.jsonl');
  let t = 0, tick = 0;
  const book = gap => ({ fetchMarket: async () => market, fetchBook: async () => ({ bids: [[100 + gap - 0.1, 5]], asks: [[100 + gap, 5]] }) });
  const res = await record({
    cfg: cfg({ intervalMs: 0 }), out, durationMs: 5, now: () => (tick++, t += 1), log: () => {},
    adapters: { lo: book(0), hi: book(0.7) },
  });
  assert.ok(res.rows > 0 && res.errors === 0);
  const rows = [];
  for await (const r of readRecording(out)) rows.push(r);
  assert.equal(rows.filter(r => r.type === 'market').length, 2);
  assert.deepEqual(rows.at(-1).bids[0].length, 2);

  writeFileSync(join(dir, 'cfg.json'), JSON.stringify({ exchanges: ['binance', 'bybit'], symbols: ['XT/USDT'], paperBalances: { binance: { USDT: 1000 }, bybit: { XT: 10 } } }));
  const rec = steady(10_000).map(r => ({ ...r, symbol: 'XT/USDT', ex: r.ex === 'lo' ? 'binance' : 'bybit' }));
  writeFileSync(join(dir, 'rec.jsonl'), rec.map(r => JSON.stringify(r)).join('\n') + '\n');
  const stdout = execFileSync(process.execPath, ['cli.mjs', 'backtest', '--config', join(dir, 'cfg.json'), '--data', join(dir, 'rec.jsonl'), '--trades', join(dir, 't.jsonl')], { cwd: new URL('..', import.meta.url), encoding: 'utf8' });
  assert.match(stdout, /거래 \d+건/);
  assert.match(stdout, /binance>bybit/);
  assert.ok(readFileSync(join(dir, 't.jsonl'), 'utf8').trim().split('\n').length >= 1);
});
