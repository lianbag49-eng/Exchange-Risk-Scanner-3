# ERS 1.8

- CMC spot top 50 as retrieved 2026-09-14, plus existing Tapbit and BitMart support. Rankings are a dated catalog, not account-safety scores.
- 52 exchange PNG brand logos embedded in assets/exchanges.json; custom entries use initials. The source URL for each logo is preserved in the catalog. Source: https://coinmarketcap.com/rankings/exchanges/ and CMC exchange image CDN.
- Reference-inspired black/gold home, exchange search, account form and full-screen report. Risk explanations deliberately distinguish measured, user-reported and unknown information.
- Optional KYC/restriction/login/2FA input and local notice keyword matching. The notice is not authenticated, ambiguous/negated language can produce a caution, and absence of a keyword is not proof of safety. No exchange login credentials or KYC documents are requested. Raw notice text is not persisted.
- Preserves encrypted storage, unreadable-storage lock, privacy screen mode and device-settings shortcuts from the preceding update.
- iOS uses the same offline catalog and evidence labels; distribution signing remains outside the simulator build.
