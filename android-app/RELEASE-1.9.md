# ERS 1.9 — AI KYC evidence review

Adds an AI KYC review entry point to account scan results on Android and iOS. Select a screenshot, review the image preview and transfer disclosure, connect an HTTPS review service, and compare visible exchange/UID/country/KYC labels. Unreadable evidence, mismatches and visual editing cues are shown as reasons for further review. No biometric matching or official document/approval verification is claimed.

Saved reports include constrained results, follow-up classification, time and image fingerprint, with ten reports per account record. Android uses the existing AES-GCM storage; iOS uses the existing Keychain record store. Original images and access tokens are not saved. AI output does not change the KYC status selected by the user or the device risk score. Older records without AI data remain readable.

The server lives in `kyc-service/` with a Dockerfile, configuration instructions and isolated provider tests. Live AI use requires a separately deployed HTTPS server, an OpenAI server key, a supported model and a per-reviewer service token. No live AI key or production review server is provisioned by this source release. An error never becomes an approval result.

BELTRIX is maintained in its own repository and is unchanged by this release.
