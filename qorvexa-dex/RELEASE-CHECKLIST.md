# BELTRIX development preview 0.3
This is a simulation frontend, NOT a release-ready DEX.
## Implemented
- Clearly marked sample market, pool and APR values
- Wallet optional paper trades with 0.05% model fee, balance validation
- Confirmation and 30-second expiry, configurable minimum receipt
- Browser storage opt-out, CSV export, confirmed reset/delete
- Read-only injected wallet, verified Base Sepolia chain
- No transaction, approval or signature RPC calls
- Browser regression tests and isolated web deployment artifact
## Release blockers
- Select audited protocol/router and supported chain/token allowlist
- Implement actual testnet quotes, allowances, simulations, transactions, receipts and failure/reorg handling
- Independent contract and frontend security review
- Real liquidity/data feeds and stale-data protection
- External wallet/mobile compatibility, real-device iOS and accessibility testing
- Legal/compliance and brand review, privacy/terms, monitoring, incident response
- No mainnet deployment until explicitly authorized after these gates

## 0.3 implemented
- Hyperliquid public mainnet spot/perpetual market metadata and candle chart with volume
- Candle WebSocket subscription, reconnect, stale-data and failure states
- Market data is separate from paper swap execution; no real orders or liquidity added
- Independent security audit is still outstanding

## 0.4 implementation and remaining verification
- Spot/perpetual trade and L2 book subscriptions; UI refresh every 1 second.
- Candle intervals remain 1 minute or greater, per upstream API. No fabricated 1-second candles.
- Testnet-only SDK signing, GTC/ALO orders, cancellation, balances, open orders and positions.
- Mainnet market display cannot submit orders. Price freshness and precision checked before order review.
- Signing requires an injected user wallet; private keys are never collected or persisted.
- ALO is an order-book liquidity provision option, not an AMM pool or funded liquidity guarantee.
- Dependency audit after version upgrades: zero reported vulnerabilities on 2026-09-14.
- Automated tests use mocked API responses and test signatures. They are not proof of live settlement.
- Pending external verification: funded testnet wallet order/partial-fill/cancel cycle; actual liquidity funding; independent security audit; real iPhone wallet compatibility.
- ERS: Android encrypted record persistence, iOS Keychain record storage, worker entry, search, remediation hints and expanded local device checks.
- ERS public IP/ISP/KYC/login history remain unavailable without a verified external data integration. Official logo endpoints may block requests and must not be described as verified offline logo assets.

## English UI simplification

- Trade is the default page; navigation is Trade, Practice, Settings.
- Live chart and testnet ticket share one screen. Depth/trades expand on demand.
- Removed demo overview/pools and the separate Base Sepolia wallet connector.
- Practice balances/history remain available together; all built-in labels and messages use English.
- Existing market/order safeguards are unchanged. CI covers English navigation, mobile width, simulation, stream freshness and mocked testnet signing.
