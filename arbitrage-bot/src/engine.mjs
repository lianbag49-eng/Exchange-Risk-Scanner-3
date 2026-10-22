// Polling loop: snapshot books on every venue at once, size the best opportunity, gate it, execute, journal it.
import { appendFileSync, mkdirSync } from 'node:fs';
import { dirname } from 'node:path';
import { bestOpportunity, spreads } from './opportunity.mjs';

export const DEFAULTS = {
  exchanges: ['binance', 'bybit', 'okx'],
  symbols: ['BTC/USDT', 'ETH/USDT'],
  // Default spot taker fees (fraction). Override with your actual VIP tier.
  fees: { binance: 0.001, bybit: 0.001, okx: 0.001 },
  minEdgeBps: 15,
  maxNotional: 200,
  depth: 20,
  intervalMs: 2000,
  maxBookAgeMs: 800,
  dailyLossLimit: 20,
  maxTradesPerDay: 200,
  cooldownMs: 3000,
  maxConsecutiveErrors: 5,
  balanceRefreshTicks: 30,
  journalPath: 'data/journal.jsonl',
  statePath: 'data/risk-state.json',
  paperBalances: {
    binance: { USDT: 1000, BTC: 0.01, ETH: 0.3 },
    bybit: { USDT: 1000, BTC: 0.01, ETH: 0.3 },
    okx: { USDT: 1000, BTC: 0.01, ETH: 0.3 },
  },
};

export function validateConfig(cfg) {
  const errs = [];
  if (!cfg.exchanges?.length || cfg.exchanges.length < 2) errs.push('need at least two exchanges');
  for (const id of cfg.exchanges ?? []) if (!(cfg.fees?.[id] >= 0 && cfg.fees[id] < 0.01)) errs.push(`fee for ${id} must be a fraction in [0, 0.01)`);
  for (const s of cfg.symbols ?? []) if (!/^[A-Z0-9]{2,15}\/[A-Z0-9]{2,15}$/.test(s)) errs.push(`bad symbol ${s}`);
  for (const k of ['minEdgeBps', 'maxNotional', 'intervalMs', 'maxBookAgeMs', 'dailyLossLimit', 'maxTradesPerDay', 'maxConsecutiveErrors'])
    if (!(Number.isFinite(cfg[k]) && cfg[k] > 0)) errs.push(`${k} must be a positive number`);
  if (cfg.minEdgeBps < 5) errs.push('minEdgeBps below 5 is almost always eaten by slippage and latency');
  if (errs.length) throw Error(`invalid config:\n - ${errs.join('\n - ')}`);
  return cfg;
}

const split = s => { const [base, quote] = s.split('/'); return { base, quote }; };

export function createEngine({ cfg, adapters, executor, risk, log = console.log, now = Date.now }) {
  const markets = {};
  let tick = 0;

  const journal = entry => {
    const line = JSON.stringify({ at: new Date(now()).toISOString(), mode: executor.mode, ...entry });
    if (cfg.journalPath) { mkdirSync(dirname(cfg.journalPath), { recursive: true }); appendFileSync(cfg.journalPath, line + '\n'); }
    return line;
  };

  async function init() {
    for (const symbol of cfg.symbols) {
      markets[symbol] = {};
      await Promise.all(cfg.exchanges.map(async id => {
        try { markets[symbol][id] = await adapters[id].fetchMarket(symbol); }
        catch (e) { log(`skip ${id} ${symbol}: ${e.message}`); }
      }));
      if (Object.keys(markets[symbol]).length < 2) { log(`skip ${symbol}: listed on fewer than two venues`); delete markets[symbol]; }
    }
    if (!Object.keys(markets).length) throw Error('no symbol is tradable on two or more venues');
    if (executor.mode === 'live') await executor.refreshBalances(cfg.exchanges);
    return markets;
  }

  /** Fetch every venue's book in parallel; drop slow responses so both legs come from the same moment. */
  async function snapshot(symbol) {
    const ids = Object.keys(markets[symbol]), books = {};
    await Promise.all(ids.map(async id => {
      const t0 = now();
      try {
        const b = await adapters[id].fetchBook(symbol, cfg.depth);
        if (now() - t0 <= cfg.maxBookAgeMs) books[id] = b;
      } catch (e) { log(`book ${id} ${symbol}: ${e.message}`); }
    }));
    return books;
  }

  function venueBalances(symbol, ids) {
    const { base, quote } = split(symbol);
    return Object.fromEntries(ids.map(id => [id, { base: executor.balances[id]?.[base] ?? 0, quote: executor.balances[id]?.[quote] ?? 0 }]));
  }

  async function runOnce() {
    tick++;
    if (executor.mode === 'live' && tick % cfg.balanceRefreshTicks === 0) await executor.refreshBalances(cfg.exchanges);
    const results = [];
    for (const symbol of Object.keys(markets)) {
      const books = await snapshot(symbol);
      const ids = Object.keys(books);
      if (ids.length < 2) continue;
      const opp = bestOpportunity({ symbol, books, markets: markets[symbol], fees: cfg.fees, balances: venueBalances(symbol, ids), minEdgeBps: cfg.minEdgeBps, maxNotional: cfg.maxNotional });
      if (!opp) { const top = spreads({ books, fees: cfg.fees })[0]; results.push({ symbol, top }); continue; }
      const gate = risk.allow(opp);
      if (!gate.ok) { results.push({ symbol, opp, skipped: gate.reason }); continue; }
      const res = await executor.execute(opp, { books, ...split(symbol) });
      risk.recordTrade(opp, res);
      log(journal({ type: 'trade', opp, result: res }));
      if (executor.mode === 'live') await executor.refreshBalances([opp.buy, opp.sell]);
      results.push({ symbol, opp, result: res });
      if (risk.state.halted) { log(journal({ type: 'halt', reason: risk.state.haltReason })); break; }
    }
    return results;
  }

  async function run({ signal } = {}) {
    await init();
    log(`${executor.mode} mode · ${Object.keys(markets).join(', ')} · ${cfg.exchanges.join('/')} · min edge ${cfg.minEdgeBps}bps · max ${cfg.maxNotional} per trade`);
    while (!signal?.aborted && !risk.state.halted) {
      const started = now();
      try { await runOnce(); risk.recordSuccess(); }
      catch (e) { log(`tick error: ${e.message}`); risk.recordError(e); }
      await new Promise(r => setTimeout(r, Math.max(0, cfg.intervalMs - (now() - started))));
    }
    if (risk.state.halted) log(`HALTED: ${risk.state.haltReason}`);
  }

  return { init, snapshot, runOnce, run, markets };
}
