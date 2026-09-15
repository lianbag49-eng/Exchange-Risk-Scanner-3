# Exchange Risk Scanner

Android exchange incident investigation and recurrence-prevention app. APK is built with GitHub Actions. iOS source is in `ios-app/`.

## Android 1.16 · 공식 제한 조회와 신원확인 업체 결과

홈의 **공식 사유 · 신원 검증 결과**에서 거래소 API와 신원확인 업체 결과를 확인합니다. 기존 캡처 AI는 계속 별도 근거입니다.

- **Binance Global HMAC 읽기 API:** 키 권한을 먼저 검사하고 거래·출금·이체 권한이 있거나 필수 권한 필드가 누락되면 연결을 거부합니다. `/api/v3/account`의 UID와 허용 여부, `/sapi/v1/account/status`의 상태 문구, `/sapi/v1/account/apiTradingStatus`의 잠금·복구 예정·조건 값을 조회합니다. UID 불일치는 적용하지 않으며 상태 조회 일부 실패는 미확인으로 표시합니다. 조회 경로 외 주문·출금 요청은 허용하지 않습니다. 응답의 잔액은 저장하거나 AI에 보내지 않습니다.
- **보고 범위:** API 거래 잠금과 계정 권한은 거래소 보고 사실입니다. 조건 값은 실제 발동 원인이나 내부 KYC 거절 사유를 입증하지 않습니다. Binance KYC 진위는 이 API에서 미제공입니다. Bybit의 10024·10009·10010은 공식 문서의 컴플라이언스·지역 이용 제한·키 IP 불일치 안내로 구분하고 상세 비공개 사유를 추측하지 않습니다.
- **Sumsub:** 서버 운영자에게 권한이 있는 기존 검증의 상태·검토 ID/시각·단계·거절/재제출 사유와 신청자 공개용 안내를 HMAC 서명된 API에서 조회합니다. 서버에 등록된 접속자별 대상만 조회하며 앱이 임의의 applicant ID·UID를 지정할 수 없습니다. `clientComment`, 신분증 사진, 이름, 문서 번호, 생체정보는 조회 결과에 포함하지 않습니다. 검증 업체 키와 대상 목록을 서버에 설정하기 전에는 **미연결**입니다. 계약·키 발급·새 신원 검증 수행은 이 업데이트에 포함되지 않으며 자동 가입·유료 주문하지 않습니다.
- **진위 해석:** 완료된 검증의 GREEN/RED와 RETRY를 승인/거절/재제출로 표시합니다. 심사 중인 이전 결과를 승인으로 올리지 않습니다. FORGERY/GRAPHIC_EDITOR가 있으면 **업체가 보고한 위변조/편집 사유**로 표시합니다. 승인만으로 문서 진위를 단독 확정하거나 해당 거래소 계정의 본인·KYC와 같다고 결론 내리지 않습니다. 독립 검증 결과를 거래소 비공개 데이터처럼 표시하지 않습니다.
- **사전 점검:** Binance의 현재 제한·비허용 보고를 주의 신호에 포함합니다. 새 제한 코드의 AI 전송에는 전송 범위를 다시 확인하고 켜야 합니다. 1.15의 암호화된 토큰은 유지하지만 동의 전 자동 AI 전송은 꺼집니다. 로컬 점검은 계속 동작합니다. 신원확인 업체 조회는 별도 동의 후 수동 요청하며 AI에 전송하지 않고 화면 세션에서만 표시합니다.

