# 해외 거래소 간 현물 차익거래 자동화 (arbitrage-bot)

Binance · Bybit · OKX의 현물 호가를 동시에 조회합니다(Gate.io · Bitget은 조회·백테스트 전용). 한 거래소의 매도호가가 다른 거래소의 매수호가보다 **양쪽 수수료를 제하고도** 기준치 이상 낮으면, 싼 곳에서 사고 비싼 곳에서 동시에 팝니다. Node 22 표준 라이브러리만 사용하며 외부 의존성은 없습니다.

> **기본값은 모의매매(paper)입니다.** 실거래는 환경변수 확인 문구, 출금 권한이 없는 API 키, `--live` 플래그가 모두 갖춰져야 실행됩니다. 차익거래는 원금 손실 위험이 있습니다. 수익을 보장하지 않습니다.

## 동작 방식

1. **재고 선배치 방식:** 각 거래소에 USDT와 코인을 미리 나눠 둡니다. 루프 안에서는 출금·입금(온체인 이동)을 하지 않습니다. 이체에는 수 분~수십 분이 걸려 그 사이 가격 차이가 사라지기 때문입니다.
2. **동시 스냅샷:** 매 주기(`intervalMs`)마다 모든 거래소의 호가를 병렬로 받습니다. 응답이 `maxBookAgeMs`보다 늦은 호가는 버립니다. 서로 다른 시점의 호가로 계산하지 않기 위해서입니다.
3. **깊이 반영 수량 산정:** A의 매도호가와 B의 매수호가를 단계별로 맞춰 가며, 한계 수익률(테이커 수수료 차감 후)이 `minEdgeBps` 이상인 수량만 담습니다. 이 수량에 다음 상한을 적용합니다.
   - 1회 최대 금액 `maxNotional`
   - 매수 거래소의 USDT 잔고, 매도 거래소의 코인 잔고
   - 양 거래소 공통 수량 단위(lot step), 최소 수량, 최소 주문금액
4. **양쪽 동시 IOC 지정가:** 매수는 사용한 가장 높은 매도호가, 매도는 사용한 가장 낮은 매수호가로 IOC 주문을 동시에 냅니다. 계산보다 불리한 가격으로는 체결되지 않습니다. 대신 그 사이 호가가 움직이면 일부만 체결될 수 있습니다.
5. **기록:** 모든 거래를 `data/journal.jsonl`에 남깁니다.

## 백테스트 (실거래 전에 먼저)

차익거래는 분봉·종가 데이터로 검증할 수 없습니다. 거래소마다 캔들 시각이 어긋나서 실제로는 없던 가격 차이가 생겨 보입니다. 호가 깊이가 없어 실제 체결 수량도 알 수 없습니다. 그래서 **여러 거래소의 호가를 같은 시점에 기록한 뒤 재생**합니다.

1. `record`: 봇이 실제로 보는 것과 같은 방식으로 호가를 주기적으로 받아 JSONL로 저장합니다. 첫 줄에 거래소별 주문 단위 규칙도 함께 저장합니다.
2. `backtest`: 저장한 호가를 시간순으로 재생합니다. 실거래와 **같은 코드**(수량 산정, 리스크 한도)로 판단하고, `--latency` 밀리초 뒤의 호가로 IOC 체결을 계산합니다. 그 사이 가격이 움직이면 부분 체결이나 한쪽만 체결된 상황이 그대로 나타납니다.

```bash
# 기록: 간격을 지연보다 짧게(예: 250ms) 잡아야 의미 있는 결과가 나옵니다.
echo '{"intervalMs": 250, "symbols": ["BTC/USDT","ETH/USDT","SOL/USDT"]}' > record.json
node cli.mjs record --config record.json --hours 24 --out data/books.jsonl

# 재생: 수수료·기준치·잔고는 config.json에서 읽습니다. 지연을 바꿔 가며 비교하세요.
node cli.mjs backtest --config config.json --data data/books.jsonl --latency 300
node cli.mjs backtest --config config.json --data data/books.jsonl --latency 1000 --trades data/bt-trades.jsonl
```

보고서 항목은 다음과 같습니다.
- 거래 수(완전 체결 / 양쪽 부분 체결 / 한쪽만 체결)
- 예상 손익과 실현 손익의 차이
- 한쪽 체결 정리 비용, 승률, 최대 낙폭, 자본 대비 수익률
- 경로별 손익, 거래소별 재고 변화

