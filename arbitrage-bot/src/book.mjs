// Order book math: step/tick quantization and depth-walking for a cross-exchange spot arbitrage.

const decimals = step => {
  const s = String(step);
  if (s.includes('e-')) return Number(s.split('e-')[1]);
  return s.includes('.') ? s.split('.')[1].replace(/0+$/, '').length : 0;
};

/** Round to an exchange increment. mode: 'floor' | 'ceil'. Returns a Number with no float residue. */
export function quantize(value, step, mode = 'floor') {
  if (!(step > 0)) throw Error('step must be positive');
  const units = value / step;
  const n = mode === 'ceil' ? Math.ceil(units - 1e-9) : Math.floor(units + 1e-9);
  return Number((n * step).toFixed(decimals(step)));
}

/** Format for the exchange API without exponent notation. */
export const formatStep = (value, step) => value.toFixed(decimals(step));

/** Coarser of two increments, so a quantity is valid on both venues. */
export function commonStep(a, b) {
  const [lo, hi] = a < b ? [a, b] : [b, a];
  const r = hi / lo;
  if (Math.abs(r - Math.round(r)) < 1e-9) return hi;
  throw Error(`incompatible lot steps ${a} and ${b}`);
}

/** Validate and normalise a book of [price, qty] levels: asks ascending, bids descending, positive values. */
export function normaliseBook({ bids, asks, ...rest }) {
  const clean = side => side.map(([p, q]) => [Number(p), Number(q)]).filter(([p, q]) => p > 0 && q > 0);
  const b = clean(bids).sort((x, y) => y[0] - x[0]);
  const a = clean(asks).sort((x, y) => x[0] - y[0]);
  if (!b.length || !a.length) throw Error('empty order book');
  if (b[0][0] >= a[0][0]) throw Error('crossed order book');
  return { ...rest, bids: b, asks: a };
}

/**
 * Walk A's asks and B's bids together and take every unit whose marginal edge after both taker fees
 * still clears minEdge. Returns the size, worst prices touched (IOC limits) and expected quote PnL.
 */
export function crossDepth({ asks, bids, buyFee, sellFee, minEdge, maxQty }) {
  let i = 0, j = 0, qty = 0, cost = 0, proceeds = 0, buyLimit = 0, sellLimit = Infinity;
  const ra = asks.map(l => l[1]), rb = bids.map(l => l[1]);
  while (i < asks.length && j < bids.length && qty < maxQty) {
    const ask = asks[i][0], bid = bids[j][0];
    const edge = (bid * (1 - sellFee) - ask * (1 + buyFee)) / (ask * (1 + buyFee));
    if (edge < minEdge) break;
    const take = Math.min(ra[i], rb[j], maxQty - qty);
    qty += take; cost += take * ask; proceeds += take * bid;
    buyLimit = ask; sellLimit = bid;
    ra[i] -= take; rb[j] -= take;
    if (ra[i] <= 1e-15) i++;
    if (rb[j] <= 1e-15) j++;
  }
  return { qty, cost, proceeds, buyLimit, sellLimit };
}

/** Expected fills when buying/selling `qty` against a book side, capped by a limit price (IOC semantics). */
export function fillAgainst(levels, qty, limit, side) {
  let left = qty, filled = 0, notional = 0;
  for (const [p, q] of levels) {
    if (left <= 1e-15) break;
    if (side === 'buy' ? p > limit : p < limit) break;
    const take = Math.min(q, left);
    filled += take; notional += take * p; left -= take;
  }
  return { filled, notional };
}
