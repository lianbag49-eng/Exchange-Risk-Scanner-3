// Optional Telegram alerts for trades, halts and start/stop. A failed alert never affects trading: errors are
// logged without the bot token and the message is dropped.

const API = 'https://api.telegram.org';

export function telegramNotifier({ token, chatId, fetchFn = fetch, minIntervalMs = 1100, log = console.log, wait = ms => new Promise(r => setTimeout(r, ms)) }) {
  let chain = Promise.resolve(), last = 0;
  const post = async text => {
    const gap = last + minIntervalMs - Date.now();
    if (gap > 0) await wait(gap);
    last = Date.now();
    try {
      const res = await fetchFn(`${API}/bot${token}/sendMessage`, {
        method: 'POST', headers: { 'Content-Type': 'application/json' }, redirect: 'error', signal: AbortSignal.timeout(5000),
        body: JSON.stringify({ chat_id: chatId, text: text.slice(0, 4000), disable_web_page_preview: true }),
      });
      if (!res.ok) log(`telegram: HTTP ${res.status}`);
    } catch (e) { log(`telegram: ${e.name === 'TimeoutError' ? 'timeout' : 'send failed'}`); }
  };
  // Serialise sends so bursts respect Telegram's per-chat rate limit and keep their order.
  const send = text => (chain = chain.then(() => post(text)));
  return { send, flush: () => chain };
}

export const silentNotifier = { send: async () => {}, flush: async () => {} };

export function notifierFromEnv(env = process.env, opts = {}) {
  const token = env.TELEGRAM_BOT_TOKEN, chatId = env.TELEGRAM_CHAT_ID;
  if (!token && !chatId) return silentNotifier;
  if (!token || !chatId) throw Error('set both TELEGRAM_BOT_TOKEN and TELEGRAM_CHAT_ID, or neither');
  if (!/^\d+:[A-Za-z0-9_-]{20,}$/.test(token)) throw Error('TELEGRAM_BOT_TOKEN does not look like a bot token');
  return telegramNotifier({ token, chatId, ...opts });
}

const n = (x, d = 4) => Number(x).toFixed(d);

export function tradeMessage(mode, opp, r) {
  const tag = mode === 'live' ? '실거래' : '모의';
  const head = r.uncertain ? '⚠️ 주문 결과 불명' : r.unhedgedQty ? '⚠️ 한쪽만 체결' : '✅ 체결';
  return [
    `[${tag}] ${head} ${opp.symbol}`,
    `${opp.buy} 매수 → ${opp.sell} 매도 · 수량 ${opp.qty}`,
    `예상 ${n(opp.expectedProfit)} → 실현 ${n(r.realizedPnl)} (${n(opp.edgeBps, 1)}bp)`,
    r.unhedgedQty ? `미헤지 수량 ${r.unhedgedQty}` : null,
  ].filter(Boolean).join('\n');
}