**판단 기준:** 지연을 늘려도 실현 손익이 예상 손익에 가깝게 유지되고, 한쪽 체결이 드물어야 실거래를 고려할 만합니다. 거래 0건이나 지연에 따른 급격한 악화도 정상적인 결과입니다. 그 상태로 실거래를 하면 안 된다는 뜻입니다.

**백테스트의 한계**
- **한쪽 체결 처리:** 실거래 봇은 한쪽만 체결되면 정지합니다. 백테스트는 그 수량을 즉시 시장가로 정리했다고 가정하고 계속 진행합니다. 정리 비용은 손익에 반영하고, 몇 번이었는지 보고합니다.
- **내 주문의 영향:** 내 주문이 호가를 소진한 효과는 다음 스냅샷에 반영되지 않습니다. 같은 경로 재진입 대기(`cooldownMs`)로 중복 진입을 줄이지만, 얕은 호가에서는 결과가 낙관적입니다.
- **기록 간격:** 기록 간격보다 짧은 지연은 모델링할 수 없습니다. 그 사이 가격 변화가 데이터에 없기 때문입니다. 이 경우 보고서에 경고를 표시합니다.
- **REST 폴링 기준:** 실제 응답 지연, 거래소 장애, 입출금 중단은 기록된 범위만 반영됩니다.

## 리스크 장치

| 장치 | 동작 |
| --- | --- |
| 한쪽 체결 불일치 | 매수·매도 체결 수량이 다르면 **즉시 정지**합니다. 미헤지 수량을 기록하고, 자동으로 정리 주문을 내지 않습니다. |
| 주문 결과 불명 | 타임아웃·5xx·체결 상태 미확정이면 **즉시 정지**합니다. 거래소에서 미체결 주문과 잔고를 직접 확인해야 합니다. |
| 일일 손실 한도 | `dailyLossLimit`(USDT)에 도달하면 정지합니다. UTC 기준으로 날짜가 바뀌면 초기화됩니다. |
| 일일 거래 횟수 | `maxTradesPerDay` |
| 같은 경로 재진입 대기 | `cooldownMs` |
| 연속 오류 | `maxConsecutiveErrors`회 연속 오류가 나면 정지합니다. |
| API 키 권한 | 실거래 시작 전 출금 권한이 있는 키는 **거부**합니다. 확인 경로는 Binance `apiRestrictions`, Bybit `query-api`, OKX `account/config`입니다. |

정지 상태는 `data/risk-state.json`에 남아 재시작해도 유지됩니다. 원인을 정리한 뒤 `node cli.mjs reset`으로 해제합니다.

## 사용법

```bash
cd arbitrage-bot

# 0) 백테스트: 위 '백테스트' 절 참고 (record → backtest)

# 1) 현재 가격 차이 조회 (키 불필요)
node cli.mjs scan --symbols BTC/USDT,ETH/USDT

# 2) 모의매매: 실제 호가로 가상 잔고를 거래
cp config.example.json config.json   # 수수료(VIP 등급), 기준치, 가상 잔고 조정
node cli.mjs run --config config.json

# 3) 상태 확인 / 정지 해제
node cli.mjs status
node cli.mjs reset

# 4) 실거래 (충분히 모의매매로 검증한 뒤)
export BINANCE_API_KEY=... BINANCE_API_SECRET=...
export BYBIT_API_KEY=...   BYBIT_API_SECRET=...
export OKX_API_KEY=...     OKX_API_SECRET=...    OKX_PASSPHRASE=...
export ARB_LIVE_CONFIRM=I_ACCEPT_REAL_FUNDS_RISK
node cli.mjs run --live --config config.json
```

`--exchanges binance,bybit`처럼 일부 거래소만 지정할 수 있습니다. 키는 환경변수에서만 읽고 파일에 저장하지 않습니다. 로그에도 남기지 않습니다. API 키는 **현물 거래 권한만** 주고 출금 권한은 끄세요. 가능하면 IP 화이트리스트도 거세요. Bybit은 통합거래계좌(UNIFIED) 잔고를 사용합니다. OKX는 현금(cash) 모드 현물 주문을 사용합니다.

## 지원 거래소

| 거래소 | 시세 조회·기록·백테스트·모의매매 | 웹소켓 호가 | 실거래 |
| --- | --- | --- | --- |
| Binance | ✅ | ✅ 20단계, 100ms | ✅ |
| Bybit | ✅ | ✅ 50단계, 스냅샷+변경분 | ✅ |
| OKX | ✅ | ✅ 5단계, 100ms | ✅ |
| Gate.io | ✅ | ❌ (REST 조회) | ❌ |
| Bitget | ✅ | ❌ (REST 조회) | ❌ |

