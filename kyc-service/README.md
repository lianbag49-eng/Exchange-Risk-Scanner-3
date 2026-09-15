# ERS AI KYC evidence review

Reviews a user-selected KYC status screenshot. The service extracts visible fields with the OpenAI Responses vision API, then compares the exchange, UID and explicitly shown KYC country with the account entered in ERS. A generic verified badge or VIP level is not proof of KYC. Identity documents are classified as insufficient for this screenshot workflow.

The result is advisory. It never marks a document genuine, confirms a person's identity, checks liveness, queries an exchange's private KYC system, approves an account or changes the existing device risk score. Possible editing cues require human review and are not a forgery verdict.

## Server setup

Use Node 22 or the included Dockerfile behind an HTTPS reverse proxy or an HTTPS container hosting service. GitHub Pages cannot run this service. Configure these secrets in the hosting service's secret manager, never in the APK, source repository or chat:

| Environment variable | Value |
| --- | --- |
| `OPENAI_API_KEY` | A server-only OpenAI project key |
| `OPENAI_MODEL` | An image and structured-output capable model available to the project; the official current examples use `gpt-6-astra` |
| `ERS_REVIEW_TOKENS` | JSON object mapping reviewer IDs to distinct random access tokens, at least 32 characters each |
| `HOST` | Default `127.0.0.1`; Docker sets `0.0.0.0` |
| `PORT` | Default `8080` |

Run `npm start` in this directory after configuring the environment. `GET /healthz` returns readiness. Route `/v1/kyc/reviews` over HTTPS, enforce a 6 MB request cap at the proxy, and disable request/response body logging. Revoke a reviewer by removing their token and restarting the service. Tokens entered in the apps last only for the review dialog session. Rotate tokens if exposed and set provider project spending limits.

This is a single-instance service. Rate limits (10 requests/minute/reviewer, four concurrent reviews) and duplicate-request locks (five minutes) are in memory. A deployment with multiple replicas needs shared rate limiting and duplicate tracking. There is no user administration UI or identity-provider login in this release.

## App flow

1. Add or open an ERS account, then open its scan result.
2. Tap **AI KYC 검토 · 증빙 이미지**.
3. Enter the HTTPS service base URL and the review service access token. Do not enter the OpenAI API key.
4. Select a KYC screenshot after masking unnecessary personal information; inspect the preview and consent to the disclosed transfer.
5. Request a review. Review field matches, uncertainty and the displayed KYC status separately from official approval.
6. Optionally classify the follow-up and save the result. At most ten reviews are retained per saved account record in the existing protected device storage. With account saving disabled, a review remains in the current session only. Deleting the account record deletes its stored reviews.

Images are recompressed to JPEG with a maximum 2048 px dimension and 4 MB transfer size, stripping source metadata. The app and this service do not persist original or resized images. The provider receives the image, so visible personal information in it is disclosed. The entered comparison values stay at the service and are not included in the model prompt. Returned reports contain only constrained codes, matches, timestamps, model and an image hash; extracted UID/name/document numbers are not returned or persisted by the service. Provider response storage is disabled with `store:false`; this does not assert zero provider retention. Confirm the provider's applicable data handling before enabling real document processing.

## Verification

`npm test` runs isolated tests with invented metadata and intercepted provider requests. CI sends no real evidence images to an AI provider and places no financial transactions. A funded API connection and accuracy evaluation on an authorized representative screenshot set remain prerequisites for enabling live AI review. The apps clearly disable requests until a server, access token, image and consent are supplied; there is no fabricated demo approval fallback.

Official references:
- https://developers.openai.com/api/docs/guides/images-vision
- https://developers.openai.com/api/docs/guides/structured-outputs
- https://developers.openai.com/api/docs/guides/your-data
