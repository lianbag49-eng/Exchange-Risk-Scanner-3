# ERS 1.18 update

## Goal
Make incident investigation easier to read without overstating what ERS can know about an exchange's private review systems.

## UX work
- Put observed evidence, documented exchange/API evidence, user-entered support evidence, and AI-ranked hypotheses in visibly separate sections.
- Add a compact incident timeline that preserves observation timestamps and provenance.
- Show `confirmed`, `supported hypothesis`, `unknown`, and `conflicting evidence` as distinct states rather than one generic risk verdict.
- Put the documented source and last-reviewed date beside each exchange-rule explanation.
- Add a prevention checklist generated only from applicable catalog rules and observed device/account signals.
- Keep screenshot analysis optional and label it as supporting evidence, not an official exchange verdict.

## Safety and privacy constraints
- Never claim access to private exchange risk engines, hidden KYC decisions, or internal restriction reasons.
- Keep API connections read-only and reject trading/withdrawal permissions where the existing connector requires read-only credentials.
- Do not send screenshots, UID, API credentials, names, document numbers, biometrics, or free-form support notes through the bounded cause-analysis endpoint.
- Preserve explicit consent and stale/mismatched-account rejection.

## Acceptance gates
1. Existing Android unit/instrumentation checks pass.
2. APK builds successfully in CI.
3. Cause-catalog/server version compatibility tests pass.
4. UI labels distinguish observation, official/documented evidence, user transcription, and model hypothesis.
5. No regression that turns an unknown reason into a confirmed cause.
