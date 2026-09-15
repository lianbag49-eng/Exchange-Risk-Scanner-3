# ERS AI KYC and exchange app diagnostics

Reviews a user-selected KYC status screenshot. The service extracts visible fields with the OpenAI Responses vision API, then compares the exchange, UID and explicitly shown KYC country with the account entered in ERS. A generic verified badge or VIP level is not proof of KYC. Identity documents are classified as insufficient for this screenshot workflow.

The result is advisory. It never marks a document genuine, confirms a person's identity, checks liveness, queries an exchange's private KYC system, approves an account or changes the existing device risk score. Possible editing cues require human review and are not a forgery verdict.

## Server setup

Use Node 22 or the included Dockerfile behind an HTTPS reverse proxy or an HTTPS container hosting service. GitHub Pages cannot run this service. Configure these secrets in the hosting service's secret manager, never in the APK, source repository or chat:

| Environment variable | Value |
| --- | --- |
| `OPENAI_API_KEY` | A server-only OpenAI project key |
| `OPENAI_MODEL` | An image and structured-output capable model available to the project; the official current examples use `gpt-6-astra` |
| `ERS_REVIEW_TOKENS` | JSON object mapping reviewer IDs to distinct random access tokens, at least 32 characters each |
| `ERS_REVIEW_TOKEN` | Optional single-reviewer token alternative; ignored when `ERS_REVIEW_TOKENS` is set |
| `HOST` | Default `127.0.0.1`; Docker sets `0.0.0.0` |
| `PORT` | Default `8080` |

Run `npm start` in this directory after configuring the environment. `GET /healthz` returns readiness. Route `/v1/kyc/reviews` over HTTPS, enforce a 6 MB request cap at the proxy, and disable request/response body logging. Revoke a reviewer by removing their token and restarting the service. Tokens entered in the apps last only for the review dialog session. Rotate tokens if exposed and set provider project spending limits.

This is a single-instance service. Rate limits (10 requests/minute/reviewer, four concurrent reviews) and duplicate-request locks (five minutes) are in memory. A deployment with multiple replicas needs shared rate limiting and duplicate tracking. There is no user administration UI or identity-provider login in this release.

## Render deployment and external AI connection

The root `render.yaml` is a Docker Blueprint for one **free** service in Singapore with manual deployment and `/healthz`. Import this repository in Render, select the Blueprint, and supply `OPENAI_API_KEY` and an available vision/structured-output `OPENAI_MODEL` through Render's environment settings. The Blueprint generates `ERS_REVIEW_TOKEN`; keep it private and enter it in ERS along with the assigned HTTPS service URL. Do not commit a key or token. If a free service is unavailable, choose a hosting arrangement explicitly before creating a paid service. Cold starts may require retrying the connection check after the service wakes up.

The Android **AI 서버 연결 확인** button calls authenticated `POST /v1/connection-check` with `{}`. The service checks OpenAI model access with `GET /v1/models/{model}`. Success means API authentication/model access; it does **not** confirm inference quota or accuracy. Complete a separate image review with an invented, non-sensitive error fixture before handling real evidence. `/healthz` alone only checks that the ERS service is running. Both diagnostics and existing KYC use the same configured server and access token.

## Android 1.10: exchange app diagnosis

Open an account result and tap **거래소 앱 AI 진단 · 오류 화면**. Select the installed exchange app by label **and exact package name**; the label is not proof of an official publisher. ERS queries launcher apps only and keeps the full list on the device. The user opens the app; ERS never operates login, trading, withdrawal or private exchange APIs.

Prepare a screenshot through the OS image picker or the optional one-shot screen share. Every screen-share session requires Android consent; Android 14+ offers single-app sharing. A foreground notification presents an explicit **현재 화면 1회 캡처** action and a stop action. No frame is requested until that capture action. The session ends after one frame, a five-second capture timeout, two-minute idle timeout, system stop/lock, or closing the ERS dialog. Protected or blank screens are rejected without bypass. No audio, accessibility, root, broad package access, storage or exchange credentials permission is requested. If notifications are unavailable, use the image picker.

Preview the image, drag black rectangles over unnecessary personal information, and apply them into the image pixels. Any edits reset consent. Never submit credential-entry screens, OTP, passwords, seed phrases, private keys or identity documents. The request is explicit and disabled until an app, bounded image, HTTPS endpoint, token and consent are present. Selected app changes clear the image and report. Screenshot provenance is user-confirmed, not OS-attested.

`POST /v1/diagnostics` sends the prepared image and selected app label/package/version/enabled state, Android SDK, network type/validation, VPN, proxy and automatic-time state at the time of the request to the ERS service. It does not send the installed app inventory or the ERS account UID. Only the image and fixed classification prompt are forwarded to OpenAI. The provider returns constrained notice codes; local facts and next checks are assembled deterministically. A VPN flag alone never becomes a cause. Reports always contain `actualCauseConfirmed:false`, `officialVerified:false`, and `sourcePackageVerified:false`. A screenshot can establish a displayed notice, not the exchange's underlying server or account decision.

Up to five reports per saved account are encrypted in the existing Android storage; old records load with an empty diagnostics history. Raw images are not persisted by ERS or this service. iOS retains the 1.9 image-based KYC workflow; cross-app enumeration and screen capture in this release are Android-only.

CI checks the server with mocked provider responses and Android 35 device tests for disabled submission gates, app visibility, flattened image masks, encrypted history and rejection of a forged capture grant. A real third-party app's protected screen, a complete user-granted capture on physical hardware, and production AI inference still require validation on the user's device/configured provider. CI does not claim those have passed.

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
- https://developer.android.com/media/grow/media-projection
- https://developer.android.com/training/package-visibility/declaring
- https://render.com/docs/blueprint-spec
