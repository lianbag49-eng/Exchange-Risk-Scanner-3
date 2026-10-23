// Replay recorded multi-venue order book snapshots through the same sizing and risk code the live loop uses.
// Orders are decided on the books visible at decision time and filled against the books seen `latencyMs` later,
// so prices that move away during the round trip show up as partial or one-legged fills.
import { createReadStream } from 'node:fs';
import { createInterface } from 'node:readline';
import { bestOpportunity } from './opportunity.mjs';
import { fillAgainst, normaliseBook } from './book.mjs';
import { RiskManager } from './risk.mjs';

const DEFAULT_MARKET = { lotStep: 0.000001, minQty: 0.000001, tickSize: 0.01, minNotional: 5 };
const split = s => { const [base, quote] = s.split('/'); return { base, quote }; };

/** Stream records from a JSONL file written by `record`: market rules first, then {t, ex, symbol, bids, asks}. */
export async function* readRecording(path) {
  const rl = createInterface({ input: createReadStream(path), crlfDelay: Infinity });
  let n = 0;
  for await (const line of rl) {
    n++;
    if (!line.trim()) continue;
    try { yield JSON.parse(line); } catch { throw Error(`${path}:${n}: invalid JSON`); }
  }
}

/**
 * Run a backtest. `records` is any (async) iterable in timestamp order.
 * Returns summary metrics plus every simulated trade.
 */
