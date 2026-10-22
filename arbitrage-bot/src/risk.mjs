// Risk gate. A halt (one-legged fill, loss limit, repeated errors) persists across restarts until reset by hand.
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import { dirname } from 'node:path';

const day = t => new Date(t).toISOString().slice(0, 10);

export class RiskManager {
  constructor({ dailyLossLimit, maxTradesPerDay, cooldownMs, maxConsecutiveErrors }, { statePath, now = Date.now } = {}) {
    Object.assign(this, { dailyLossLimit, maxTradesPerDay, cooldownMs, maxConsecutiveErrors, statePath, now });
    this.state = { halted: false, haltReason: null, day: day(now()), dayPnl: 0, tradesToday: 0, consecutiveErrors: 0, lastTradeAt: {} };
    if (statePath) {
      try { Object.assign(this.state, JSON.parse(readFileSync(statePath, 'utf8'))); } catch (e) { if (e.code !== 'ENOENT') throw e; }
    }
  }

  #save() {
    if (!this.statePath) return;
    mkdirSync(dirname(this.statePath), { recursive: true });
    writeFileSync(this.statePath, JSON.stringify(this.state, null, 1));
  }

  #roll() {
    const d = day(this.now());
    if (d !== this.state.day) Object.assign(this.state, { day: d, dayPnl: 0, tradesToday: 0 });
  }

  allow(opp) {
    this.#roll();
    const s = this.state, key = `${opp.symbol}:${opp.buy}>${opp.sell}`;
    if (s.halted) return { ok: false, reason: `halted: ${s.haltReason}` };
    if (s.dayPnl <= -this.dailyLossLimit) return { ok: false, reason: 'daily loss limit reached' };
    if (s.tradesToday >= this.maxTradesPerDay) return { ok: false, reason: 'daily trade count reached' };
    if (this.now() - (s.lastTradeAt[key] ?? 0) < this.cooldownMs) return { ok: false, reason: 'pair cooldown' };
    return { ok: true };
  }

  recordTrade(opp, result) {
    this.#roll();
    const s = this.state;
    s.tradesToday++; s.dayPnl += result.realizedPnl; s.consecutiveErrors = 0;
    s.lastTradeAt[`${opp.symbol}:${opp.buy}>${opp.sell}`] = this.now();
    if (result.uncertain) this.halt(`order outcome unknown on ${opp.buy}>${opp.sell} ${opp.symbol}; check open orders and balances then run "reset"`);
    else if (result.unhedgedQty) this.halt(`unhedged ${result.unhedgedQty} ${opp.symbol} after ${opp.buy}>${opp.sell}; flatten manually then run "reset"`);
    else if (s.dayPnl <= -this.dailyLossLimit) this.halt(`daily loss ${s.dayPnl.toFixed(4)} reached limit`);
    this.#save();
  }

  recordError(err) {
    if (++this.state.consecutiveErrors >= this.maxConsecutiveErrors) this.halt(`${this.state.consecutiveErrors} consecutive errors, last: ${err.message}`);
    this.#save();
  }

  recordSuccess() { if (this.state.consecutiveErrors) { this.state.consecutiveErrors = 0; this.#save(); } }

  halt(reason) { Object.assign(this.state, { halted: true, haltReason: reason }); this.#save(); }

  reset() { Object.assign(this.state, { halted: false, haltReason: null, consecutiveErrors: 0 }); this.#save(); }
}
