# QORVEXA pre-launch preview 0.2
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
