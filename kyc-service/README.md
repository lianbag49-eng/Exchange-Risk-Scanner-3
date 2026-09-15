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

CI checks the server with mocked provider responses and Android 35 device tests for disabled submission gates, app visibility, flattened image masks, encrypted history, rejection of a forged capture grant, and a complete Android system-consented one-shot capture of the emulator Settings app. A real third-party app's protected screen, a complete user-granted capture on physical hardware, and production AI inference still require validation on the user's device/configured provider. CI does not claim those have passed.

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


## 사진 없는 사전 검토 (Android 1.15)

새 `preflight.mjs`와 `POST /v1/preflight`를 포함해 서버를 다시 배포해야 합니다. `OPENAI_API_KEY`, `OPENAI_MODEL`, `ERS_REVIEW_TOKEN(S)`은 기존 설정을 유지합니다. Dockerfile에 모듈을 포함했습니다. Render 작업 공간과 기존 `ers-ai-review` 서비스를 확인한 뒤 해당 저장소의 변경을 배포하세요. 새 서비스를 만들거나 기존 키를 공개할 필요는 없습니다.

요청 형식: `{requestId, consent:true, items:[{id:<패키지 SHA-256>, signals:[<허용 코드>], unknowns:[<허용 코드>]}]}`. 최대 50개, 중복 ID 금지, 예상 외 필드 금지. 사진·UID·키·자유 텍스트를 받지 않습니다. 응답은 입력 해시와 관측 범위의 위험도, AI가 정리한 focus/checks 코드입니다. 공개된 로컬 분류가 위험도를 결정하며, AI는 계정의 실제 제한 사유를 판정하지 않습니다.

`npm test`는 인증/동의/재전송/요청 제한, 개인정보 필드 거부, 위험도 범위, 모델 응답 변조·환각·거절 처리와 provider 전송 내용을 검사합니다. 실제 키를 사용한 검토는 앱의 **연결 + 가상 신호 AI 테스트**로 별도 확인할 수 있습니다.


## ERS 1.16 신원확인 업체 연결 (선택)

`POST /v1/identity/status`는 이미지 AI와 분리된 Sumsub 결과 조회 경로입니다. 기존 ERS Bearer 인증·요청 제한·중복 방지와 별도 `consent:true`를 적용합니다. 설정하지 않으면 인증된 응답에 `status:not_configured`를 반환하며 검증 성공으로 표시하지 않습니다.

Render의 기존 서비스 Environment에 다음 값을 준비합니다. 기존 OpenAI/ERS 설정은 유지합니다. 유효한 업체 계정과 계약/조회 권한이 필요하며 이 코드는 계정 생성이나 새 검증을 구매하지 않습니다.

- `SUMSUB_APP_TOKEN`: 서버 전용 업체 앱 토큰.
- `SUMSUB_SECRET_KEY`: 서버 전용 서명 키.
- `ERS_IDENTITY_SUBJECTS`: ERS 접속자 ID별 조회 가능한 검증 대상. 단일 `ERS_REVIEW_TOKEN`을 쓰면 접속자 ID는 `reviewer`; 여러 사용자는 `ERS_REVIEW_TOKENS`의 각 키와 정확히 맞춥니다. 권한 없는 대상이나 다른 거래소 고객의 applicant ID를 넣지 마세요. 접속자별 토큰을 분리해야 결과 접근도 분리됩니다.

가상 설정 예시(실제 정보로 사용하지 않음):

```json
{"reviewer":[{"id":"11111111-2222-3333-4444-555555555555","label":"가상 검증 대상","applicantId":"1234567890abcdef12345678"}]}
```

`id`는 ERS 내 불투명 UUID, `label`은 표시용 별칭, `applicantId`는 조회 권한이 있는 실제 업체 검증 ID입니다. 이 매핑은 서버 운영자가 정한 조회 범위이며 **거래소 UID나 앱 로그인과의 동일성 증명은 아닙니다**. 별도 교차 검증 없이 거래소 KYC 인증으로 승격하지 않습니다.

