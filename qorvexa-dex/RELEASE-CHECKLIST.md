# BELTRIX 0.4 — Hyperliquid testnet terminal

Implemented: Hyperliquid spot and perpetual metadata, candles, volume, bid/ask depth, mainnet read-only view, testnet wallet account data, signed limit/IOC/post-only orders, reduce-only checks, leverage updates, own-market cancellation, fills, stale-data guards, quote expiry, explicit confirmation and ambiguous-submit reconciliation. Existing paper swap remains separate. No deposit, token approval, withdrawal or mainnet order UI.

## Validation and limits
- Browser tests use deterministic market/account fixtures and a simulated EIP-1193 provider. They are not proof of real-wallet fills.
- Real testnet market metadata was fetched during implementation. A funded user wallet is required to validate onboarding, signature UX, actual fills and cancellation on each supported wallet.
- Injected wallets are supported. On iOS use a compatible wallet's browser; ordinary Safari provides data viewing and paper simulation. WalletConnect is not implemented.
- Liquidity means Hyperliquid order-book depth and post-only orders, not a new liquidity pool. No new contracts, LP deposit vault or AMM has been deployed.
- No independent security audit has been performed. Frontend review and automated guards are development validation only.
- Release gates: end-to-end funded testnet exercises, independent audit, real-device wallet/accessibility review, monitoring/incident response and product/legal review. Mainnet trading requires separate authorization and these gates.

## Data and dependencies
Market data: https://hyperliquid.gitbook.io/hyperliquid-docs/for-developers/api
Order schema: https://hyperliquid.gitbook.io/hyperliquid-docs/for-developers/api/exchange-endpoint
SDK: @nktkas/hyperliquid 0.33.3 (community SDK, MIT). Browser wallet: viem. Chart rendering: TradingView Lightweight Charts (Apache-2.0), with attribution and bundled notices.
