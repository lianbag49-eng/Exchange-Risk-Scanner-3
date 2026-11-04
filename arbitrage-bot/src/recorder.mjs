// Record what the bot would see: market rules once, then every venue's book each interval, stamped with the
// local receive time. The output is the input format of the backtester.
import { appendFileSync, mkdirSync } from 'node:fs';
import { dirname } from 'node:path';

export async function record({ cfg, adapters, out, durationMs, depth = cfg.depth, signal, now = Date.now, log = console.log, feed = null }) {
  mkdirSync(dirname(out), { recursive: true });
  const write = r => appendFileSync(out, JSON.stringify(r) + '\n');
  const live = [];
  for (const symbol of cfg.symbols) for (const ex of cfg.exchanges) {
    try { write({ type: 'market', ex, symbol, market: await adapters[ex].fetchMarket(symbol) }); live.push([ex, symbol]); }
    catch (e) { log(`skip ${ex} ${symbol}: ${e.message}`); }
  }
  if (!live.length) throw Error('nothing to record');
  const end = now() + durationMs;
  let ticks = 0, rows = 0, errors = 0;
  const lastPushed = {};
  // Rows are buffered and written in timestamp order (the backtester skips rows that go back in time). A row
  // produced later always has t >= the start of the current tick, so everything older than that can be flushed.
  const pending = [];
  const flush = before => {
    pending.sort((a, b) => a.t - b.t);
    const i = before === undefined ? pending.length : pending.findIndex(r => r.t >= before);
    const ready = pending.splice(0, i === -1 ? pending.length : i);
    for (const r of ready) write(r);
  };
  while (!signal?.aborted && now() < end) {
    const started = now();
    await Promise.all(live.map(async ([ex, symbol]) => {
      // A pushed book is written once per update, stamped with when it arrived (not when it was sampled).
      if (feed?.venues.includes(ex)) {
        const p = feed.get(symbol, ex), k = `${ex}|${symbol}`;
        if (p && p.t !== lastPushed[k]) { lastPushed[k] = p.t; pending.push({ t: p.t, ex, symbol, bids: p.book.bids, asks: p.book.asks }); rows++; }
        return;
      }
      try {
        const b = await adapters[ex].fetchBook(symbol, depth), t = now();
        if (t - started <= cfg.maxBookAgeMs * 4) { pending.push({ t, ex, symbol, bids: b.bids, asks: b.asks }); rows++; }
      } catch { errors++; }
    }));
    flush(started);
    if (++ticks % 30 === 0) log(`${ticks} ticks · ${rows} books · ${errors} errors`);
    await new Promise(r => setTimeout(r, Math.max(0, cfg.intervalMs - (now() - started))));
  }
  flush();
  return { ticks, rows, errors };
}
