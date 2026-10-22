#!/usr/bin/env node
// arb scan | run [--live] | status | reset   (options: --config file.json --symbols A/B,C/D --exchanges x,y)
import { readFileSync } from 'node:fs';
import { parseArgs } from 'node:util';
import { ADAPTERS, credsFromEnv } from './src/exchanges.mjs';
import { DEFAULTS, validateConfig, createEngine } from './src/engine.mjs';
import { spreads } from './src/opportunity.mjs';
import { RiskManager } from './src/risk.mjs';
import { paperExecutor, liveExecutor } from './src/executor.mjs';

const LIVE_CONFIRM = 'I_ACCEPT_REAL_FUNDS_RISK';

export function loadConfig(values, env = process.env) {
  const file = values.config ? JSON.parse(readFileSync(values.config, 'utf8')) : {};
  const cfg = { ...DEFAULTS, ...file, fees: { ...DEFAULTS.fees, ...file.fees } };
  if (values.symbols) cfg.symbols = values.symbols.split(',');
  if (values.exchanges) cfg.exchanges = values.exchanges.split(',');
  for (const id of cfg.exchanges) if (!ADAPTERS[id]) throw Error(`unsupported exchange ${id} (supported: ${Object.keys(ADAPTERS).join(', ')})`);
  if (values.live && env.ARB_LIVE_CONFIRM !== LIVE_CONFIRM) throw Error(`live mode needs ARB_LIVE_CONFIRM=${LIVE_CONFIRM}`);
  return validateConfig(cfg);
}

async function scan(cfg) {
  const adapters = Object.fromEntries(cfg.exchanges.map(id => [id, ADAPTERS[id]()]));
  for (const symbol of cfg.symbols) {
    const books = {};
    await Promise.all(cfg.exchanges.map(async id => {
      try { books[id] = await adapters[id].fetchBook(symbol, 5); } catch (e) { console.log(`  ${symbol} ${id}: ${e.message}`); }
    }));
    if (Object.keys(books).length < 2) { console.log(`${symbol}: fewer than two books`); continue; }
    console.log(`\n${symbol}   buy@ask → sell@bid        gross bps   net bps (after taker fees)`);
    for (const r of spreads({ books, fees: cfg.fees }))
      console.log(`  ${r.buy.padEnd(8)}→ ${r.sell.padEnd(8)} ${String(r.ask).padStart(12)} ${String(r.bid).padStart(12)} ${r.grossBps.toFixed(2).padStart(9)} ${r.netBps.toFixed(2).padStart(9)}${r.netBps >= cfg.minEdgeBps ? '  ◀ above threshold' : ''}`);
  }
}

async function run(cfg, live) {
  const risk = new RiskManager(cfg, { statePath: cfg.statePath });
  if (risk.state.halted) throw Error(`halted: ${risk.state.haltReason}\nresolve it, then: node cli.mjs reset`);
  let adapters, executor;
  if (live) {
    adapters = {};
    for (const id of cfg.exchanges) {
      const creds = credsFromEnv(id);
      if (!creds) throw Error(`live mode: set ${id.toUpperCase()}_API_KEY and ${id.toUpperCase()}_API_SECRET${id === 'okx' ? ' and OKX_PASSPHRASE' : ''}`);
      adapters[id] = ADAPTERS[id]({ creds });
      const safety = await adapters[id].checkKeySafety();
      if (!safety.ok) throw Error(`${id}: ${safety.reason}`);
    }
    executor = liveExecutor({ adapters, fees: cfg.fees });
  } else {
    adapters = Object.fromEntries(cfg.exchanges.map(id => [id, ADAPTERS[id]()]));
    executor = paperExecutor({ balances: structuredClone(cfg.paperBalances), fees: cfg.fees });
  }
  const engine = createEngine({ cfg, adapters, executor, risk });
  const ac = new AbortController();
  process.once('SIGINT', () => { console.log('\nstopping after current tick…'); ac.abort(); });
  await engine.run({ signal: ac.signal });
  console.log('balances:', JSON.stringify(executor.balances));
}

async function main(argv) {
  const { values, positionals } = parseArgs({
    args: argv, allowPositionals: true,
    options: { config: { type: 'string' }, symbols: { type: 'string' }, exchanges: { type: 'string' }, live: { type: 'boolean', default: false } },
  });
  const cmd = positionals[0] ?? 'scan';
  const cfg = loadConfig(values);
  if (cmd === 'scan') return scan(cfg);
  if (cmd === 'run') return run(cfg, values.live);
  const risk = new RiskManager(cfg, { statePath: cfg.statePath });
  if (cmd === 'status') return console.log(JSON.stringify(risk.state, null, 2));
  if (cmd === 'reset') { risk.reset(); return console.log('halt cleared'); }
  throw Error(`unknown command ${cmd}`);
}

if (import.meta.url === `file://${process.argv[1]}`)
  main(process.argv.slice(2)).catch(e => { console.error(e.message); process.exit(1); });