export async function backtest(records, cfg, { latencyMs = 300 } = {}) {
  const fees = cfg.fees, markets = {}, latest = {}, pending = [], trades = [], reserved = {};
  const balances = structuredClone(cfg.paperBalances);
  let clock = 0, nextTick = null, first = null, snapshots = 0, decisions = 0, outOfOrder = 0, gapSum = 0, gaps = 0;
  const risk = new RiskManager({ ...cfg, maxConsecutiveErrors: Infinity }, { now: () => clock });

  const freshBooks = symbol => {
    const books = {};
    for (const [ex, b] of Object.entries(latest[symbol] ?? {})) if (clock - b.t <= cfg.maxBookAgeMs) books[ex] = b.book;
    return books;
  };
  const venueBalances = (symbol, ids) => {
    const { base, quote } = split(symbol);
    const free = (id, a) => (balances[id]?.[a] ?? 0) - (reserved[id]?.[a] ?? 0);
    return Object.fromEntries(ids.map(id => [id, { base: free(id, base), quote: free(id, quote) }]));
  };
  const move = (ex, asset, d) => { balances[ex] ??= {}; balances[ex][asset] = (balances[ex][asset] ?? 0) + d; };
  // Funds committed to in-flight orders are not offered to the next decision (same as exchange-side locking).
  const reserve = (opp, sign) => {
    const { base, quote } = split(opp.symbol);
    (reserved[opp.buy] ??= {})[quote] = (reserved[opp.buy][quote] ?? 0) + sign * opp.expectedCost * (1 + fees[opp.buy]);
    (reserved[opp.sell] ??= {})[base] = (reserved[opp.sell][base] ?? 0) + sign * opp.qty;
  };

  function decide() {
    decisions++;
    for (const symbol of cfg.symbols) {
      const books = freshBooks(symbol), ids = Object.keys(books);
      if (ids.length < 2) continue;
      const mk = Object.fromEntries(ids.map(id => [id, markets[`${id}:${symbol}`] ?? cfg.markets?.[id] ?? DEFAULT_MARKET]));
      const opp = bestOpportunity({ symbol, books, markets: mk, fees, balances: venueBalances(symbol, ids), minEdgeBps: cfg.minEdgeBps, maxNotional: cfg.maxNotional });
      if (!opp || !risk.allow(opp).ok) continue;
      // Reserve the cooldown now so the same stale signal is not re-entered while the orders are in flight.
      risk.state.lastTradeAt[`${opp.symbol}:${opp.buy}>${opp.sell}`] = clock;
      reserve(opp, 1);
      pending.push({ fillAt: clock + latencyMs, decidedAt: clock, opp });
    }
  }

  function fill({ opp, decidedAt }) {
    reserve(opp, -1);
    const { base, quote } = split(opp.symbol);
    const A = latest[opp.symbol]?.[opp.buy]?.book, B = latest[opp.symbol]?.[opp.sell]?.book;
    const b = A ? fillAgainst(A.asks, opp.qty, opp.buyLimit, 'buy') : { filled: 0, notional: 0 };
    const s = B ? fillAgainst(B.bids, opp.qty, opp.sellLimit, 'sell') : { filled: 0, notional: 0 };
    move(opp.buy, quote, -b.notional * (1 + fees[opp.buy])); move(opp.buy, base, b.filled);
    move(opp.sell, base, -s.filled); move(opp.sell, quote, s.notional * (1 - fees[opp.sell]));
    const hedged = Math.min(b.filled, s.filled);
    const part = (leg, q) => (leg.filled > 0 ? leg.notional * (q / leg.filled) : 0);
    let pnl = part(s, hedged) * (1 - fees[opp.sell]) - part(b, hedged) * (1 + fees[opp.buy]);

    // A one-legged fill halts the live bot. Here it is flattened at the over-filled venue's current book with a
    // taker order so the replay can continue; the flatten cost is charged to PnL and the event is counted.
    let unhedged = b.filled - s.filled, flattenCost = 0;
    if (Math.abs(unhedged) >= opp.step / 2) {
      const ex = unhedged > 0 ? opp.buy : opp.sell, book = unhedged > 0 ? A : B, qty = Math.abs(unhedged);
      const levels = unhedged > 0 ? book.bids : book.asks;
      const f = fillAgainst(levels, qty, unhedged > 0 ? 0 : Infinity, unhedged > 0 ? 'sell' : 'buy');
      const leftover = qty - f.filled;
      // Book too thin to flatten: value the rest at the worst visible level (pessimistic).
      const worst = levels[levels.length - 1][0], notional = f.notional + leftover * worst;
      const legPart = unhedged > 0 ? part(b, qty) * (1 + fees[opp.buy]) : part(s, qty) * (1 - fees[opp.sell]);
      if (unhedged > 0) { move(ex, base, -qty); move(ex, quote, notional * (1 - fees[ex])); flattenCost = legPart - notional * (1 - fees[ex]); }
      else { move(ex, base, qty); move(ex, quote, -notional * (1 + fees[ex])); flattenCost = notional * (1 + fees[ex]) - legPart; }
      pnl -= flattenCost;
    } else unhedged = 0;

    const t = { at: clock, decidedAt, symbol: opp.symbol, buy: opp.buy, sell: opp.sell, qty: opp.qty, expectedProfit: opp.expectedProfit, edgeBps: opp.edgeBps,
      buyFilled: b.filled, sellFilled: s.filled, hedged, unhedged: Number(unhedged.toFixed(12)), flattenCost, pnl };
    trades.push(t);
    risk.recordTrade(opp, { realizedPnl: pnl, unhedgedQty: 0 });
  }

  for await (const r of records) {
    if (r.type === 'market') { markets[`${r.ex}:${r.symbol}`] = r.market; continue; }
    if (!Number.isFinite(r.t)) continue;
    if (r.t < clock) { outOfOrder++; continue; }
    // Advance the data clock: run due fills and decision ticks up to (not including) this record's time.
    while (true) {
      const due = Math.min(pending[0]?.fillAt ?? Infinity, nextTick ?? Infinity);
      if (due > r.t) break;
      clock = due;
      if (pending[0]?.fillAt === due) fill(pending.shift());
      else { decide(); nextTick += cfg.intervalMs; }
    }
    clock = r.t;
    first ??= r.t;
    nextTick ??= r.t + cfg.intervalMs;
    let book;
    try { book = normaliseBook(r); } catch { continue; }
    const prev = latest[r.symbol]?.[r.ex];
    if (prev) { gapSum += r.t - prev.t; gaps++; }
    (latest[r.symbol] ??= {})[r.ex] = { t: r.t, book };
    snapshots++;
  }
  while (pending.length) { clock = Math.max(clock, pending[0].fillAt); fill(pending.shift()); }

  const summary = summarise({ trades, balances, cfg, latest, start: first, end: clock, snapshots, decisions, outOfOrder, latencyMs });
  summary.haltReason = risk.state.haltReason;
  summary.avgUpdateMs = gaps ? gapSum / gaps : 0;
  return { summary, trades, balances };
}