**Gate.io · Bitget은 실거래를 막아 두었습니다.**
- Bitget은 API 문서가 통합 계좌(UTA) 체계로 개편 중이라 주문 형식을 확정할 수 없었습니다.
- Gate.io는 API 키에 출금 권한이 있는지 확인할 방법이 문서에 없습니다.
- 확인되지 않은 주문 코드는 위험하므로, 두 거래소를 넣고 `--live`를 실행하면 시작 단계에서 거부합니다.

그래도 백테스트로 "거래소를 늘리면 기회가 늘어나는지"는 확인할 수 있습니다.

```bash
node cli.mjs record --exchanges binance,bybit,okx,gate,bitget --hours 24
node cli.mjs backtest --config config.json --data data/books-....jsonl
```

이때 `config.json`의 `paperBalances`에 gate, bitget 잔고도 넣어야 합니다.

## 웹소켓 호가 (`--feed ws`)

기본 방식(`rest`)은 매 회차마다 각 거래소에 호가를 요청합니다. 왕복 시간만큼 늦고, 요청 횟수 제한 때문에 간격을 줄이기 어렵습니다. `--feed ws`를 쓰면 거래소가 바뀐 호가를 계속 보내 주고, 봇은 받은 최신 호가를 바로 씁니다.

```bash
node cli.mjs run --config config.json --feed ws
node cli.mjs record --config record.json --feed ws --hours 24   # 기록도 웹소켓으로
```

- 받은 지 `maxBookAgeMs`가 지난 호가는 쓰지 않습니다. 연결이 끊긴 거래소는 그 즉시 호가를 비우고, 다시 연결될 때까지 REST로 조회합니다.
- 연결이 끊기면 1초, 2초, 4초… 최대 30초 간격으로 재연결합니다. 30초 동안 데이터가 없으면 연결을 끊고 새로 연결합니다. Binance는 24시간마다 연결을 끊는데, 이것도 자동으로 다시 연결합니다.
- 웹소켓을 쓰면 판단 간격 `intervalMs`를 250ms 정도로 줄일 수 있습니다. 주문 API의 요청 제한은 별개입니다.
- 기록할 때는 `intervalMs`마다 최신 호가를 한 번씩 저장합니다. 그 사이에 여러 번 바뀐 호가는 마지막 것만 남습니다. 기록 간격은 100~250ms를 권장합니다.
- OKX 웹소켓은 5단계까지만 보내므로, OKX 쪽 주문 수량이 그만큼 작게 계산될 수 있습니다.
- 웹소켓 처리는 공식 문서 형식대로 작성하고 가짜 웹소켓으로 테스트했습니다. 실제 연결은 이 개발 환경에서 확인하지 못했습니다. 처음에는 `record --feed ws`로 데이터가 잘 쌓이는지 먼저 확인하세요.

## 텔레그램 알림

시작, 체결, 정지, 종료를 텔레그램으로 받을 수 있습니다.

