import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, readFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { createBookFeed } from '../src/feeds.mjs';
import { createEngine, DEFAULTS } from '../src/engine.mjs';
import { paperExecutor } from '../src/executor.mjs';
import { RiskManager } from '../src/risk.mjs';
import { record } from '../src/recorder.mjs';

/** In-memory WebSocket and timers so protocol handling is tested without a network. */
function harness() {
  const sockets = [], intervals = new Map(), timeouts = new Map();
  let clock = 1_000_000, nextId = 1;
  class FakeWS {
    constructor(url) { this.url = url; this.sent = []; this.closed = false; sockets.push(this); }
    send(m) { this.sent.push(m); }
    close() { if (this.closed) return; this.closed = true; this.onclose?.({}); }
    open() { this.onopen?.({}); }
    push(obj) { this.onmessage?.({ data: typeof obj === 'string' ? obj : JSON.stringify(obj) }); }
  }
  const timers = {
    setInterval: (fn, ms) => { const id = nextId++; intervals.set(id, { fn, ms }); return id; },
    clearInterval: id => intervals.delete(id),
    setTimeout: (fn, ms) => { const id = nextId++; timeouts.set(id, { fn, ms }); return id; },
    clearTimeout: id => timeouts.delete(id),
  };
  return {
    sockets, intervals, timeouts, timers, FakeWS,
    now: () => clock, advance: ms => { clock += ms; },
    runIntervals: () => { for (const { fn } of [...intervals.values()]) fn(); },
    runTimeouts: () => { const t = [...timeouts.entries()]; timeouts.clear(); for (const [, { fn }] of t) fn(); },
  };
}

const feedFor = (h, exchanges, symbols, extra = {}) =>
  createBookFeed({ exchanges, symbols, WebSocketImpl: h.FakeWS, now: h.now, timers: h.timers, log: () => {}, ...extra });

test('Binance: one combined partial-depth stream; each frame replaces the book', () => {
  const h = harness();
  const f = feedFor(h, ['binance'], ['BTC/USDT', 'ETH/USDT']);
  f.start();
  const ws = h.sockets[0];
  assert.equal(ws.url, 'wss://stream.binance.com:9443/stream?streams=btcusdt@depth20@100ms/ethusdt@depth20@100ms');
  ws.open();
  assert.deepEqual(ws.sent, [], 'stream names are in the URL, nothing to subscribe');
  assert.equal(f.get('BTC/USDT', 'binance'), undefined);
  ws.push({ stream: 'btcusdt@depth20@100ms', data: { lastUpdateId: 1, bids: [['100', '1'], ['99', '2']], asks: [['101', '1']] } });
  const g = f.get('BTC/USDT', 'binance');
  assert.deepEqual(g.book.bids, [[100, 1], [99, 2]]);
  assert.equal(g.t, h.now());
  ws.push({ stream: 'btcusdt@depth20@100ms', data: { lastUpdateId: 2, bids: [['98', '1']], asks: [['102', '3']] } });
  assert.deepEqual(f.get('BTC/USDT', 'binance').book.bids, [[98, 1]], 'snapshot, not merge');
  assert.equal(f.get('ETH/USDT', 'binance'), undefined);
});

test('Bybit: subscribes in chunks of 10, pings every 20s, applies deltas only after a snapshot', () => {
  const h = harness();
  const symbols = Array.from({ length: 12 }, (_, i) => `C${i}/USDT`);
  const f = feedFor(h, ['bybit'], symbols);
  f.start();
  const ws = h.sockets[0];
  assert.equal(ws.url, 'wss://stream.bybit.com/v5/public/spot');
  ws.open();
  assert.equal(ws.sent.length, 2);
  assert.deepEqual(JSON.parse(ws.sent[0]).args.length, 10);
  assert.deepEqual(JSON.parse(ws.sent[1]), { op: 'subscribe', args: ['orderbook.50.C10USDT', 'orderbook.50.C11USDT'] });
  const ping = [...h.intervals.values()].find(i => i.ms === 20_000);
  ping.fn();
  assert.deepEqual(JSON.parse(ws.sent.at(-1)), { op: 'ping' });

  const topic = 'orderbook.50.C0USDT';
  ws.push({ topic, type: 'delta', data: { s: 'C0USDT', b: [['1', '1']], a: [['2', '1']] } });
  assert.equal(f.get('C0/USDT', 'bybit'), undefined, 'a delta before the snapshot is ignored');
  ws.push({ topic, type: 'snapshot', data: { s: 'C0USDT', b: [['100.0', '1'], ['99.5', '2']], a: [['100.5', '1'], ['101.0', '4']] } });
  ws.push({ topic, type: 'delta', data: { s: 'C0USDT', b: [['99.5', '0'], ['99.8', '3']], a: [['100.5', '0.4'], ['100.7', '1']] } });
  const b = f.get('C0/USDT', 'bybit').book;
  assert.deepEqual(b.bids, [[100, 1], [99.8, 3]], 'size 0 deletes, new price inserts');
  assert.deepEqual(b.asks, [[100.5, 0.4], [100.7, 1], [101, 4]], 'existing price updates');
  ws.push({ topic, type: 'snapshot', data: { s: 'C0USDT', b: [['90', '1']], a: [['91', '1']] } });
  assert.deepEqual(f.get('C0/USDT', 'bybit').book.bids, [[90, 1]], 'a new snapshot resets the book');
  ws.push({ success: true, op: 'subscribe' });
  ws.push('not json');
  assert.ok(f.get('C0/USDT', 'bybit'));
});

