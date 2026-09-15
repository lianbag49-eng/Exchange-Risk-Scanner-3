# ERS 1.18 — complete-directory startup review

The home screen now compares launchable apps in the current Android profile
against every entry in ERS's bundled CMC directory (2,448), including inactive
and untracked catalog entries. These statuses are catalog metadata, not fraud
verdicts. All ambiguous name matches remain candidates; no three-exchange limit
is applied. Hidden apps, other profiles and unmatched names can be missed.

Local inspection runs immediately. On first opening, a single disclosure asks
whether to send registered exchange IDs and bounded, pseudonymous observation
codes to ERS/OpenAI. Accepting starts all detected candidates without a UID,
screenshot, case record, reviewer token or API key entry. Declining retains local
inspection. The home screen can enable/disable future AI transmission.

Technical observations are reviewed in batches of 50. Public guidance is then
researched for every distinct matched exchange ID, not just exchanges supported
by account API adapters. The server resolves the site's domain using the fixed
CMC detail endpoint and verifies the returned ID and slug against its directory.
Responses API web search is restricted to that domain and subdomains. Only
bounded topic codes with actual retrieved source URLs are accepted. Public
guidance is not proof of a person's restriction reason, identity or KYC document
authenticity. No other app session or screen is accessed automatically.

The home screen shows separate full-directory, device, AI and public-source
counts. A searchable coverage view includes every registered exchange, with
unrecognized apps marked as unrecognized/account unqueried. Unavailable sources,
server directory mismatches and provider failures remain incomplete, never safe.
One failed batch or exchange does not discard successful work. Foreground
coroutine cancellation stops further requests; retry processes unfinished work.
Results remain in memory for the current app session. Subsequent process starts
inspect again; public server results may be reused with their original timestamp.

## Scoped onboarding API

- `POST /v1/startup/session`: consent plus versioned scope, no identity input;
  issues a random two-hour token held only in app memory.
- `POST /v1/startup/preflight`: the existing bounded signal schema, max 50 apps.
- `POST /v1/startup/exchange`: numeric registered ID only, no caller URL or prompt.

These intentionally public onboarding sessions authenticate only the two startup
routes. Existing reviewer-authenticated image, diagnosis, identity, causes and
connection endpoints retain their authentication. Startup tokens cannot access
them. OpenAI keys remain server-side; account keys and documents are never accepted
by startup routes. There is no anonymous access to private account evidence.

Resource limits per running server process: 100 issued sessions/hour, 200 active
sessions, 2 provider tasks at once, 200 preflight and 100 new public research calls
per UTC day, 2,700 requests/session, 64 KiB request bodies, request replay rejection.
Completed public research is reused for seven days; insufficient-source results
for one hour. Concurrent research for the same exchange is coalesced. Limits and
caches are in memory and reset on process restart; multi-instance deployment
requires a shared quota/cache store. No 2,448 paid searches run on each phone:
all entries are matched locally, only installed candidates receive AI review.
Provider quota/billing failures are shown as failures. Real account APIs still
require each account owner's read-only connection.

## Verification

Server tests cover identical full catalogs, non-adapter exchanges, strict
consent/replay/expiry, privileged endpoint isolation, budgets, canonical domain
resolution, retrieved-source binding and provider request shape. Android tests
exercise fresh consent-to-auto-review with six exchange candidates and no token
settings, searchable full coverage, 121 apps in three batches, all 2,448 dynamic
IDs, partial failure, cancellation and response binding. Fixtures are labeled
mock-only and do not represent private exchange account access.

Primary API documentation: https://developers.openai.com/api/docs/guides/tools-web-search
