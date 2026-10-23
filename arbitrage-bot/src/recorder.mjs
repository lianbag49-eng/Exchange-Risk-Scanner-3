// Record what the bot would see: market rules once, then every venue's book each interval, stamped with the
// local receive time. The output is the input format of the backtester.
import { appendFileSync, mkdirSync } from 'node:fs';
import { dirname } from 'node:path';

export async function record({ cfg, adapters, out, durationMs, depth = cfg.depth, signal, now = Date.now, log = console.log }) {
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
  while (!signal?.aborted && now() < end) {
    const started = now();
    await Promise.all(live.map(async ([ex, symbol]) => {
      try {
        const b = await adapters[ex].fetchBook(symbol, depth), t = now();
        if (t - started <= cfg.maxBookAgeMs * 4) { write({ t, ex, symbol, bids: b.bids, asks: b.asks }); rows++; }
      } catch { errors++; }
    }));
    if (++ticks % 30 === 0) log(`${ticks} ticks · ${rows} books · ${errors} errors`);
    await new Promise(r => setTimeout(r, Math.max(0, cfg.intervalMs - (now() - started))));
  }
  return { ticks, rows, errors };
}
