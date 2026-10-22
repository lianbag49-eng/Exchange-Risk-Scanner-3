// Exchange adapters: public books/markets for scanning, signed spot IOC orders and balances for live mode.
// Only the fixed HTTPS hosts below are contacted. Keys never leave the signing headers.
import { createHmac } from 'node:crypto';
import { normaliseBook } from './book.mjs';

const TERMINAL_WAIT_MS = [150, 300, 600, 1200, 2000];

export class ExchangeError extends Error {
  constructor(exchange, message, code, httpStatus) { super(`${exchange}: ${message}`); Object.assign(this, { exchange, code, httpStatus }); }
}

const split = symbol => {
  const m = /^([A-Z0-9]{2,15})\/([A-Z0-9]{2,15})$/.exec(symbol);
  if (!m) throw Error(`symbol must look like BTC/USDT, got ${symbol}`);
  return { base: m[1], quote: m[2] };
};

function makeHttp(id, host, { fetchFn = fetch, timeoutMs = 5000 } = {}) {
  return async (path, { method = 'GET', headers = {}, body } = {}) => {
    let res;
    try {
      res = await fetchFn(host + path, { method, headers, body, redirect: 'error', signal: AbortSignal.timeout(timeoutMs) });
    } catch (e) {
      throw new ExchangeError(id, `network ${e.name === 'TimeoutError' ? 'timeout' : 'failure'}`, 'network');
    }
    let json;
    try { json = await res.json(); } catch { throw new ExchangeError(id, `HTTP ${res.status} non-JSON response`, res.status, res.status); }
    if (!res.ok) throw new ExchangeError(id, `HTTP ${res.status} ${json?.code ?? json?.retCode ?? ''} ${String(json?.msg ?? json?.retMsg ?? '').slice(0, 160)}`.trim(), json?.code ?? res.status, res.status);
    return json;
  };
}

const hmacHex = (secret, s) => createHmac('sha256', secret).update(s).digest('hex');
const need = (creds, id, ...keys) => {
  for (const k of keys) if (!creds?.[k]) throw new ExchangeError(id, `missing credential ${k}`, 'credentials');
  return creds;
};
const sleep = ms => new Promise(r => setTimeout(r, ms));

// ---------------------------------------------------------------- Binance (spot)
export function binance({ creds, now = Date.now, ...opts } = {}) {
  const id = 'binance', http = makeHttp(id, 'https://api.binance.com', opts);
  const native = s => { const { base, quote } = split(s); return base + quote; };
  const signed = (method, path, params = {}) => {
    const { apiKey, apiSecret } = need(creds, id, 'apiKey', 'apiSecret');
    const q = new URLSearchParams({ ...params, recvWindow: '5000', timestamp: String(now()) }).toString();
    return http(`${path}?${q}&signature=${hmacHex(apiSecret, q)}`, { method, headers: { 'X-MBX-APIKEY': apiKey } });
  };
  return {
    id,
    async fetchBook(symbol, depth = 20) {
      const r = await http(`/api/v3/depth?symbol=${native(symbol)}&limit=${depth}`);
      return normaliseBook({ bids: r.bids, asks: r.asks });
    },
    async fetchMarket(symbol) {
      const s = (await http(`/api/v3/exchangeInfo?symbol=${native(symbol)}`)).symbols?.[0];
      if (!s || s.status !== 'TRADING') throw new ExchangeError(id, `${symbol} not trading`, 'market');
      const f = t => s.filters.find(x => x.filterType === t) ?? {};
      return {
        lotStep: Number(f('LOT_SIZE').stepSize), minQty: Number(f('LOT_SIZE').minQty), tickSize: Number(f('PRICE_FILTER').tickSize),
        minNotional: Number(f('NOTIONAL').minNotional ?? f('MIN_NOTIONAL').minNotional ?? 0),
      };
    },
    async checkKeySafety() {
      const r = await signed('GET', '/sapi/v1/account/apiRestrictions');
      if (r.enableWithdrawals !== false) return { ok: false, reason: 'API key allows withdrawals; create a trade-only key' };
      if (r.enableSpotAndMarginTrading !== true) return { ok: false, reason: 'API key cannot trade spot' };
      return { ok: true };
    },
    async fetchBalances() {
      const r = await signed('GET', '/api/v3/account', { omitZeroBalances: 'true' });
      return Object.fromEntries((r.balances ?? []).map(b => [b.asset, Number(b.free)]));
    },
    async placeIoc({ symbol, side, qty, price }) {
      const r = await signed('POST', '/api/v3/order', {
        symbol: native(symbol), side: side.toUpperCase(), type: 'LIMIT', timeInForce: 'IOC',
        quantity: qty, price, newOrderRespType: 'RESULT',
      });
      return { orderId: String(r.orderId), status: r.status, filledQty: Number(r.executedQty), filledQuote: Number(r.cummulativeQuoteQty) };
    },
  };
}

