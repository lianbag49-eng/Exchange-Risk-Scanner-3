# BELTRIX wallet workspace

This adds an English wallet home inspired by the supplied layout while keeping the existing trading screen. BELTRIX is independent of OKX. It connects an existing EIP-1193 wallet; it does not create or custody private keys.

## Implemented

- Account selection, public watch-only accounts, network switching, native balances, indexed ERC-20 balances and manual token import.
- Wallet-approved native and ERC-20 sends with exact integer arithmetic, current balance and contract simulation checks, buffered execution-gas review, explicit recipient acknowledgement, bound account/network/nonce and review expiry.
- Chain-specific receive QR, address copy and native sharing.
- Native, ERC-20 and internal-transfer history, send/receive/pending/failed filters, text/date search, earlier-page loading, details, explorer links and CSV export of loaded records.
- Local submission notifications, receipt checks, ambiguous-submission lock and explicit reconciliation. No automatic write retry.
- ERC-20 allowance lookup, a recent 2,000-block approval scan and wallet-approved zero-allowance revocation.
- Public address book, privacy settings, asset/address/TXID/DApp search, DApp directory and external-domain confirmation.
- Hyperliquid equity-linked perpetual market discovery, account balances, positions, recent fills and recent deposit/withdrawal ledger.
- DeFiLlama market-rate discovery and official lending/staking/bridge application links. The existing Hyperliquid order-entry screen stays testnet-only.

## Product boundaries

- Supported signing networks: Ethereum, Arbitrum, Base, Optimism, BNB Chain, Polygon, Sepolia and Arbitrum Sepolia. Native Bitcoin and Solana transfers are not implemented. A familiar token symbol does not authenticate its contract.
- This is an EVM wallet frontend, not the complete OKX Wallet ecosystem. It does not implement native stock custody/trading, fiat onramps, seed-phrase custody, OKX campaigns, a proprietary bridge or a universal DeFi position indexer.
- Stock-tab products are equity-linked derivatives returned by Hyperliquid builder-market metadata. They are not stocks. They open at the venue for trading.
- DeFi, swap and bridge execution opens official external applications. Displayed APY is provider-reported and variable, not a promised return.
- Portfolio USD value covers priced assets on the selected network. Testnet balances are never assigned a real dollar value. Unpriced and unavailable assets remain explicitly marked.
- Public index availability and coverage differ by chain. Ethereum history uses the Routescan compatible endpoint; other configured explorers are queried where supported. Index failure is an incomplete result, never proof of zero activity. Token discovery can be partial. RPC reads and manual import remain available when an index fails.
- Blockscout instance access may be restricted or require a service plan. No API keys are embedded. Addresses used in public data requests are shared with the respective provider.
- The approval scan covers only the most recent 2,000 blocks and up to 40 discovered token/spender pairs. It is not an all-history approval audit and excludes NFT operators; exact token/spender checks and an external approval explorer are available.
- Hyperliquid account sections show recent API responses, capped at 100 displayed records per section. Blockchain CSV exports contain the currently loaded, filtered rows, not an implied full tax statement.
- Gas review contains an execution buffer. L2 data fees can be additional; the signing wallet displays the final fees. Submitted transactions can fail or be replaced.
- A confirmation is a mined receipt, not a guarantee against later chain reorganization. Real funded transactions, hardware-wallet compatibility and an independent security audit remain release gates.

## Verification

- Unit coverage: exact amount parsing, bad addresses, contract calldata, review binding/expiry, status normalization, deduplication and CSV formula escaping.
- Browser fixtures: provider connection, receive QR, native/ERC-20 sends, token simulation rejection, insufficient gas, account/network/nonce changes, review expiry, ambiguous responses, explicit wallet rejection, history pagination/provider failure, allowance revocation, watch-only restrictions, navigation and data views.
- Browser fixtures use invented addresses and funds. They do not prove settlement on a live chain.

## Primary references

- [EIP-6963 provider discovery](https://eips.ethereum.org/EIPS/eip-6963)
- [ERC-20 token standard](https://eips.ethereum.org/EIPS/eip-20)
- [EIP-681 payment request format](https://eips.ethereum.org/EIPS/eip-681)
- [Blockscout REST and cursor pagination](https://docs.blockscout.com/devs/apis/rest)
- [Blockscout address transaction schema](https://docs.blockscout.com/api-reference/get-address-transactions)
- [Hyperliquid perpetual and builder-market API](https://hyperliquid.gitbook.io/hyperliquid-docs/for-developers/api/info-endpoint/perpetuals)
- [DefiLlama API](https://api-docs.defillama.com/)
- viem 2.56.5 source bundled through the pinned npm lockfile.