설정 방법: [서버 신원 검증 연결](kyc-service/README.md). 공식 문서: [Binance 계정](https://developers.binance.com/en/docs/catalog/core-trading-wallet/api/rest-api/account), [Binance UID·권한](https://developers.binance.com/en/docs/catalog/core-trading-spot-trading/api/rest-api/account), [Bybit 오류 코드](https://bybit-exchange.github.io/docs/v5/error), [Sumsub 검증 결과](https://docs.sumsub.com/reference/get-applicant-review-status), [Sumsub 인증](https://docs.sumsub.com/reference/authentication).

## Android 1.15 · 거래소 로고와 사진 없는 자동 사전 점검

홈의 설치 거래소 카드, CMC 목록, 사건 목록·상세에 거래소 로고를 연결했습니다. 기존 52개 내장 로고는 오프라인에서도 표시하고, 나머지는 CMC ID에 해당하는 고정 CDN의 PNG를 필요한 화면에서 불러옵니다. 최대 크기·픽셀 수를 검사하고 로컬 캐시를 사용하며, 로고가 없거나 다운로드에 실패하면 이름 약자를 표시합니다. 이 로고는 **CMC 제공 브랜드 이미지**이며 설치 앱의 공식 배포자나 서명을 인증하지 않습니다. [CMC 로고 출처 예시](https://coinmarketcap.com/exchanges/binance/).

사진 업로드 없이 첫 실행/복귀 시 로컬 사전 점검을 자동 시작합니다. 앱의 디버깅 플래그·활성 상태·설치 처리 앱, 화면 잠금, USB 디버깅, 보안 패치 날짜, 네트워크·VPN·프록시·자동 시간 상태를 조회합니다. 기존 연결 계정은 5분 이내 응답을 활용하고 오래된 응답은 읽기 전용 공식 API로 재조회합니다. 사전 점검에서는 계정 저장소를 쓰지 않으므로 연결 수정·해제를 덮어쓰지 않습니다. 계정 편집·캡처 중과 백그라운드에서는 다음 자동 조회/전송을 중단합니다. 이미 시작된 HTTPS 요청은 제한된 타임아웃 안에 종료될 수 있습니다. 종료한 앱을 몰래 추적하는 서비스나 다른 앱의 세션·화면 자동 탐색은 추가하지 않았습니다.

**위험도 범위:** ERS의 기술적 신호 분류입니다. 디버깅 허용 앱은 높음, 잠금 미설정·USB 디버깅·365일 이상 된 보안 패치·네트워크/시간 문제·연결 계정 KYC 후속 확인·미해결 사용자 사건은 주의입니다. 중요한 관측 자체가 불가능하면 미확인이고, 그 외 관측 신호가 적으면 낮음입니다. VPN/프록시만으로 점수를 올리지 않습니다. 낮음도 계정 안전이나 KYC 진위를 보장하지 않습니다. 연결된 계정과 현재 앱 로그인이 같은지는 미확인입니다. 거래소 내부 위험도·제한 사유·정지 확률을 제공하는 모델이 아닙니다.

**사진 없는 외부 AI:** 홈의 **AI 자동 연결**에서 HTTPS 서버 주소와 서버 접속 토큰을 한 번 설정하고 전송 동의 후 켭니다. 서버 토큰을 Android Keystore AES-256-GCM으로 저장하고 끄기에서 삭제할 수 있습니다. 화면 활성 중 약 5분 간격으로 관측 코드를 AI에 전달하며 한 요청은 최대 50개 앱, 여러 묶음은 간격을 두어 검토합니다. 스크린샷·UID·계정 별칭·API 키·사건 메모는 보내지 않습니다. 앱 패키지의 SHA-256 식별자와 관측/미확인 코드만 보내므로 무작위 익명 ID와 같은 완전한 익명성을 주장하지 않습니다. 패키지명이 사전 대입으로 추측될 수 있습니다. 신호에는 UID를 뺀 KYC 후속 확인 여부와 미해결 사용자 사건 존재 여부가 포함됩니다. API 사용료가 발생할 수 있습니다.

AI는 관측된 신호의 확인 순서와 다음 점검을 코드로 제안합니다. 위험도 자체는 공개된 ERS 분류 규칙으로 계산하고 AI가 임의로 확정 사유·공식 인증·새 위험 신호로 바꿀 수 없습니다. 응답은 요청 ID·입력 해시·대상 앱 해시·허용 코드로 검증합니다. 변경된 관측에 이전 AI 결과를 붙이지 않고, 연결/배포/인증 실패 시 AI 완료로 표시하지 않습니다. 캡처는 이후 오류 안내의 추가 근거로 계속 이용할 수 있습니다.

서버의 새 `/v1/preflight` 경로는 기존 Bearer 인증·동의·중복/요청 제한을 적용합니다. 원본 사진을 받지 않는 별도 JSON 경로이며 개인정보 필드를 포함한 예상 외 요청을 거부합니다. OpenAI Responses API에는 `store:false`와 엄격한 JSON Schema를 사용합니다. 서버 배포와 실제 접속 토큰이 준비되기 전에는 외부 AI 작동 완료를 의미하지 않습니다. 기존 `/healthz` 성공은 AI 판독 성공이 아닙니다. [OpenAI Structured Outputs](https://developers.openai.com/api/docs/guides/structured-outputs), [Android 앱 조회](https://developer.android.com/training/package-visibility), [Android 앱 격리](https://source.android.com/docs/security/app-sandbox).

## Android 1.14 · 첫 실행 자동 인식과 원인·조치 이력

ERS를 처음 실행하거나 다른 앱에서 돌아오면 Android가 조회를 허용한 실행 앱을 CMC 목록과 자동 비교하고 **홈에 설치 후보 카드**를 표시합니다. 별도 계정 등록이나 인식 버튼을 먼저 누를 필요가 없습니다. 설치만으로 실행되거나 다른 앱에 로그인되는 것은 아니며 비공개 계정 ID와 거래소 내부 판정 사유는 읽지 않습니다. 앱 인식 실패는 마지막 조회 시각과 이전 목록을 유지하며 실패를 표시합니다. 숨긴 앱·업무 프로필·복제 앱 등의 조회 제한은 계속 적용됩니다. CMC 목록 갱신은 기존 전체 목록 화면에서 지원합니다.

홈을 **원인 조사·재발 예방** 중심으로 바꿨습니다. 설치 후보의 **원인 기록**에서 사용자에게 표시된 안내·오류 코드, 기억하는 발생 시각, 선택적인 계정 별칭을 남깁니다. 저장 시 Android 버전·네트워크·인터넷 검증·VPN·프록시·자동 시간 설정과 **수집 시각**을 별도 관측으로 기록합니다. 과거 사건 시점의 기기 상태로 소급해서 표시하지 않습니다. 설치 앱이 하나여도 여러 계정/사건을 따로 기록할 수 있으며 별칭을 실제 로그인 ID로 취급하지 않습니다.

사건별로 조치, 고객지원 답변 메모, 이후 상태(확인 중·지속·해결·미확인)를 추가하고 다시 열어 볼 수 있습니다. 안내·조치·지원 답변은 사용자 입력, 기기 상태는 직접 관측, AI 화면 검토는 원인 후보로 구분합니다. 지원 답변을 붙여 넣거나 해결됨을 선택해도 공식 확인/원인 확정으로 승격하지 않습니다. 같은 앱·안내 유형의 건수와 해결 기록은 사용자 기록 집계이며 조치 효과나 인과관계를 입증하지 않습니다. 기존 기기 점수는 계정 점검 이력에 유지하고 거래소 판정 점수와 명확히 구분했습니다.

계정 미연결 앱의 **AI 검토 결과 저장**도 이제 암호화된 사건 이력에 남습니다. 기존 사건에서 검토를 추가할 수 있고, 다른 앱의 검토를 잘못 붙이는 것은 거부합니다. 기기 관측이 없는 AI 단독 사건에는 과거 기기 상태를 만들어 넣지 않습니다. 같은 AI 요청을 반복 저장해도 중복 추가하지 않습니다. 원본 캡처는 저장하지 않습니다.

사건은 별도 Android Keystore AES-256-GCM 저장소에 최대 200건, 사건당 조치 100개·AI 결과 20개까지 저장합니다. 한도에 도달하면 기존 항목을 조용히 버리지 않고 저장을 거부합니다. 복호화/복구 실패 시 기존 데이터 위에 쓰지 않습니다. 개별 사건과 전체 사건 삭제를 확인창에서 지원합니다. 앱 인식 자체는 전체 설치 목록을 저장하거나 서버로 보내지 않습니다. 사건은 저장 버튼을 누를 때 보관하며 외부 AI는 기존 캡처/전송 동의와 서버 인증 이후에만 이용합니다.

자동 탐색의 첫 실행·재조회·실패 처리, 무계정 사건 작성/재열기/해결 기록, 암호화 재시작 복구·손상 후 쓰기 잠금, 출처와 앱 불일치 거부를 Android 계측 테스트로 검증합니다. 기존 API·캡처·AI 서버 테스트도 유지합니다. 실제 거래소의 비공개 판정 사유나 실제 계정 인증 성공까지 확인한 것은 아닙니다. Android 변경이며 iOS는 기존 기능입니다.

## Android 1.13 · CMC 전체 목록과 설치 앱 후보 자동 인식

메인의 **거래소 앱 자동 인식 · CMC 전체 목록**에서 거래소 계정 연결 없이 Android가 보여 주는 실행 앱을 분류합니다. 2026-09-15 CMC 공식 Exchange ID Map의 2,448개 항목(활성 978 · 비활성 307 · 미추적 1,163)을 내장했습니다. 목록이 24시간 이상 지났으면 화면을 열 때 전체 페이지를 갱신하며, 수동 갱신도 지원합니다. 갱신 실패·중복 페이지·빈 목록·불완전 응답은 기존 목록을 교체하지 않습니다. 이름과 패키지 이름 일치를 후보로 표시하고 지역별 이름이 더 구체적으로 맞으면 우선합니다.

앱 후보에서 **이 앱 화면 AI 검토**를 누르면 선택 앱을 유지한 채 기존 캡처·마스킹·AI 검토 화면이 열립니다. 계정 연결 없이도 화면 증빙을 검토할 수 있으나 AI 서버 접속과 사용자 캡처·전송 동의는 필요합니다. 1.13에서는 로그인 ID를 모르는 앱의 결과를 현재 세션에서만 보관했으며, 1.14부터 계정과 분리된 사건 이력에 암호화해 저장합니다. 일치하지 않은 실행 앱은 직접 거래소를 지정할 수 있습니다.

CMC 등록·활성 상태는 거래소의 신뢰도나 신분증 진위 인증이 아닙니다. 앱 이름·패키지 일치도 공식 배포자 확인이 아닙니다. 후보의 **공식 앱, 로그인 ID, KYC 승인·신분증 진위는 미확인**으로 표시합니다. 숨긴 앱·복제 앱·다른 프로필·웹 전용 거래소는 Android 앱 목록으로 인식하지 못할 수 있습니다. 공식 KYC 조회는 아래 1.12의 인증된 API 범위를 유지합니다. AI 화면 판독을 진위 확정으로 바꾸지 않았습니다.

작업자 입력 칸과 결과 화면의 작업자 표시를 제거했습니다. 기존 암호화 기록의 호환성은 유지합니다. 거래소 선택 목록은 전체 디렉터리를 검색하고 필요한 행만 표시합니다. 기존 내장 로고 52개를 유지하며 새 항목은 이름 약자로 표시합니다.

CMC 요청에는 앱 목록·계정 정보·API 키를 보내지 않습니다. 고정된 공개 목록 URL만 조회하고 목록 비교는 기기에서 처리합니다. [CMC Keyless API](https://coinmarketcap.com/api/documentation/pro-api-reference/keyless-public-api), [Exchange ID Map](https://coinmarketcap.com/api/documentation/pro-api-reference/exchange), [Android 앱 조회 범위](https://developer.android.com/training/package-visibility).

## Android 1.12 · 공식 계정 연결과 전체 점검

메인의 **계정 연결 · 전체 자동 점검**에서 권한 있는 계정을 연결하고, 연결한 계정 전체를 조회할 수 있습니다. 자동 점검을 켜면 이 화면이 활성화된 동안 5분마다 반복합니다. 백그라운드·앱 종료 후에는 예약 점검하지 않습니다.

| 거래소 | 연결 방식 | 확인하는 내용 |
| --- | --- | --- |
| Bybit Global | 시스템 생성 HMAC 읽기 전용 API 키 | API 키의 UID, KYC 레벨·지역, 메인/서브계정 응답 구분, 읽기 전용 권한 |
| Toobit | 제휴 계정 API 키와 조회할 초대 계정 UID | 해당 제휴 관계에서 조회 가능한 UID의 KYC 통과 여부. 일반 개인 API는 지원하지 않음 |
| 그 외 | 아직 공식 계정 조회 미지원 | 기존 AI 화면 근거 검토 이용 가능. 현재 KYC 미확인으로 표시 |

공식 응답의 조회 시각과 출처를 표시합니다. UID 불일치·권한 부족·조회 실패는 정상으로 처리하지 않습니다. 알 수 없는 KYC 값은 미확인이고, 5분 이상 지난 결과는 재조회 필요로 표시합니다. Bybit 쓰기 권한 키는 연결을 거부합니다. Toobit API는 키 권한 상세를 반환하지 않으므로 읽기 전용 키임을 사용자가 확인해야 하며, 제휴 조회 권한과 읽기 전용 확인 여부를 따로 표시합니다.

API 키는 Android Keystore AES-256-GCM으로 기기에 암호화해 보관하고, 거래소의 고정된 조회 전용 HTTPS 경로에만 요청합니다. 키와 공식 API 응답을 AI 서버로 전송하지 않습니다. 연결 화면은 화면 캡처를 차단하며, 연결 해제로 저장 키를 삭제할 수 있습니다. 계정 연결 최초 확인과 자동 점검 모두 거래·주문·출금 API를 호출하지 않습니다.

**지원 범위:** 앱 목록은 이름으로 찾은 설치 후보이며 로그인된 계정 목록이 아닙니다. 다른 앱의 비공개 로그인 ID를 추출하거나, KYC 화면을 자동 순회하는 기능은 구현하지 않았습니다. 공식 API의 KYC 승인 상태도 신분증 자체의 진위를 증명하지 않습니다. 화면 검토는 사용자가 캡처에 동의한 이미지만 다루고 공식 API 결과와 구분합니다. 이 기능은 Android 전용이며 iOS에 반영되지 않았습니다.

검증은 가상 응답으로 HMAC 서명·UID 일치·권한 거부·미확인 처리·암호화 보관과 Android 화면 흐름을 검사합니다. 실제 거래소 계정 인증 성공은 사용자의 초기 연결 이후 확인해야 합니다.

공식 근거: [Bybit 계정 API](https://bybit-exchange.github.io/docs/v5/user/apikey-info), [Bybit 인증 방식](https://bybit-exchange.github.io/docs/v5/guide), [Toobit 제휴 API](https://api-docs.toobit.com/api/agent.html), [Toobit 인증 방식](https://api-docs.toobit.com/api/basic-information.html), [Android 앱 격리](https://source.android.com/docs/security/app-sandbox).

## AI 서버 연결 및 샘플 분석 (1.11에서 추가)

Android의 AI KYC 검토와 거래소 앱 AI 진단에 `https://ers-ai-review.onrender.com`을 기본 적용합니다. 기존에 저장한 사용자 지정 주소는 유지합니다. Render의 `ERS_REVIEW_TOKEN`은 앱의 세션용 접속 토큰 칸에만 입력하며 공개 APK·저장소에는 포함하지 않습니다.

두 화면의 **연결 + 가상 이미지 분석 테스트**는 API 인증·모델 조회 후 가상 네트워크 오류 이미지 한 장을 실제 AI에 보내 판독까지 검사합니다. 테스트에는 고정된 가상 앱·기기 정보만 사용하며 계정 기록이나 선택한 증빙 이미지를 보내지 않습니다. 테스트 결과는 계정 이력에 저장하지 않습니다. API 사용량이 발생하며, 성공해도 실제 계정의 KYC 승인이나 오류 원인이 확인된 것은 아닙니다. CI는 네트워크 요청 없이 이 테스트 흐름과 실패 처리를 검사합니다.

`/healthz`의 `ready`는 서버 실행 여부만 나타냅니다. 비공개 접속 토큰으로 실행한 샘플 분석 결과를 받기 전에는 외부 AI 분석 완료로 간주하지 않습니다.

[최신 Android APK 다운로드](https://github.com/lianbag49-eng/Exchange-Risk-Scanner-3/raw/refs/heads/main/downloads/Exchange-Risk-Scanner.apk)

계정의 스캔 결과에서 **거래소 앱 AI 진단 · 오류 화면**을 여세요. 설치 앱 선택, 앱 열기, 사용자 동의를 받는 화면 1회 캡처, 개인정보 가리기, 외부 AI 검토, 계정별 암호화 결과 기록을 지원합니다. 기기에서 확인한 사실과 화면의 오류 안내를 구분하며, 실제 계정·서버 측 원인을 확정하지 않습니다.

외부 AI는 서버 배포와 OpenAI 키 설정이 필요합니다. [서버 연결·Render 배포 안내](kyc-service/README.md)를 따라 HTTPS 서버 주소와 검토 토큰을 앱에 입력하세요. 키나 서버가 없는 상태를 연결 성공으로 표시하지 않습니다. OpenAI API 키를 APK나 저장소에 넣지 마세요. iOS는 기존 이미지 기반 KYC 검토를 유지합니다.

## Separate BELTRIX project

BELTRIX wallet and trading code, dependencies, tests and deployment are maintained in [lianbag49-eng/beltrix](https://github.com/lianbag49-eng/beltrix). Open [BELTRIX](https://lianbag49-eng.github.io/beltrix/).

This repository maintains ERS. The `legacy-beltrix/` directory only keeps old website links working. Previous BELTRIX source remains available in Git history at `7d939155e93dad6512c05ff79f24fb3c53dfc1b2`.


## ERS 1.17 — evidence-based cause hypotheses

The incident screen now offers **공식 기준 원인 분석 · 사후 대조**. It works without screenshots using bounded incident observations and, optionally, a user-selected fresh read-only account result. The catalog covers 14 hypotheses from published Bybit errors/API KYC fields, Binance account status/KYC guidance, OKX withdrawal guidance and Android network observations. Other exchanges receive only applicable device checks; absent evidence remains unknown. This is partial documented coverage, not a reproduction of private exchange review systems.

`kyc-service/cause-knowledge.json` and the identical Android asset contain reviewed source URLs, review date, applicability rules, supporting/counter evidence, limits and next checks. Last reviewed: 2026-09-15; version `2026-09-15.1`. Catalog changes require updating both files and shipping a compatible app; the server rejects version mismatches. Public documents are not fetched during each analysis.

`POST /v1/causes` requires the existing ERS bearer token, an independent explicit consent, request UUID, case UUID, exchange code, knowledge version and bounded evidence codes/origins/timestamps. No raw notice, screenshot, UID, API credentials, prior AI guesses or follow-up notes are sent. API provenance is reported by the client and is not re-attested by the server. Account selection must be confirmed by the user; queries older than five minutes or mismatched UIDs are rejected. Current observations do not establish past incident conditions.

The server derives eligible hypotheses and retains counter evidence deterministically. The external model can rank up to three eligible IDs using strict structured output; it cannot generate arbitrary claims, verdicts, probabilities or new source links. Supporting evidence, limitations and prevention checks come from the versioned catalog. No eligible rule means `insufficient_evidence` and no external inference. Existing rate limits, replay checks, request timeouts and no-store responses apply. Existing OPENAI_API_KEY, OPENAI_MODEL and ERS token configuration is reused. No new provider credentials are required for this endpoint.

Hypothesis snapshots and append-only follow-up notes are encrypted with the incident records. The comparison uses the first prediction made before any follow-up, then the most recent user-reviewed follow-up classification. Later predictions are excluded from retrospective prediction comparisons. Direct observations and unknown reasons are excluded from official-reason comparisons. Support/notice records remain user transcriptions, not exchange-certified truth. No accuracy percentages, automatic training labels, background uploads or server-side incident retention are added. Older records remain readable.

Examples of source boundaries: Bybit 10009 means regional service restriction, 10010 key IP mismatch, 10024 unspecified compliance rules, and 10027 unspecified transaction restriction. Generic HTTP 403 cannot identify one reason. Binance `isLocked` describes API trading status; GCR/IFER/UFR values do not identify the actual trigger. KYC status or provider approval does not by itself authenticate identity documents or bind a phone app session.