// ---------------------------------------------------------------- Bybit (v5 spot, unified account)
export function bybit({ creds, now = Date.now, wait = sleep, ...opts } = {}) {
  const id = 'bybit', http = makeHttp(id, 'https://api.bybit.com', opts);
  const native = s => { const { base, quote } = split(s); return base + quote; };
  const ok = r => { if (r.retCode !== 0) throw new ExchangeError(id, `retCode ${r.retCode} ${String(r.retMsg ?? '').slice(0, 160)}`, r.retCode); return r.result; };
  const signed = async (method, path, params = {}) => {
    const { apiKey, apiSecret } = need(creds, id, 'apiKey', 'apiSecret');
    const ts = String(now()), recv = '5000';
    const payload = method === 'GET' ? new URLSearchParams(params).toString() : JSON.stringify(params);
    const headers = {
      'X-BAPI-API-KEY': apiKey, 'X-BAPI-TIMESTAMP': ts, 'X-BAPI-RECV-WINDOW': recv,
      'X-BAPI-SIGN': hmacHex(apiSecret, ts + apiKey + recv + payload), 'Content-Type': 'application/json',
    };
    return ok(await (method === 'GET'
      ? http(payload ? `${path}?${payload}` : path, { headers })
      : http(path, { method, headers, body: payload })));
  };
  return {
    id,
    async fetchBook(symbol, depth = 20) {
      const r = ok(await http(`/v5/market/orderbook?category=spot&symbol=${native(symbol)}&limit=${depth}`));
      return normaliseBook({ bids: r.b, asks: r.a, ts: r.ts });
    },
    async fetchMarket(symbol) {
      const s = ok(await http(`/v5/market/instruments-info?category=spot&symbol=${native(symbol)}`)).list?.[0];
      if (!s || s.status !== 'Trading') throw new ExchangeError(id, `${symbol} not trading`, 'market');
      return {
        lotStep: Number(s.lotSizeFilter.basePrecision), minQty: Number(s.lotSizeFilter.minOrderQty),
        tickSize: Number(s.priceFilter.tickSize), minNotional: Number(s.lotSizeFilter.minOrderAmt ?? 0),
      };
    },
    async checkKeySafety() {
      const r = await signed('GET', '/v5/user/query-api');
      if ((r.permissions?.Wallet ?? []).includes('Withdraw')) return { ok: false, reason: 'API key allows withdrawals; create a trade-only key' };
      if (Number(r.readOnly) !== 0 || !(r.permissions?.Spot ?? []).includes('SpotTrade')) return { ok: false, reason: 'API key cannot trade spot' };
      return { ok: true };
    },
    async fetchBalances() {
      const r = await signed('GET', '/v5/account/wallet-balance', { accountType: 'UNIFIED' });
      return Object.fromEntries((r.list?.[0]?.coin ?? []).map(c => [c.coin, Number(c.walletBalance) - Number(c.locked || 0)]));
    },
    async placeIoc({ symbol, side, qty, price }) {
      const { orderId } = await signed('POST', '/v5/order/create', {
        category: 'spot', symbol: native(symbol), side: side === 'buy' ? 'Buy' : 'Sell',
        orderType: 'Limit', qty, price, timeInForce: 'IOC',
      });
      // IOC orders settle almost immediately; poll until a terminal status (closed orders stay queryable by orderId).
      for (const ms of TERMINAL_WAIT_MS) {
        await wait(ms);
        const o = (await signed('GET', '/v5/order/realtime', { category: 'spot', orderId })).list?.[0];
        if (o && ['Filled', 'Cancelled', 'PartiallyFilledCanceled', 'Rejected'].includes(o.orderStatus))
          return { orderId, status: o.orderStatus, filledQty: Number(o.cumExecQty), filledQuote: Number(o.cumExecValue) };
      }
      throw new ExchangeError(id, `order ${orderId} did not reach a terminal state`, 'unsettled');
    },
  };
}

