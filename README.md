# Exchange Risk Scanner

Android device/account environment self-audit app. APK is built with GitHub Actions. iOS source is in `ios-app/`.

## Android 1.13 · CMC 전체 목록과 설치 앱 후보 자동 인식

메인의 **거래소 앱 자동 인식 · CMC 전체 목록**에서 거래소 계정 연결 없이 Android가 보여 주는 실행 앱을 분류합니다. 2026-09-15 CMC 공식 Exchange ID Map의 2,448개 항목(활성 978 · 비활성 307 · 미추적 1,163)을 내장했습니다. 목록이 24시간 이상 지났으면 화면을 열 때 전체 페이지를 갱신하며, 수동 갱신도 지원합니다. 갱신 실패·중복 페이지·빈 목록·불완전 응답은 기존 목록을 교체하지 않습니다. 이름과 패키지 이름 일치를 후보로 표시하고 지역별 이름이 더 구체적으로 맞으면 우선합니다.

앱 후보에서 **이 앱 화면 AI 검토**를 누르면 선택 앱을 유지한 채 기존 캡처·마스킹·AI 검토 화면이 열립니다. 계정 연결 없이도 화면 증빙을 검토할 수 있으나 AI 서버 접속과 사용자 캡처·전송 동의는 필요합니다. 로그인 ID를 모르는 앱의 결과는 등록 계정으로 저장하지 않고 현재 세션에서만 보관합니다. 일치하지 않은 실행 앱은 직접 거래소를 지정할 수 있습니다.

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
