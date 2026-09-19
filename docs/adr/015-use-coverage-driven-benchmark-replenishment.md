# ADR-015: Coverage-driven bounded Benchmark Replenishment 사용

상태: Accepted

## 배경

Peer Benchmark는 `gameStartTimestamp` 기준 최근 30일 유효 표본만 집계한다. append-only sample corpus가 있어도
POSITION coverage가 부족하면 role-level 비교를 제공할 수 없다. 반면 request마다 수집하거나 전체 tier/division과
champion-position 조합을 채우려 하면 Riot API 비용과 429 위험이 빠르게 커진다.

기존 manual `BenchmarkSeedService`는 이미 League discovery, Match ID dedupe, Detail cache와 bounded loading,
participant metric, idempotent sample persistence, cooldown 처리를 한 경계에서 제공한다.

## 결정

`BenchmarkReplenishmentTickService`가 명시 allowlist의 KR Ranked Solo tier/division cohort만 대상으로,
동일한 query window의 `POSITION` coverage를 평가한다. TOP, JUNGLE, MIDDLE, BOTTOM, UTILITY가 모두 AVAILABLE이면
skip하고 하나라도 `NO_DATA` 또는 `INSUFFICIENT_SAMPLE`이면 candidate가 된다. coverage query에 없는 position은
0건 `NO_DATA`로 해석한다.

`CHAMPION_POSITION`은 replenish trigger와 fallback이 아니다. 같은 collector의 결과로 충분해질 수 있지만 모든
champion-position 조합을 채우는 목표는 두지 않는다.

각 tick은 설정된 작은 global budget 안에서 deterministic priority의 cohort만 처리한다. 기본값은 cohort 1개, page 1개,
player 10명, player당 Match 5개다. 부족분을 채울 때까지 반복하지 않으며 다음 tick에서 coverage를 다시 평가한다.
기존 `BenchmarkSeedService.seed(...)`를 사용하고 Riot I/O와 DB cursor transaction을 함께 열지 않는다.

`benchmark_replenishment_cursor`는 cohort별 discovery page를 저장한다. 정상 discovery 뒤에는 실제 처리한 page 수만큼 전진하고 빈
page는 1로 wrap한다. discovery 단계의 429/local cooldown은 last-attempt metadata만 남기고 page를 전진시키지 않는다. collector 단계의 429는
그 tick의 후속 collection을 멈추되 discovery가 끝난 page는 반복하지 않는다. scheduler는 기본 disabled이고 process
내 guard만 사용한다. `RUN_BENCHMARK_REPLENISHMENT_ONCE`는 scheduler와 별개의 opt-in 검증 runner이며, 실행 결과의
민감정보 없는 구조화된 요약을 application log에 남긴다.

## 결과와 Trade-off

- freshness 기준과 동일한 coverage로 collection 필요성을 결정하고, old DB row count를 성공 기준으로 사용하지 않는다.
- public endpoint와 analysis request에서 Riot 수집을 시작하지 않아 비용을 사용자 요청에 연결하지 않는다.
- JVM-local cooldown을 재사용하며 retry, sleep, benchmark 전용 rate limiter를 만들지 않는다.
- cursor는 restart를 넘겨 유지하지만 multi-instance claim이나 distributed scheduler는 제공하지 않는다.
- metric은 low-cardinality outcome만 기록하고 PUUID, Riot ID, Match ID와 cohort identifier를 tag로 사용하지 않는다.
- allowlist 확장, high-tier discovery, demand-driven activation, patch-aware corpus, distributed lock은 별도 결정이다.

## 검토한 대안

### 분석 요청 시 synchronous seed

사용자 지연 시간·Riot 비용·429 영향을 예측하기 어렵고, HTTP request가 cohort activation을 결정하게 된다.

### 모든 champion-position 조합 우선 충족

희소한 조합 때문에 API 비용이 과도해지고, 현재 필요한 role-level baseline을 늦춘다.

### 부족분까지 무제한 반복 수집

rate limit과 비용을 통제할 수 없다. bounded incremental convergence가 현재 single-instance MVP에 더 적합하다.