요청은 `{"requestId":"새 UUID","consent":true}` 두 필드만 허용합니다. 클라이언트가 applicantId/UID/사진을 지정할 수 없습니다. `/resources/applicants/{applicantId}/status`만 HTTPS GET으로 조회하며 최신 초 단위 타임스탬프와 HMAC SHA-256으로 서명합니다. 사용자당 최대 20대상, 응답당 128KiB, 배치 45초 제한과 실패 상태를 적용합니다. 신원확인 원본 자료·운영자 전용 clientComment는 응답·저장·OpenAI에 전달하지 않습니다. 업체의 신청자 공개용 moderationComment만 표시할 수 있습니다. 서버는 조회 결과를 저장하지 않으며 기기의 이 화면도 세션 표시만 지원합니다.

`completed`인 리뷰만 승인/거절을 보고합니다. GREEN은 업체가 구성된 검증 단계를 승인했다는 뜻이며 신분증 진위 단독 확인이나 거래소 KYC 승인을 의미하지 않습니다. 문서 위변조/편집 사유가 반환되면 해당 업체 보고로 표시하고 없는 사실을 만들지 않습니다. 사진이나 프로필을 제출하는 SDK, 안면 대조, 신규 검증 시작은 이 경로에서 실행하지 않습니다.

검증: 가상 응답으로 인증·권한별 대상 격리·서명·공개 필드 최소화·오류/심사 중 처리·요청 중복·Android 결과 연결을 검사합니다. 실제 Sumsub 계정 결과와 실제 Binance 키 인증은 각 서비스의 유효한 권한으로 연결한 후에만 확인할 수 있습니다. `/healthz` 200 또는 미인증 경로의 401은 실제 모델/신원 검증 성공을 의미하지 않습니다.


## ERS 1.17 — evidence-based cause hypotheses

The incident screen now offers **공식 기준 원인 분석 · 사후 대조**. It works without screenshots using bounded incident observations and, optionally, a user-selected fresh read-only account result. The catalog covers 14 hypotheses from published Bybit errors/API KYC fields, Binance account status/KYC guidance, OKX withdrawal guidance and Android network observations. Other exchanges receive only applicable device checks; absent evidence remains unknown. This is partial documented coverage, not a reproduction of private exchange review systems.

`kyc-service/cause-knowledge.json` and the identical Android asset contain reviewed source URLs, review date, applicability rules, supporting/counter evidence, limits and next checks. Last reviewed: 2026-09-15; version `2026-09-15.1`. Catalog changes require updating both files and shipping a compatible app; the server rejects version mismatches. Public documents are not fetched during each analysis.

`POST /v1/causes` requires the existing ERS bearer token, an independent explicit consent, request UUID, case UUID, exchange code, knowledge version and bounded evidence codes/origins/timestamps. No raw notice, screenshot, UID, API credentials, prior AI guesses or follow-up notes are sent. API provenance is reported by the client and is not re-attested by the server. Account selection must be confirmed by the user; queries older than five minutes or mismatched UIDs are rejected. Current observations do not establish past incident conditions.

The server derives eligible hypotheses and retains counter evidence deterministically. The external model can rank up to three eligible IDs using strict structured output; it cannot generate arbitrary claims, verdicts, probabilities or new source links. Supporting evidence, limitations and prevention checks come from the versioned catalog. No eligible rule means `insufficient_evidence` and no external inference. Existing rate limits, replay checks, request timeouts and no-store responses apply. Existing OPENAI_API_KEY, OPENAI_MODEL and ERS token configuration is reused. No new provider credentials are required for this endpoint.

Hypothesis snapshots and append-only follow-up notes are encrypted with the incident records. The comparison uses the first prediction made before any follow-up, then the most recent user-reviewed follow-up classification. Later predictions are excluded from retrospective prediction comparisons. Direct observations and unknown reasons are excluded from official-reason comparisons. Support/notice records remain user transcriptions, not exchange-certified truth. No accuracy percentages, automatic training labels, background uploads or server-side incident retention are added. Older records remain readable.

Examples of source boundaries: Bybit 10009 means regional service restriction, 10010 key IP mismatch, 10024 unspecified compliance rules, and 10027 unspecified transaction restriction. Generic HTTP 403 cannot identify one reason. Binance `isLocked` describes API trading status; GCR/IFER/UFR values do not identify the actual trigger. KYC status or provider approval does not by itself authenticate identity documents or bind a phone app session.
