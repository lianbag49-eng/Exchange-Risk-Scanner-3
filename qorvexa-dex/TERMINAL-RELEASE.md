# BELTRIX trading terminal

Adds visible order book depth / spread / cumulative size / click-to-price, live mark and oracle price, daily change and volume, open interest, signed indicative hourly funding, payer direction and next-hour countdown. Statistics show unavailable/stale states, not invented zeros. Fast book subscriptions request 5 levels; deep subscriptions request 20. Rendering ticks each second; source update cadence is controlled by Hyperliquid.

Orders: existing testnet-only signing plus GTC, ALO, IOC, price-bounded market IOC, reduce-only position closes and standalone market-trigger TP/SL for an existing position. Trigger orders are size-specific, not auto-resizing attached brackets or OCO. A TP or SL does not automatically cancel the other; reduce-only prevents opening a reverse position. Slippage bounds may prevent execution. Spot has no leverage, funding or TP/SL in this frontend.

Leverage: per-market API maximum, cross / isolated choice, explicit review and signature, current setting re-read before orders. Market orders bind an exact slippage-limited price and use the venue's lot/tick precision. Changing market, account, form, leverage or review expiry invalidates the order. Unknown submissions persist a lock keyed by account and client order ID; no automatic write retry. Reconciliation requires a matching order from the testnet API.

Account tabs: positions with entry/liquidation/margin/unrealized PnL, open-order cancellation, latest 100 returned fills, recent 7-day funding records capped at 100 displayed entries, perpetual and spot balances. Account refresh runs every 15 seconds; account data is scoped to testnet. Funding direction is per contract; signed USDC history is the account's received/paid amount. Countdown assumes the device clock is correct. Current funding is indicative until the hour settles.

Scope: a BELTRIX frontend to Hyperliquid public API, not a copy of the complete service. Mainnet order execution remains disabled. Vaults, staking, mainnet deposits/withdrawals and other venue-only services open on the official venue. TWAP, scale orders, order amendments, subaccount trading, portfolio margin, builder-deployed perp execution and an independent security audit are not implemented here. Actual funded execution remains a separate validation gate.

Sources:
- https://hyperliquid.gitbook.io/hyperliquid-docs/for-developers/api/websocket/subscriptions
- https://hyperliquid.gitbook.io/hyperliquid-docs/for-developers/api/exchange-endpoint
- https://hyperliquid.gitbook.io/hyperliquid-docs/trading/funding
- https://hyperliquid.gitbook.io/hyperliquid-docs/for-developers/api/info-endpoint/perpetuals
