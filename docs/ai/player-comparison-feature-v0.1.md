# 플레이어 비교 Feature v0.1

## 목적

`PlayerComparisonFeature`는 기존의 사용자 측 `PlayerComparisonContext`와 stored `BenchmarkSample`의
PostgreSQL aggregate를 결합한 provider-independent comparison read model이다. Backend가 exact cohort,
comparison availability와 metric difference를 결정하며, LLM은 이 feature를 자연어로 설명하는 미래 adapter의
입력으로만 사용한다. 이 버전은 LLM 호출이나 REST endpoint를 포함하지 않는다.

```text
PlayerComparisonContext
    -> exact BenchmarkCohort
    -> PeerBenchmarkQueryService.findBenchmarkExcludingPlayer(...)
    -> 적격성 및 차이 계산
    -> PlayerComparisonFeature
```

## 정확한 cohort 매핑

현재 수집과 사용자 조회의 지원 범위는 KR Ranked Solo이며, 각 `PlayerCohortStatistics`는 다음 exact cohort로
변환된다.

```text
region     = KR
queueId    = 420
tier       = PlayerComparisonContext.rankContext.tier
division   = PlayerComparisonContext.rankContext.division
position   = PlayerCohortStatistics.position
championId = PlayerCohortStatistics.championId
```

예를 들어 GOLD I, MIDDLE, Ahri(103) 사용자는 `KR / 420 / GOLD / I / MIDDLE / 103`만 조회한다.
다른 division, tier 전체, position 또는 champion으로 fallback하지 않는다. `rankContext`가 `null`이면
exact cohort를 만들 수 없으므로 benchmark query를 실행하지 않는다.

`PlayerComparisonContext.targetPuuid`는 이 exact cohort query에만 전달한다. `PlayerComparisonFeatureService`는
`findBenchmarkExcludingPlayer(cohort, targetPuuid)`를 사용하므로 대상 사용자의 own `BenchmarkSample`은 peer
distribution에 포함되지 않는다. PUUID는 feature나 `PlayerAnalysisInput`으로 전달하지 않는다.

## 비교 모델

```text
PlayerComparisonFeature
├── rankContext: PlayerRankContext?
└── comparisons: List<PlayerCohortComparison>
    ├── championId, position, userGames
    ├── status
    ├── benchmarkCohort: BenchmarkCohort?
    ├── benchmarkSampleCount, benchmarkUniquePlayerCount
    └── metrics: PlayerComparisonMetrics?
        ├── kda
        ├── csPerMinute
        ├── goldPerMinute
        ├── damagePerMinute
        ├── visionPerMinute
        ├── killParticipation
        └── damageShare
```

`metrics`는 `status = AVAILABLE`일 때만 존재한다. 그 외 상태는 cohort와 available benchmark metadata를
보존할 수 있지만 metric comparison을 만들지 않는다. `UNRANKED`는 query 자체를 하지 않았으므로 cohort와
benchmark counts가 없다.

Feature와 context의 cohort 순서는 `userGames` 내림차순, `position` 오름차순, `championId` 오름차순이다.
동일 `BenchmarkCohort`는 한 번만 query한다.

## 적격성 및 benchmark 사용 가능 여부

사용자 측 MVP product heuristic은 `MINIMUM_USER_GAMES_FOR_COMPARISON = 5`다. 이는 통계적 유의성을
보장하는 기준이 아니라 1~2경기 같은 지나치게 작은 사용자 표본을 평가에 사용하지 않기 위한 최소 안전장치다.

`PlayerCohortComparisonStatus`는 다음 우선순위로 결정된다.

| Condition | Status |
| --- | --- |
| 현재 Ranked Solo rank 없음 | `UNRANKED` |
| `userGames < 5` | `INSUFFICIENT_USER_SAMPLE` |
| Exact cohort에 sample 없음 | `BENCHMARK_NO_DATA` |
| Benchmark availability policy 미달 | `BENCHMARK_INSUFFICIENT_SAMPLE` |
| 사용자와 benchmark 모두 충분 | `AVAILABLE` |

`PeerBenchmarkQueryService`는 `NO_DATA`, `INSUFFICIENT_SAMPLE`, `AVAILABLE`을 제공한다. Player comparison은
대상 사용자를 제외한 aggregate의 sample/unique player count로 이 policy를 다시 평가한다. 따라서 전체 cohort가
AVAILABLE이어도 own sample 제외 후 30 samples 또는 10 unique players 미만이면
`BENCHMARK_INSUFFICIENT_SAMPLE`이 된다. 모든 matching sample이 대상 사용자의 것이면 `NO_DATA`이며,
feature에서는 `BENCHMARK_NO_DATA`로 매핑한다. 기본값은 sample 30건과 unique player 10명이며, 4 samples / 2
players인 local smoke data는 `INSUFFICIENT_SAMPLE`으로 유지된다.

사용자 표본이 부족해도 rank가 있으면 exact cohort query는 수행하고 benchmark counts를 전달한다. 다만
사용자 상태가 우선하므로 metric comparison은 생성하지 않는다.

## 지표 비교 의미

각 `MetricComparison`은 다음을 가진다.

- `playerValue`: 해당 `(championId, position)` 사용자 Match들의 평균
- `benchmarkMean`, `benchmarkMedian`: exact cohort의 match-level observation aggregate
- `differenceFromMean = playerValue - benchmarkMean`
- `differenceFromMedian = playerValue - benchmarkMedian`
- `benchmarkP25`, `benchmarkP75`, `benchmarkP90`: match-level distribution reference threshold

Backend가 subtraction을 직접 수행한다. 예를 들어 player value 7.2, mean 6.7, median 6.8이면 differences는
각각 `0.5`, `0.4`다.

## 통계적 한계와 금지된 해석

사용자 값은 여러 Match의 평균이고 benchmark distribution은 sampled player의 개별 Match participant
observation 분포다. 그러므로 사용자 평균과 benchmark mean/median 차이는 사실로 제공할 수 있지만, 두 값이
같은 population의 동등한 observation이라는 뜻은 아니다.

특히 p25/p75/p90은 player percentile, skill percentile, percentile rank, top X%가 아니다. 사용자 평균이 p75보다
높아도 "상위 25% 플레이어"나 "Gold Ahri 상위권"이라고 말할 수 없다. 이 모델에는 `percentileRank`,
`topPercent`, `playerPercentile`, `skillPercentile` 필드가 없다.

이 버전은 `GOOD`, `BAD`, `STRONG`, `WEAK` 같은 평가 label과 `higherIsBetter` 같은 metric 방향성 policy도
만들지 않는다. LLM은 `status`, exact cohort, counts, 평균·중앙값과 명시적으로 계산된 차이를 설명할 수 있지만,
backend feature만으로 skill rating, MMR, rank percentile 또는 일반적인 좋고 나쁨을 주장해서는 안 된다.

## 범위 밖 항목과 향후 작업

이 버전은 Riot collection, benchmark threshold, representative sampling, player-level benchmark, percentile rank,
LLM/OpenAI, REST endpoint, timeline/rank history, benchmark cache를 변경하지 않는다.

향후 player-level benchmark는 peer별로 충분히 비교 가능한 Match를 먼저 집계한 뒤 peer-player distribution을
구성해야 한다. 그때도 percentile 정의, sample balancing과 product 표현은 별도 정책과 검증을 거쳐야 한다.
