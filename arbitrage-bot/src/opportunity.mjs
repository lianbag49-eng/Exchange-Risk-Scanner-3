// Find the best executable buy-here/sell-there opportunity from simultaneous book snapshots.
import { quantize, commonStep, crossDepth, fillAgainst } from './book.mjs';

const pairs = ids => ids.flatMap(a => ids.filter(b => b !== a).map(b => [a, b]));

/** Top-of-book spread for every ordered exchange pair, before and after taker fees (bps). */
export function spreads({ books, fees }) {
  return pairs(Object.keys(books)).map(([buy, sell]) => {
    const ask = books[buy].asks[0][0], bid = books[sell].bids[0][0];
    const grossBps = (bid - ask) / ask * 1e4;
    const netBps = (bid * (1 - fees[sell]) - ask * (1 + fees[buy])) / (ask * (1 + fees[buy])) * 1e4;
    return { buy, sell, ask, bid, grossBps, netBps };
  }).sort((x, y) => y.netBps - x.netBps);
}

/**
 * Size one opportunity for a buy/sell pair. Limited by book depth (marginal edge >= minEdgeBps), max notional,
 * quote balance on the buy venue and base inventory on the sell venue. Pre-funded inventory on both sides is
 * required: no on-chain transfer happens inside the loop.
 */
export function sizePair({ symbol, buy, sell, books, markets, fees, balances, minEdgeBps, maxNotional }) {
  const A = books[buy], B = books[sell], mA = markets[buy], mB = markets[sell];
  const buyFee = fees[buy], sellFee = fees[sell], minEdge = minEdgeBps / 1e4;
  const quoteAvail = balances[buy]?.quote ?? 0, baseAvail = balances[sell]?.base ?? 0;
  let step;
  try { step = commonStep(mA.lotStep, mB.lotStep); } catch { return null; }

  let maxQty = Math.min(baseAvail, maxNotional / A.asks[0][0], quoteAvail / (A.asks[0][0] * (1 + buyFee)));
  if (!(maxQty > 0)) return null;
  let cross;
  for (let k = 0; k < 4; k++) {
    cross = crossDepth({ asks: A.asks, bids: B.bids, buyFee, sellFee, minEdge, maxQty });
    const spend = cross.cost * (1 + buyFee), over = Math.max(spend / quoteAvail, cross.cost / maxNotional);
    if (over <= 1 + 1e-12) break;
    maxQty = cross.qty / over * 0.999;
  }
  const qty = quantize(cross.qty, step);
  if (!(qty > 0) || qty < Math.max(mA.minQty, mB.minQty)) return null;

  const buyLimit = quantize(cross.buyLimit, mA.tickSize, 'ceil'), sellLimit = quantize(cross.sellLimit, mB.tickSize, 'floor');
  const b = fillAgainst(A.asks, qty, buyLimit, 'buy'), s = fillAgainst(B.bids, qty, sellLimit, 'sell');
  if (b.filled + 1e-12 < qty || s.filled + 1e-12 < qty) return null;
  if (b.notional < mA.minNotional || s.notional < mB.minNotional) return null;
  if (b.notional * (1 + buyFee) > quoteAvail + 1e-9 || b.notional > maxNotional + 1e-9 || qty > baseAvail + 1e-12) return null;

  const profit = s.notional * (1 - sellFee) - b.notional * (1 + buyFee);
  const edgeBps = profit / (b.notional * (1 + buyFee)) * 1e4;
  if (edgeBps < minEdgeBps) return null;
  return { symbol, buy, sell, qty, step, buyLimit, sellLimit, buyTick: mA.tickSize, sellTick: mB.tickSize, expectedCost: b.notional, expectedProceeds: s.notional, expectedProfit: profit, edgeBps };
}

/** Best opportunity across every ordered pair, or null. */
export function bestOpportunity(ctx) {
  let best = null;
  for (const [buy, sell] of pairs(Object.keys(ctx.books))) {
    const o = sizePair({ ...ctx, buy, sell });
    if (o && (!best || o.expectedProfit > best.expectedProfit)) best = o;
  }
  return best;
}
