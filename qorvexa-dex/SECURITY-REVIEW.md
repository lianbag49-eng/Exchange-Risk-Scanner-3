# BELTRIX 0.4 development security review

This is an internal implementation review, not an independent security audit.

- Order transport is pinned to the Hyperliquid testnet endpoint and permits only order, cancel and updateLeverage actions. Mainnet market data is read-only.
- Explicit in-app review and wallet signature are required. Wallet address, network, review expiry and market selection are checked before signing and again before sending.
- Orders use exact reviewed price/size, protocol precision, minimum notional, a client order ID and a signed expiresAfter deadline. Market orders use IOC with a bounded price; no automatic mutation retries.
- A transport failure after send retains the client order ID in tab storage. New orders remain blocked until an order-status response resolves it, or the signed expiry has passed and the server confirms unknownOid.
- Spot orders check unheld balances. Perps check a conservative withdrawable-margin estimate; reduce-only checks position direction and size. The server remains authoritative for fills, fees and margin.
- Cancellation resolves the order's own market index, independent of the chart selection.
- Market and wallet text use textContent, DOM nodes and numeric validation. User API keys, seed phrases and private keys are not collected. No deposits, withdrawals or token approvals are implemented.
- Dependencies are bundled from package-lock.json; runtime script CDNs are not used. Upstream licenses and TradingView attribution are included.
- Market/account responses are never cached by the service worker. Paper storage can be disabled; pending-submission metadata is kept only in the tab's session storage.

Validation: Android unit/build checks and browser fixture tests run in GitHub Actions before main publication. Browser fixture tests exercise review payloads, testnet signatures, spot asset IDs, rejected signatures, stale data, wrong-chain/mainnet blocks, account changes, ambiguous responses, expiry, order cancellation, stream updates, metadata failure, paper opt-out and mobile layout.

Open validation: funded real-wallet testnet fills, real iOS/Android wallet testing, independent frontend/security review, ongoing dependency monitoring and operational incident response. No assurance of production readiness is made.