1. 텔레그램에서 [@BotFather](https://t.me/BotFather)에게 `/newbot`을 보내 봇을 만들고 토큰을 받습니다.
2. 만든 봇에게 아무 메시지나 보낸 뒤, `https://api.telegram.org/bot<토큰>/getUpdates`를 열어 `chat.id` 값을 확인합니다.
3. 환경변수를 설정합니다.
   ```bash
   export TELEGRAM_BOT_TOKEN=123456:ABC...
   export TELEGRAM_CHAT_ID=123456789
   ```

알림은 다음 경우에 보냅니다.
- ▶️ 시작
- ✅ 체결(예상 손익과 실현 손익)
- ⚠️ 한쪽만 체결 또는 주문 결과 불명
- 🛑 정지(사유 포함)
- ⏹ 정상 종료(오늘 거래 수와 손익)

알림 전송이 실패해도 거래에는 영향을 주지 않습니다. 토큰은 로그에 남기지 않습니다.

## 서버에서 24시간 운영

**1. 서버 고르기**
- 거래소 서버와 가까울수록 지연이 줄어듭니다.
  - Binance: AWS 도쿄(ap-northeast-1)로 알려져 있습니다.
  - Bybit: AWS 싱가포르(ap-southeast-1)로 알려져 있습니다.
  - OKX: 홍콩으로 알려져 있습니다.
- 세 거래소를 함께 쓰면 **도쿄나 싱가포르**가 무난합니다. 이 정보는 공식 보장이 아니므로, 후보 서버마다 `node cli.mjs scan`을 실행해 거래소별 응답 시간(ms)을 비교해 보세요.
- 서버가 있는 국가, 그리고 본인 거주 국가에서 해당 거래소 이용이 허용되는지 각 거래소 약관을 확인하세요. 일부 국가(예: 미국)의 IP는 거래소가 차단합니다.
- 사양은 작아도 됩니다. CPU 1개, 메모리 1GB면 충분합니다.

**2. 설치**
```bash
# Node 22 설치 후
git clone https://github.com/lianbag49-eng/Exchange-Risk-Scanner-3.git
cd Exchange-Risk-Scanner-3/arbitrage-bot
npm test                                  # 서버에서도 테스트 통과 확인
cp config.example.json config.json        # 수수료·기준치·잔고 조정
cp deploy/arb.env.example deploy/arb.env  # 키 입력
chmod 600 deploy/arb.env                  # 본인만 읽기
node --env-file=deploy/arb.env cli.mjs fees   # 키와 실제 수수료 확인
```
`deploy/arb.env`는 `.gitignore`에 들어 있어 커밋되지 않습니다.

**API 키 설정:** 현물 거래 권한만 주고, 출금 권한은 끄세요. **IP 화이트리스트에 서버 IP만** 등록하세요. 키가 유출돼도 다른 곳에서 쓸 수 없습니다.

**3. pm2로 상시 실행**
```bash
npm install -g pm2
pm2 start deploy/ecosystem.config.cjs     # 기본: 모의매매 + 웹소켓
pm2 logs arb-bot                          # 로그 보기
pm2 stop arb-bot                          # 지금 회차를 마치고 종료
pm2 save && pm2 startup                   # 서버 재부팅 후 자동 시작
```

`deploy/ecosystem.config.cjs`의 동작은 다음과 같습니다.
- **비정상 종료(오류)일 때만** 다시 시작합니다. 최대 5회까지입니다.
- **리스크 정지로 끝났을 때는 다시 시작하지 않습니다.** 원인을 확인하고 `node cli.mjs reset`을 실행한 뒤 `pm2 restart arb-bot`으로 직접 다시 시작하세요.
- 실거래로 바꾸려면 두 가지를 직접 바꿔야 합니다.
  - `args`에 `--live`를 추가합니다.
  - `deploy/arb.env`에서 `ARB_LIVE_CONFIRM` 줄의 주석을 풉니다.

**4. 점검**
- `data/journal.jsonl`: 모든 거래 기록
- `node cli.mjs status`: 정지 여부, 오늘 거래 수와 손익
- 텔레그램 알림을 켜 두면 정지를 바로 알 수 있습니다.
- 각 거래소의 실제 잔고와 미체결 주문은 하루 한 번 정도 직접 확인하세요.

## 설정 (`config.json`)

| 키 | 기본값 | 설명 |
| --- | --- | --- |
| `feed` | `rest` | `ws`면 Binance·Bybit·OKX 호가를 웹소켓으로 받습니다. 나머지 거래소는 REST로 조회합니다. |
| `fees` | 각 0.001 | 테이커 수수료(비율). 일반(비VIP) 회원 기준값입니다. 아래 '수수료' 절을 참고하세요. |
| `minEdgeBps` | 15 | 수수료 차감 후 최소 순수익률(bp). 5 미만은 거부합니다. |
| `maxNotional` | 200 | 1회 최대 매수 금액(견적 통화) |
| `maxBookAgeMs` | 800 | 이보다 늦게 도착한 호가는 사용하지 않음 |
| `dailyLossLimit` | 20 | 일일 손실 한도 |
| `paperBalances` | — | 모의매매 시작 잔고 |

## 수수료

기본값 0.1%는 세 거래소 **일반(비VIP) 회원의 글로벌 현물 테이커 수수료**입니다. 최고 VIP 등급의 최저 수수료가 아닙니다. 2026-10 공식 수수료표 기준은 다음과 같습니다.

| 거래소 | 일반 회원 테이커 | 최고 등급 테이커 | 비고 |
| --- | --- | --- | --- |
| Binance | 0.100% (BNB 결제 0.075%) | VIP 9: 0.023% (BNB 0.01725%) | |
| Bybit | 0.100% | Supreme VIP: 0.045% | |
| OKX | 0.100% (글로벌) | VIP 9: 0.015% | 법인·지역마다 다릅니다. 싱가포르는 0.2%, 미국은 0.35%입니다. |

**본인 계정의 실제 요율을 쓰세요.**
- **실거래:** 시작할 때 각 거래소 API에서 계정의 실제 테이커 요율을 읽습니다. 설정값과 실제값 중 **높은 쪽**을 사용합니다. 설정이 실제보다 낮게 잘못 입력돼 있어도, 손실 거래가 이익으로 계산되지 않습니다.
  - Binance: `/api/v3/account/commission`
  - Bybit: `/v5/account/fee-rate`
  - OKX: `/api/v5/account/trade-fee`
- **백테스트·모의매매:** 키를 설정하고 `node cli.mjs fees`를 실행하면 계정 요율과 config에 넣을 값을 출력합니다.
- **BNB 할인:** Binance의 BNB 수수료 할인은 BNB 잔고가 있을 때만 적용됩니다. 그래서 보수적으로 반영하지 않습니다.

## 알아둘 한계

- **실제 거래소 연동은 이 저장소의 테스트 환경에서 검증하지 못했습니다.** 개발 환경의 네트워크가 거래소 API를 차단합니다. 서명 방식, 요청 형식, 응답 해석은 공식 문서 기준으로 작성했고 모의 응답으로 테스트했습니다. 실거래 전에 `record` → `backtest`, `scan`, 모의매매 순서로 먼저 확인하세요. 처음에는 `maxNotional`을 작게(예: 20) 두고 시작하세요.
- **REST 폴링 방식입니다.** 주요 코인의 거래소 간 가격 차이는 대개 수수료보다 작습니다. 전문 업체는 웹소켓과 코로케이션을 씁니다. 이 도구가 기회를 거의 찾지 못하는 것이 정상일 수 있습니다. 기회는 변동성이 클 때나 유동성이 낮은 알트코인에서 더 자주 보입니다. 다만 그만큼 체결·상장폐지·입출금 중단 위험도 큽니다.
- **재고 쏠림:** 거래를 반복하면 USDT는 싼 거래소에서 줄고, 코인은 비싼 거래소에서 줄어듭니다. 자동 리밸런싱(출금)은 의도적으로 넣지 않았습니다. 잔고가 부족해지면 해당 방향 거래가 멈춥니다. 직접 이체해서 다시 맞춰야 합니다.
- Binance는 매수 수수료를 받은 코인에서 차감할 수 있습니다(BNB 할인 미사용 시). 이 경우 코인 재고가 조금씩 줄어듭니다. 실거래 모드는 거래 후와 주기적으로 실제 잔고를 다시 읽습니다.
- 국내 거래소(김치 프리미엄)는 지원하지 않습니다. 해외 거래소 간 동일 페어(예: BTC/USDT) 차익만 대상으로 합니다.
- 거주 국가에 따라 일부 거래소 이용이 제한되거나, 차익거래 수익에 세금 신고 의무가 있을 수 있습니다. 각 거래소의 약관과 관련 법규를 확인하세요.

## 구조

```
cli.mjs              scan / fees / record / backtest / run [--live] / status / reset
src/exchanges.mjs    Binance·Bybit·OKX 어댑터 (공개 호가·마켓 규칙, 서명 주문·잔고·키 권한 확인), Gate·Bitget 공개 조회
src/feeds.mjs        웹소켓 호가 수신 (재연결·연결 유지 신호·변경분 반영)
src/notify.mjs       텔레그램 알림
deploy/              pm2 실행 설정, 서버용 환경변수 예시
src/book.mjs         호가 정규화, 깊이 순회, 수량·가격 단위 처리
src/opportunity.mjs  거래소 쌍별 수량 산정과 최적 기회 선택
src/executor.mjs     모의/실거래 실행, 체결 정산, 미헤지·불명 상태 표시
src/risk.mjs         손실·횟수·대기·오류 한도, 정지 상태 저장
src/engine.mjs       폴링 루프와 기록
src/recorder.mjs     백테스트용 호가 기록
src/backtest.mjs     기록 재생, 지연 반영 체결, 보고서
```

테스트: `npm test`(네트워크·키 없이 모의 응답 사용). 공식 문서: [Binance Spot API](https://developers.binance.com/docs/binance-spot-api-docs/rest-api), [Bybit V5](https://bybit-exchange.github.io/docs/v5/intro), [OKX V5](https://www.okx.com/docs-v5/en/).