// ---------------------------------------------------------------- OKX (v5 spot, cash mode)
export function okx({ creds, now = Date.now, wait = sleep, ...opts } = {}) {
  const id = 'okx', http = makeHttp(id, 'https://www.okx.com', opts);
  const native = s => { const { base, quote } = split(s); return `${base}-${quote}`; };
  const ok = r => { if (r.code !== '0') throw new ExchangeError(id, `code ${r.code} ${String(r.data?.[0]?.sMsg || r.msg || '').slice(0, 160)}`, r.code); return r.data; };
  const signed = async (method, path, params = {}) => {
    const { apiKey, apiSecret, passphrase } = need(creds, id, 'apiKey', 'apiSecret', 'passphrase');
    const ts = new Date(now()).toISOString();
    const q = method === 'GET' && Object.keys(params).length ? `?${new URLSearchParams(params)}` : '';
    const body = method === 'GET' ? '' : JSON.stringify(params);
    const sign = createHmac('sha256', apiSecret).update(ts + method + path + q + body).digest('base64');
    const headers = { 'OK-ACCESS-KEY': apiKey, 'OK-ACCESS-SIGN': sign, 'OK-ACCESS-TIMESTAMP': ts, 'OK-ACCESS-PASSPHRASE': passphrase, 'Content-Type': 'application/json' };
    return ok(await http(path + q, { method, headers, body: body || undefined }));
  };
  return {
    id,
    async fetchBook(symbol, depth = 20) {
      const r = ok(await http(`/api/v5/market/books?instId=${native(symbol)}&sz=${depth}`))[0];
      return normaliseBook({ bids: r.bids, asks: r.asks, ts: Number(r.ts) });
    },
    async fetchMarket(symbol) {
      const s = ok(await http(`/api/v5/public/instruments?instType=SPOT&instId=${native(symbol)}`))[0];
      if (!s || s.state !== 'live') throw new ExchangeError(id, `${symbol} not trading`, 'market');
      return { lotStep: Number(s.lotSz), minQty: Number(s.minSz), tickSize: Number(s.tickSz), minNotional: 0 };
    },
    async checkKeySafety() {
      const perm = String((await signed('GET', '/api/v5/account/config'))[0]?.perm ?? '').split(',');
      if (perm.includes('withdraw')) return { ok: false, reason: 'API key allows withdrawals; create a trade-only key' };
      if (!perm.includes('trade')) return { ok: false, reason: 'API key cannot trade' };
      return { ok: true };
    },
    async fetchBalances() {
      const d = (await signed('GET', '/api/v5/account/balance'))[0];
      return Object.fromEntries((d?.details ?? []).map(c => [c.ccy, Number(c.availBal)]));
    },
    async placeIoc({ symbol, side, qty, price }) {
      const instId = native(symbol);
      const [{ ordId }] = await signed('POST', '/api/v5/trade/order', { instId, tdMode: 'cash', side, ordType: 'ioc', px: price, sz: qty });
      for (const ms of TERMINAL_WAIT_MS) {
        await wait(ms);
        const o = (await signed('GET', '/api/v5/trade/order', { instId, ordId }))[0];
        if (o && ['filled', 'canceled', 'mmp_canceled'].includes(o.state)) {
          const filledQty = Number(o.accFillSz);
          return { orderId: ordId, status: o.state, filledQty, filledQuote: filledQty * Number(o.avgPx || 0) };
        }
      }
      throw new ExchangeError(id, `order ${ordId} did not reach a terminal state`, 'unsettled');
    },
  };
}

export const ADAPTERS = { binance, bybit, okx };

/** Credentials come only from the environment; nothing is read from or written to config files. */
export function credsFromEnv(id, env = process.env) {
  const p = id.toUpperCase();
  const c = { apiKey: env[`${p}_API_KEY`], apiSecret: env[`${p}_API_SECRET`], passphrase: env[`${p}_PASSPHRASE`] };
  return c.apiKey && c.apiSecret ? c : undefined;
}
