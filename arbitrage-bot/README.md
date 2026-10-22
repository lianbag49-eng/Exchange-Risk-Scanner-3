# 해외 거래소 간 현물 차익거래 자동화 (arbitrage-bot)

Binance · Bybit · OKX의 현물 호가를 동시에 조회합니다. 한 거래소의 매도호가가 다른 거래소의 매수호가보다 **양쪽 수수료를 제하고도** 기준치 이상 낮으면, 싼 곳에서 사고 비싼 곳에서 동시에 팝니다. Node 22 표준 라이브러리만 사용하며 외부 의존성은 없습니다.

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

## 설정 (`config.json`)

| 키 | 기본값 | 설명 |
| --- | --- | --- |
| `fees` | 각 0.001 | 테이커 수수료(비율). 본인 VIP 등급과 할인 적용 여부에 맞게 바꾸세요. |
| `minEdgeBps` | 15 | 수수료 차감 후 최소 순수익률(bp). 5 미만은 거부합니다. |
| `maxNotional` | 200 | 1회 최대 매수 금액(견적 통화) |
| `maxBookAgeMs` | 800 | 이보다 늦게 도착한 호가는 사용하지 않음 |
| `dailyLossLimit` | 20 | 일일 손실 한도 |
| `paperBalances` | — | 모의매매 시작 잔고 |

## 알아둘 한계

- **실제 거래소 연동은 이 저장소의 테스트 환경에서 검증하지 못했습니다.** 개발 환경의 네트워크가 거래소 API를 차단합니다. 서명 방식, 요청 형식, 응답 해석은 공식 문서 기준으로 작성했고 모의 응답으로 테스트했습니다. 실거래 전에 `scan`과 모의매매로 먼저 확인하세요. 처음에는 `maxNotional`을 작게(예: 20) 두고 시작하세요.
- **REST 폴링 방식입니다.** 주요 코인의 거래소 간 가격 차이는 대개 수수료보다 작습니다. 전문 업체는 웹소켓과 코로케이션을 씁니다. 이 도구가 기회를 거의 찾지 못하는 것이 정상일 수 있습니다. 기회는 변동성이 클 때나 유동성이 낮은 알트코인에서 더 자주 보입니다. 다만 그만큼 체결·상장폐지·입출금 중단 위험도 큽니다.
- **재고 쏠림:** 거래를 반복하면 USDT는 싼 거래소에서 줄고, 코인은 비싼 거래소에서 줄어듭니다. 자동 리밸런싱(출금)은 의도적으로 넣지 않았습니다. 잔고가 부족해지면 해당 방향 거래가 멈춥니다. 직접 이체해서 다시 맞춰야 합니다.
- Binance는 매수 수수료를 받은 코인에서 차감할 수 있습니다(BNB 할인 미사용 시). 이 경우 코인 재고가 조금씩 줄어듭니다. 실거래 모드는 거래 후와 주기적으로 실제 잔고를 다시 읽습니다.
- 국내 거래소(김치 프리미엄)는 지원하지 않습니다. 해외 거래소 간 동일 페어(예: BTC/USDT) 차익만 대상으로 합니다.
- 거주 국가에 따라 일부 거래소 이용이 제한되거나, 차익거래 수익에 세금 신고 의무가 있을 수 있습니다. 각 거래소의 약관과 관련 법규를 확인하세요.

## 구조

```
cli.mjs              scan / run [--live] / status / reset
src/exchanges.mjs    Binance·Bybit·OKX 어댑터 (공개 호가·마켓 규칙, 서명 주문·잔고·키 권한 확인)
src/book.mjs         호가 정규화, 깊이 순회, 수량·가격 단위 처리
src/opportunity.mjs  거래소 쌍별 수량 산정과 최적 기회 선택
src/executor.mjs     모의/실거래 실행, 체결 정산, 미헤지·불명 상태 표시
src/risk.mjs         손실·횟수·대기·오류 한도, 정지 상태 저장
src/engine.mjs       폴링 루프와 기록
```

테스트: `npm test`(네트워크·키 없이 모의 응답 사용). 공식 문서: [Binance Spot API](https://developers.binance.com/docs/binance-spot-api-docs/rest-api), [Bybit V5](https://bybit-exchange.github.io/docs/v5/intro), [OKX V5](https://www.okx.com/docs-v5/en/).
