// Executes both legs at once with IOC limit orders priced at the worst level the sizing used,
// so neither leg can fill at a worse price than the opportunity was computed for.
import { fillAgainst, formatStep } from './book.mjs';

const UNCERTAIN = new Set(['network', 'unsettled']);

function settle(opp, fees, buy, sell) {
  const hedged = Math.min(buy.filledQty, sell.filledQty);
  const part = (leg, q) => (leg.filledQty > 0 ? leg.filledQuote * (q / leg.filledQty) : 0);
  const realizedPnl = part(sell, hedged) * (1 - fees[opp.sell]) - part(buy, hedged) * (1 + fees[opp.buy]);
  const diff = buy.filledQty - sell.filledQty;
  return { buyLeg: buy, sellLeg: sell, hedgedQty: hedged, realizedPnl, unhedgedQty: Math.abs(diff) >= opp.step / 2 ? Number(diff.toFixed(12)) : 0 };
}

/** Paper trading: fills against the snapshot that produced the opportunity and moves simulated balances. */
export function paperExecutor({ balances, fees }) {
  return {
    mode: 'paper',
    balances,
    async execute(opp, { books, base, quote }) {
      const b = fillAgainst(books[opp.buy].asks, opp.qty, opp.buyLimit, 'buy');
      const s = fillAgainst(books[opp.sell].bids, opp.qty, opp.sellLimit, 'sell');
      const A = balances[opp.buy], B = balances[opp.sell];
      A[quote] -= b.notional * (1 + fees[opp.buy]); A[base] = (A[base] ?? 0) + b.filled;
      B[base] -= s.filled; B[quote] = (B[quote] ?? 0) + s.notional * (1 - fees[opp.sell]);
      return settle(opp, fees,
        { orderId: 'paper', status: 'filled', filledQty: b.filled, filledQuote: b.notional },
        { orderId: 'paper', status: 'filled', filledQty: s.filled, filledQuote: s.notional });
    },
    async refreshBalances() { return balances; },
  };
}

/** Live trading. Any leg whose outcome is unknown (timeout, 5xx, never settled) is reported so the engine halts. */
export function liveExecutor({ adapters, fees }) {
  const balances = {};
  const leg = async (ex, side, opp) => {
    const price = side === 'buy' ? formatStep(opp.buyLimit, opp.buyTick) : formatStep(opp.sellLimit, opp.sellTick);
    try {
      return await adapters[ex].placeIoc({ symbol: opp.symbol, side, qty: formatStep(opp.qty, opp.step), price });
    } catch (e) {
      return { orderId: null, status: 'error', filledQty: 0, filledQuote: 0, error: e.message, uncertain: UNCERTAIN.has(e.code) || !e.exchange || e.httpStatus >= 500 };
    }
  };
  return {
    mode: 'live',
    balances,
    async execute(opp) {
      const [buy, sell] = await Promise.all([leg(opp.buy, 'buy', opp), leg(opp.sell, 'sell', opp)]);
      const r = settle(opp, fees, buy, sell);
      if (buy.uncertain || sell.uncertain) r.uncertain = true;
      return r;
    },
    async refreshBalances(ids) {
      await Promise.all(ids.map(async id => { balances[id] = await adapters[id].fetchBalances(); }));
      return balances;
    },
  };
}
