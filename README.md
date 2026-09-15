# Exchange Risk Scanner

Android device/account environment self-audit app. APK is built with GitHub Actions. iOS source is in `ios-app/`.

## Android 1.10 · 앱 AI 진단

[최신 Android APK 다운로드](https://github.com/lianbag49-eng/Exchange-Risk-Scanner-3/raw/refs/heads/main/downloads/Exchange-Risk-Scanner.apk)

계정의 스캔 결과에서 **거래소 앱 AI 진단 · 오류 화면**을 여세요. 설치 앱 선택, 앱 열기, 사용자 동의를 받는 화면 1회 캡처, 개인정보 가리기, 외부 AI 검토, 계정별 암호화 결과 기록을 지원합니다. 기기에서 확인한 사실과 화면의 오류 안내를 구분하며, 실제 계정·서버 측 원인을 확정하지 않습니다.

외부 AI는 서버 배포와 OpenAI 키 설정이 필요합니다. [서버 연결·Render 배포 안내](kyc-service/README.md)를 따라 HTTPS 서버 주소와 검토 토큰을 앱에 입력하세요. 키나 서버가 없는 상태를 연결 성공으로 표시하지 않습니다. OpenAI API 키를 APK나 저장소에 넣지 마세요. iOS는 기존 이미지 기반 KYC 검토를 유지합니다.

## Separate BELTRIX project

BELTRIX wallet and trading code, dependencies, tests and deployment are maintained in [lianbag49-eng/beltrix](https://github.com/lianbag49-eng/beltrix). Open [BELTRIX](https://lianbag49-eng.github.io/beltrix/).

This repository maintains ERS. The `legacy-beltrix/` directory only keeps old website links working. Previous BELTRIX source remains available in Git history at `7d939155e93dad6512c05ff79f24fb3c53dfc1b2`.