function summarise({ trades, balances, cfg, latest, start, end, snapshots, decisions, outOfOrder, latencyMs }) {
  let equity = 0, peak = 0, maxDrawdown = 0;
  for (const t of trades) { equity += t.pnl; peak = Math.max(peak, equity); maxDrawdown = Math.max(maxDrawdown, peak - equity); }
  const byRoute = {};
  for (const t of trades) {
    const k = `${t.symbol} ${t.buy}>${t.sell}`;
    const r = byRoute[k] ??= { trades: 0, pnl: 0, expected: 0 };
    r.trades++; r.pnl += t.pnl; r.expected += t.expectedProfit;
  }
  // Mark both starting and ending inventory at the last mid so the PnL figure is not a price move.
  const mid = {};
  for (const [symbol, venues] of Object.entries(latest)) {
    const mids = Object.values(venues).map(v => (v.book.bids[0][0] + v.book.asks[0][0]) / 2);
    mid[split(symbol).base] = mids.reduce((a, b) => a + b, 0) / mids.length;
  }
  const value = bal => Object.values(bal).reduce((sum, v) => sum + Object.entries(v).reduce((s, [a, q]) => s + q * (a in mid ? mid[a] : 1), 0), 0);
  const startValue = value(cfg.paperBalances), endValue = value(balances);
  const quoteAssets = new Set(cfg.symbols.map(s => split(s).quote));
  const drift = {};
  for (const [ex, bal] of Object.entries(balances)) for (const [a, q] of Object.entries(bal)) {
    const s0 = cfg.paperBalances[ex]?.[a] ?? 0;
    if (Math.abs(q - s0) > 1e-9) (drift[ex] ??= {})[a] = Number((q - s0).toFixed(8));
  }
  const n = trades.length, pnl = trades.reduce((s, t) => s + t.pnl, 0);
  return {
    hours: (end - start) / 3.6e6, snapshots, decisions, outOfOrder, latencyMs,
    trades: n,
    fullyHedged: trades.filter(t => !t.unhedged && t.hedged >= t.qty - 1e-12).length,
    partial: trades.filter(t => !t.unhedged && t.hedged < t.qty - 1e-12).length,
    oneLegged: trades.filter(t => t.unhedged).length,
    expectedPnl: trades.reduce((s, t) => s + t.expectedProfit, 0),
    realizedPnl: pnl,
    flattenCost: trades.reduce((s, t) => s + t.flattenCost, 0),
    winRate: n ? trades.filter(t => t.pnl > 0).length / n : 0,
    avgEdgeBps: n ? trades.reduce((s, t) => s + t.edgeBps, 0) / n : 0,
    maxDrawdown,
    capital: startValue,
    returnPct: startValue ? (endValue - startValue) / startValue * 100 : 0,
    quote: [...quoteAssets].join('/'),
    byRoute,
    inventoryDrift: drift,
  };
}

/** Human-readable report. */
export function formatReport(s) {
  const f = (x, d = 4) => Number(x).toFixed(d);
  const lines = [
    `기간 ${f(s.hours, 2)}시간 · 스냅샷 ${s.snapshots} · 판단 ${s.decisions}회 · 지연 ${s.latencyMs}ms${s.outOfOrder ? ` · 순서 어긋난 기록 ${s.outOfOrder}건 제외` : ''}`,
    `거래 ${s.trades}건 (완전 체결 ${s.fullyHedged} · 양쪽 부분 체결 ${s.partial} · 한쪽만 체결 ${s.oneLegged})`,
    `예상 손익 ${f(s.expectedPnl)} → 실현 손익 ${f(s.realizedPnl)} ${s.quote} (한쪽 체결 정리 비용 ${f(s.flattenCost)})`,
    `승률 ${f(s.winRate * 100, 1)}% · 평균 예상 수익률 ${f(s.avgEdgeBps, 1)}bp · 최대 낙폭 ${f(s.maxDrawdown)} · 자본 대비 ${f(s.returnPct, 4)}%`,
  ];
  for (const [k, r] of Object.entries(s.byRoute).sort((a, b) => b[1].pnl - a[1].pnl))
    lines.push(`  ${k.padEnd(28)} ${String(r.trades).padStart(5)}건  예상 ${f(r.expected).padStart(10)}  실현 ${f(r.pnl).padStart(10)}`);
  if (Object.keys(s.inventoryDrift).length) lines.push(`재고 변화 ${JSON.stringify(s.inventoryDrift)}`);
  if (s.avgUpdateMs > s.latencyMs)
    lines.push(`※ 기록의 호가 갱신 간격(평균 ${f(s.avgUpdateMs, 0)}ms)이 지연(${s.latencyMs}ms)보다 깁니다. 그 사이 가격 변화가 데이터에 없어 결과가 낙관적입니다. 더 짧은 간격으로 기록하세요.`);
  if (s.haltReason) lines.push(`※ 리스크 한도로 중간에 거래가 멈췄습니다: ${s.haltReason}`);
  if (s.oneLegged) lines.push(`※ 실거래 봇은 한쪽 체결 ${s.oneLegged}건마다 정지합니다. 백테스트는 즉시 정리했다고 가정하고 계속 진행했습니다.`);
  return lines.join('\n');
}
