#!/usr/bin/env node
// arb scan | fees | record --out f.jsonl --hours 6 | backtest --data f.jsonl [--latency 300] | run [--live] | status | reset
// common options: --config file.json --symbols A/B,C/D --exchanges x,y
import { readFileSync, writeFileSync } from 'node:fs';
import { parseArgs } from 'node:util';
import { ADAPTERS, credsFromEnv } from './src/exchanges.mjs';
import { DEFAULTS, validateConfig, createEngine, resolveFees } from './src/engine.mjs';
import { spreads } from './src/opportunity.mjs';
import { RiskManager } from './src/risk.mjs';
import { paperExecutor, liveExecutor } from './src/executor.mjs';
import { record } from './src/recorder.mjs';
import { backtest, readRecording, formatReport } from './src/backtest.mjs';

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

function liveAdapters(cfg) {
  const adapters = {};
  for (const id of cfg.exchanges) {
    const creds = credsFromEnv(id);
    if (!creds) throw Error(`set ${id.toUpperCase()}_API_KEY and ${id.toUpperCase()}_API_SECRET${id === 'okx' ? ' and OKX_PASSPHRASE' : ''}`);
    adapters[id] = ADAPTERS[id]({ creds });
  }
  return adapters;
}

const pct = f => `${(f * 100).toFixed(4)}%`;
const feeTable = rows => rows.map(r => `  ${r.exchange.padEnd(8)} 설정 ${pct(r.configured).padStart(9)}  계정 실제 ${pct(r.actual).padStart(9)}  사용 ${pct(r.used).padStart(9)}${r.actual > r.configured ? '  ◀ 설정이 실제보다 낮아 실제값 사용' : ''}`).join('\n');

async function feesCmd(cfg) {
  const { fees, rows } = await resolveFees({ cfg, adapters: liveAdapters(cfg) });
  console.log(`계정 테이커 수수료 (${cfg.symbols.join(', ')} 중 최고값)\n${feeTable(rows)}`);
  console.log(`\n백테스트 config.json에 넣을 값:\n  "fees": ${JSON.stringify(Object.fromEntries(rows.map(r => [r.exchange, r.actual])))}`);
  return fees;
}

async function run(cfg, live) {
  const risk = new RiskManager(cfg, { statePath: cfg.statePath });
  if (risk.state.halted) throw Error(`halted: ${risk.state.haltReason}\nresolve it, then: node cli.mjs reset`);
  let adapters, executor;
  if (live) {
    adapters = liveAdapters(cfg);
    for (const id of cfg.exchanges) {
      const safety = await adapters[id].checkKeySafety();
      if (!safety.ok) throw Error(`${id}: ${safety.reason}`);
    }
    const { fees, rows } = await resolveFees({ cfg, adapters });
    console.log(`taker fees\n${feeTable(rows)}`);
    cfg = { ...cfg, fees };
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

async function recordCmd(cfg, values) {
  const hours = Number(values.hours ?? 1);
  if (!(hours > 0)) throw Error('--hours must be positive');
  const out = values.out ?? `data/books-${new Date().toISOString().slice(0, 19).replace(/[:T]/g, '-')}.jsonl`;
  const adapters = Object.fromEntries(cfg.exchanges.map(id => [id, ADAPTERS[id]()]));
  const ac = new AbortController();
  process.once('SIGINT', () => ac.abort());
  console.log(`recording ${cfg.symbols.join(', ')} on ${cfg.exchanges.join('/')} every ${cfg.intervalMs}ms for ${hours}h → ${out}`);
  console.log(JSON.stringify(await record({ cfg, adapters, out, durationMs: hours * 3.6e6, signal: ac.signal })));
}

async function backtestCmd(cfg, values) {
  if (!values.data) throw Error('backtest needs --data <recording.jsonl> (make one with: node cli.mjs record)');
  const latencyMs = Number(values.latency ?? 300);
  if (!(latencyMs >= 0)) throw Error('--latency must be >= 0');
  const { summary, trades } = await backtest(readRecording(values.data), cfg, { latencyMs });
  console.log(formatReport(summary));
  if (values.trades) { writeFileSync(values.trades, trades.map(t => JSON.stringify(t)).join('\n') + '\n'); console.log(`trades → ${values.trades}`); }
}

async function main(argv) {
  const { values, positionals } = parseArgs({
    args: argv, allowPositionals: true,
    options: {
      config: { type: 'string' }, symbols: { type: 'string' }, exchanges: { type: 'string' }, live: { type: 'boolean', default: false },
      out: { type: 'string' }, hours: { type: 'string' }, data: { type: 'string' }, latency: { type: 'string' }, trades: { type: 'string' },
    },
  });
  const cmd = positionals[0] ?? 'scan';
  const cfg = loadConfig(values);
  if (cmd === 'scan') return scan(cfg);
  if (cmd === 'run') return run(cfg, values.live);
  if (cmd === 'record') return recordCmd(cfg, values);
  if (cmd === 'fees') return feesCmd(cfg);
  if (cmd === 'backtest') return backtestCmd(cfg, values);
  const risk = new RiskManager(cfg, { statePath: cfg.statePath });
  if (cmd === 'status') return console.log(JSON.stringify(risk.state, null, 2));
  if (cmd === 'reset') { risk.reset(); return console.log('halt cleared'); }
  throw Error(`unknown command ${cmd}`);
}

if (import.meta.url === `file://${process.argv[1]}`)
  main(process.argv.slice(2)).catch(e => { console.error(e.message); process.exit(1); });
