# ADR-008: 명시적인 Benchmark Scope 사용

상태: Accepted

## 배경

ADR-006은 단일 `region / queue / tier / division / position / championId` exact benchmark를 도입했다. 현재
local GOLD I corpus에는 34개의 `BenchmarkSample` observation이 있지만, 이는 23개의 exact
champion-position cohort에 분산되어 있다. 가장 큰 cohort도 3명의 player가 제공한 5개 sample뿐이며,
availability policy는 30 samples와 10 unique players를 요구한다.

기존 availability threshold를 낮추거나 exact cohort가 sparse할 때 position aggregate로 조용히 대체하면,
comparison population의 중요한 차이가 가려진다. 또한 user-side statistic과 benchmark statistic의 단위가
일치하지 않게 된다.

## 결정

두 개의 독립적인 `BenchmarkScope`를 제공한다.

- `POSITION`: `region / queueId / tier / division / position`
- `CHAMPION_POSITION`: `region / queueId / tier / division / position / championId`

`BenchmarkCohort`는 scope를 가진다. `championId`는 `POSITION`에서 없고 `CHAMPION_POSITION`에서는 반드시
존재해야 하며, model은 유효하지 않은 조합을 거부한다.

Backend는 각 scope의 user Match statistic을 별도로 계산하고 같은 statistical unit끼리만 연결한다.

- 모든 MIDDLE user observation은 `POSITION / MIDDLE` benchmark와 비교한다.
- Ahri MIDDLE user observation만 `CHAMPION_POSITION / Ahri / MIDDLE` benchmark와 비교한다.

두 scope 모두 기존 30 samples / 10 unique players availability policy를 사용한다. 각각 target PUUID exclusion
후 count, distribution, availability를 다시 계산한다. position 결과가 `AVAILABLE`이어도 sparse한
champion-position 결과는 `INSUFFICIENT_SAMPLE`로 유지되며 fallback하지 않는다.

Coverage report는 두 scope를 반환하고 각 row에 scope를 명시한다.

## 결과

Position-level coverage는 champion-specific corpus보다 먼저 role-level baseline을 제공할 수 있다. 다만 champion
kit과 playstyle이 섞이므로 champion-specific skill baseline으로 표현하지 않는다.

저장된 `benchmark_sample` schema와 collection workflow는 변경하지 않는다. PostgreSQL은 계속 on-demand로
aggregate를 계산하며, 이 결정으로 JVM에 raw corpus를 올리거나 cache를 추가하거나 threshold를 변경하거나 Riot
request를 보내지 않는다.

기존 OpenAI analysis mapping은 `CHAMPION_POSITION`만 사용한다. position-level comparison을 prompt에 전달하는
일은 scope correctness와 LLM contract 확장을 분리하기 위해 다음 작업으로 미룬다.

Benchmark는 계속 match-level이며 patch-aware하지 않고 heavy contributor의 영향을 받을 수 있다. 어느 scope도
player percentile, rank estimate, top-X-percent claim, player-balanced distribution을 만들지 않는다.

## 구현 갱신

이전에 미뤘던 OpenAI input 확장은 Player Analysis v0.2에서 구현됐다. adapter는 AVAILABLE인 모든 POSITION 및
CHAMPION_POSITION comparison을 명시적인 scope와 nullable champion ID invariant를 포함해 결정적인 scope 순서로
전달한다. 두 scope는 독립적으로 유지하며 fallback이나 metric 혼합을 추가하지 않는다.
[Player Analysis v0.2](../ai/player-analysis-v0.2.md)를 참고한다.
