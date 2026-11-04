// WebSocket order book feeds (opt-in with --feed ws). Each venue pushes its book continuously, so the loop reads
// the latest copy instead of paying a REST round trip per tick. A book is stamped with the local receive time;
// the engine still rejects anything older than maxBookAgeMs and falls back to REST for venues without a feed.
import { normaliseBook } from './book.mjs';

const nativeOf = s => s.replace('/', '');

/** Per-venue protocol: where to connect, what to send, how to keep alive, how to turn a frame into book updates. */
export const CONNECTORS = {
  // Partial book depth: a full top-20 snapshot every 100ms on a combined stream. Server pings are answered by
  // the WebSocket implementation; connections are dropped by Binance after 24h and reconnected here.
  binance: {
    url: symbols => `wss://stream.binance.com:9443/stream?streams=${symbols.map(s => `${nativeOf(s).toLowerCase()}@depth20@100ms`).join('/')}`,
    subscribe: () => [],
    keepalive: null,
    parse(msg, symbols) {
      const sym = symbols.find(s => msg.stream === `${nativeOf(s).toLowerCase()}@depth20@100ms`);
      return sym && msg.data ? [{ symbol: sym, type: 'snapshot', bids: msg.data.bids, asks: msg.data.asks }] : [];
    },
  },
  // Level-50 book: one snapshot after subscribing, then deltas (size 0 deletes a level). A new snapshot resets.
  bybit: {
    url: () => 'wss://stream.bybit.com/v5/public/spot',
    // Spot accepts at most 10 topics per subscribe request.
    subscribe: symbols => {
      const topics = symbols.map(s => `orderbook.50.${nativeOf(s)}`), out = [];
      for (let i = 0; i < topics.length; i += 10) out.push(JSON.stringify({ op: 'subscribe', args: topics.slice(i, i + 10) }));
      return out;
    },
    keepalive: { everyMs: 20_000, message: JSON.stringify({ op: 'ping' }) },
    parse(msg, symbols) {
      if (!msg.topic?.startsWith('orderbook.') || !msg.data) return [];
      const sym = symbols.find(s => nativeOf(s) === msg.data.s);
      return sym && (msg.type === 'snapshot' || msg.type === 'delta') ? [{ symbol: sym, type: msg.type, bids: msg.data.b ?? [], asks: msg.data.a ?? [] }] : [];
    },
  },
  // books5: full 5-level snapshot pushed every 100ms when it changes. Idle sockets are closed after 30s,
  // so a text "ping" is sent every 25s.
  okx: {
    url: () => 'wss://ws.okx.com:8443/ws/v5/public',
    subscribe: symbols => [JSON.stringify({ op: 'subscribe', args: symbols.map(s => ({ channel: 'books5', instId: s.replace('/', '-') })) })],
    keepalive: { everyMs: 25_000, message: 'ping' },
    parse(msg, symbols) {
      if (msg.arg?.channel !== 'books5' || !msg.data?.[0]) return [];
      const sym = symbols.find(s => s.replace('/', '-') === msg.arg.instId);
      return sym ? [{ symbol: sym, type: 'snapshot', bids: msg.data[0].bids, asks: msg.data[0].asks }] : [];
    },
  },
};

/**
 * Start one socket per supported venue. get(symbol, ex) returns { t, book } or undefined (no feed, not yet
 * received, disconnected, or the latest update was invalid).
 */
export function createBookFeed({ exchanges, symbols, depth = 20, WebSocketImpl = globalThis.WebSocket, now = Date.now,
  log = console.log, idleMs = 30_000, timers = { setTimeout, clearTimeout, setInterval, clearInterval } }) {
  if (!WebSocketImpl) throw Error('this Node.js has no global WebSocket; use Node 22 or newer, or --feed rest');
  const venues = exchanges.filter(ex => CONNECTORS[ex]);
  const state = {};   // `${ex}|${symbol}` -> { t, bids: Map, asks: Map, book, dirty }
  const conns = {};
  let stopped = false;

  const key = (ex, s) => `${ex}|${s}`;
  const clearVenue = ex => { for (const s of symbols) delete state[key(ex, s)]; };

  function apply(ex, u) {
    const k = key(ex, u.symbol);
    let st = state[k];
    if (u.type === 'snapshot' || !st) {
      if (u.type !== 'snapshot') return;   // a delta before any snapshot cannot be applied
      st = state[k] = { bids: new Map(), asks: new Map() };
    }
    for (const [side, rows] of [['bids', u.bids], ['asks', u.asks]]) for (const [p, q] of rows) {
      if (Number(q) === 0) st[side].delete(p); else st[side].set(p, q);
    }
    st.t = now(); st.dirty = true;
  }

  function connect(ex, attempt = 0) {
    if (stopped) return;
    const c = CONNECTORS[ex];
    const ws = new WebSocketImpl(c.url(symbols));
    const conn = conns[ex] = { ws, attempt, lastMsg: now(), timers: [] };
    const closeQuietly = () => { try { ws.close(); } catch {} };
    ws.onopen = () => {
      for (const m of c.subscribe(symbols)) ws.send(m);
      if (c.keepalive) conn.timers.push(timers.setInterval(() => { try { ws.send(c.keepalive.message); } catch {} }, c.keepalive.everyMs));
      conn.timers.push(timers.setInterval(() => { if (now() - conn.lastMsg > idleMs) { log(`feed ${ex}: idle, reconnecting`); closeQuietly(); } }, Math.min(idleMs, 5000)));
    };
    ws.onmessage = ev => {
      conn.lastMsg = now(); conn.attempt = 0;
      const text = typeof ev.data === 'string' ? ev.data : String(ev.data);
      if (text === 'pong') return;
      let msg;
      try { msg = JSON.parse(text); } catch { return; }
      for (const u of c.parse(msg, symbols)) apply(ex, u);
    };
    ws.onerror = () => {};
    ws.onclose = () => {
      for (const t of conn.timers) timers.clearInterval(t);
      clearVenue(ex);
      if (stopped || conns[ex] !== conn) return;
      const delay = Math.min(30_000, 1000 * 2 ** conn.attempt);
      log(`feed ${ex}: disconnected, retry in ${delay / 1000}s`);
      conn.retry = timers.setTimeout(() => connect(ex, conn.attempt + 1), delay);
    };
  }

  return {
    venues,
    start() { for (const ex of venues) connect(ex); },
    stop() {
      stopped = true;
      for (const conn of Object.values(conns)) {
        for (const t of conn.timers) timers.clearInterval(t);
        if (conn.retry) timers.clearTimeout(conn.retry);
        try { conn.ws.close(); } catch {}
      }
    },
    get(symbol, ex) {
      const st = state[key(ex, symbol)];
      if (!st) return undefined;
      if (st.dirty) {
        const side = (m, desc) => [...m].map(([p, q]) => [Number(p), Number(q)]).sort((a, b) => (desc ? b[0] - a[0] : a[0] - b[0])).slice(0, depth);
        try { st.book = normaliseBook({ bids: side(st.bids, true), asks: side(st.asks, false) }); } catch { st.book = null; }
        st.dirty = false;
      }
      return st.book ? { t: st.t, book: st.book } : undefined;
    },
  };
}