test('OKX: books5 subscribe with instId, text ping keepalive, pong ignored', () => {
  const h = harness();
  const f = feedFor(h, ['okx'], ['BTC/USDT']);
  f.start();
  const ws = h.sockets[0];
  assert.equal(ws.url, 'wss://ws.okx.com:8443/ws/v5/public');
  ws.open();
  assert.deepEqual(JSON.parse(ws.sent[0]), { op: 'subscribe', args: [{ channel: 'books5', instId: 'BTC-USDT' }] });
  [...h.intervals.values()].find(i => i.ms === 25_000).fn();
  assert.equal(ws.sent.at(-1), 'ping');
  ws.push('pong');
  ws.push({ arg: { channel: 'books5', instId: 'BTC-USDT' }, data: [{ asks: [['101', '2', '0', '1']], bids: [['100', '1', '0', '1']], ts: '1' }] });
  assert.deepEqual(f.get('BTC/USDT', 'okx').book.asks, [[101, 2]]);
});

test('a disconnect clears that venue, reconnects with backoff, and an idle socket is recycled', () => {
  const h = harness();
  const f = feedFor(h, ['okx', 'gate'], ['BTC/USDT'], { idleMs: 30_000 });
  assert.deepEqual(f.venues, ['okx'], 'venues without a connector stay on REST');
  f.start();
  let ws = h.sockets[0];
  ws.open();
  ws.push({ arg: { channel: 'books5', instId: 'BTC-USDT' }, data: [{ asks: [['101', '1']], bids: [['100', '1']] }] });
  ws.close();
  assert.equal(f.get('BTC/USDT', 'okx'), undefined);
  assert.equal([...h.timeouts.values()][0].ms, 1000);
  h.runTimeouts();
  ws = h.sockets[1];
  ws.close();
  assert.equal([...h.timeouts.values()][0].ms, 2000, 'backoff doubles while nothing is received');
  h.runTimeouts();
  ws = h.sockets[2];
  ws.open();
  ws.push('pong');
  h.advance(31_000);
  h.runIntervals();
  assert.ok(ws.closed, 'no data for longer than idleMs closes the socket');
  assert.equal([...h.timeouts.values()][0].ms, 1000, 'a received message resets the backoff');
  f.stop();
  h.runTimeouts();
  assert.equal(h.sockets.length, 3, 'stopped feeds do not reconnect');
});

test('a crossed pushed book is unusable until the next valid update', () => {
  const h = harness();
  const f = feedFor(h, ['binance'], ['BTC/USDT']);
  f.start(); h.sockets[0].open();
  h.sockets[0].push({ stream: 'btcusdt@depth20@100ms', data: { bids: [['101', '1']], asks: [['100', '1']] } });
  assert.equal(f.get('BTC/USDT', 'binance'), undefined);
});

test('the engine prefers a fresh pushed book, falls back to REST when it is stale or missing', async () => {
  const market = { lotStep: 0.0001, minQty: 0.0001, tickSize: 0.01, minNotional: 5 };
  let restCalls = 0, t = 5000;
  const rest = { fetchMarket: async () => market, fetchBook: async () => { restCalls++; return { bids: [[99, 1]], asks: [[100, 1]] }; } };
  const pushed = { book: { bids: [[200, 1]], asks: [[201, 1]] }, t: 4900 };
  const feed = { venues: ['a'], get: (s, ex) => (ex === 'a' ? pushed : undefined) };
  const cfg = { ...DEFAULTS, exchanges: ['a', 'b'], symbols: ['X/USDT'], fees: { a: 0.001, b: 0.001 }, journalPath: null };
  const engine = createEngine({ cfg, adapters: { a: rest, b: rest }, feed, now: () => t, log: () => {},
    executor: paperExecutor({ balances: {}, fees: cfg.fees }), risk: new RiskManager(cfg) });
  await engine.init();
  let books = await engine.snapshot('X/USDT');
  assert.equal(books.a.bids[0][0], 200);
  assert.equal(restCalls, 1, 'only the venue without a feed was polled');
  t = 4900 + cfg.maxBookAgeMs + 1;
  books = await engine.snapshot('X/USDT');
  assert.equal(books.a.bids[0][0], 99, 'stale pushed book replaced by REST');
});

test('recorder writes pushed and polled books in timestamp order', async () => {
  const dir = mkdtempSync(join(tmpdir(), 'arb-feed-'));
  const out = join(dir, 'r.jsonl');
  let t = 1000;
  const market = { lotStep: 0.0001, minQty: 0.0001, tickSize: 0.01, minNotional: 5 };
  // The polled venue answers late (t advances during the call); the pushed book carries an earlier arrival time.
  const slow = { fetchMarket: async () => market, fetchBook: async () => { t += 50; return { bids: [[99, 1]], asks: [[100, 1]] }; } };
  let pushedAt = 990;
  const feed = { venues: ['p'], get: () => ({ t: pushedAt, book: { bids: [[99, 1]], asks: [[100, 1]] } }) };
  const cfg = { ...DEFAULTS, exchanges: ['p', 's'], symbols: ['X/USDT'], intervalMs: 0 };
  let ticks = 0;
  await record({ cfg, out, durationMs: 1e9, feed, log: () => {}, adapters: { p: slow, s: slow },
    now: () => t, signal: { get aborted() { pushedAt = t - 10; return ++ticks > 4; } } });
  const ts = readFileSync(out, 'utf8').trim().split('\n').map(JSON.parse).filter(r => !r.type).map(r => r.t);
  assert.ok(ts.length >= 4);
  assert.deepEqual(ts, [...ts].sort((a, b) => a - b));
});
