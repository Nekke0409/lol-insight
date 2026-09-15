# Peer Benchmark v0.1

## Scope

v0.1 `PeerBenchmark` is an on-demand PostgreSQL aggregate over stored `BenchmarkSample` rows. It is an
application/domain read model; no JPA entity is returned outside the persistence boundary.
`PeerBenchmarkQueryService.findBenchmark(cohort)` reads the complete cohort corpus, while
`findBenchmarkExcludingPlayer(cohort, puuid)` is the explicit player-comparison query. There is no public REST
endpoint in this version.

The exact cohort key is:

```text
region + queueId + tier + division + position + championId
```

`division` is deliberately included. A GOLD I sample is not a whole-GOLD benchmark, and it must not be presented as
one. Tier-wide aggregation needs a separate sampling policy that deliberately covers all divisions.

## User-side comparison input

`PlayerComparisonContext` is the implemented provider-independent user input for `PlayerComparisonFeature`. It retains the
target PUUID for Backend-only aggregate exclusion, the user's current `RANKED_SOLO_5x5` tier/division and capture time,
plus target-player Match metrics grouped by the same `championId + position` dimensions. A player without a Ranked Solo
entry has `rankContext = null`; this is not a Riot failure. The context deliberately does not invoke
`PeerBenchmarkQueryService`, construct a `BenchmarkCohort`, apply a minimum-user-games policy, calculate a difference,
or claim a percentile. Those responsibilities belong to `PlayerComparisonFeatureService`; see
[Player Comparison Context v0.1](../ai/player-comparison-context-v0.1.md) and
[Player Comparison Feature v0.1](../ai/player-comparison-feature-v0.1.md) for the separate contracts.

## Observation unit and output

One `BenchmarkSample` is one `(sampled player PUUID, matchId)` participant-level observation. It is not a player
average. Therefore `PeerBenchmark` describes the distribution of **match-level observations** in the exact cohort.

For each of KDA, CS/min, gold/min, damage/min, vision/min, kill participation and damage share, the result has:

- `mean`: arithmetic mean of match observations
- `median`: p50 threshold of match observations
- `p25`, `p75`, `p90`: continuous percentile thresholds of match observations

For example, `csPerMinute.p90 = 8.1` means the 90th-percentile threshold among collected match observations for that
cohort is 8.1. It does not mean a player with 8.1 CS/min is in the top 10% of players. v0.1 calculates neither a user
percentile rank nor a player percentile, score, MMR, ranking, or “top X% player” claim.

`PeerBenchmark` exposes both counts:

- `sampleCount = COUNT(*)`: every eligible match observation
- `uniquePlayerCount = COUNT(DISTINCT puuid)`: contributing sampled players

A player with 100 eligible matches and another with one contributes 101 samples and two unique players. This is the
current match-level meaning, not a player-balanced benchmark. Heavy contributors can influence the distribution;
weighting, resampling and per-player balancing are future work, likely together with a player-level benchmark.

## Availability policy

`PeerBenchmarkResult` separates data presence from usability:

| Status | Meaning | `benchmark` |
| --- | --- | --- |
| `NO_DATA` | No matching `BenchmarkSample` exists. | `null` |
| `INSUFFICIENT_SAMPLE` | Samples exist but do not meet the minimum policy. | `null` |
| `AVAILABLE` | Both policy minima are met. | `PeerBenchmark` |

The initial configurable heuristic is `minimumSampleCount = 30` and `minimumUniquePlayerCount = 10`, bound from
`benchmark.availability.minimum-sample-count` and `benchmark.availability.minimum-unique-player-count`. These values
are a product safeguard against treating tiny smoke-test data as a peer benchmark; they do not establish statistical
validity. The existing local smoke shape of four samples from two players is therefore `INSUFFICIENT_SAMPLE`, not
`AVAILABLE`.

## Query and storage policy

The persistence query runs `COUNT(*)`, `COUNT(DISTINCT puuid)`, `AVG` and PostgreSQL `percentile_cont` directly on
`benchmark_sample`. It does not load all samples into the JVM to sort them. The player-comparison query adds
`puuid <> :excludedPuuid` to the exact-cohort predicate, so the target player's own samples are excluded from every
count, mean and percentile calculation. Availability is then evaluated from that excluded aggregate; excluding every
matching sample is normal `NO_DATA`. v0.1 intentionally has no aggregate table, materialized view, scheduler,
retention job, or Redis aggregate cache. Those choices can be reconsidered after data volume and query latency are
measured.

`gameVersion` and `gameStartTimestamp` are already retained on each sample, but v0.1 does not filter on them. The
aggregate is not patch-aware, so old and new patches can mix as data accumulates. A production benchmark needs a
freshness window or patch-aware cohort strategy before that mixing becomes material.

## 제한된 개발용 seed 및 coverage

`BenchmarkSeedManualSmokeTest`는 end-to-end analysis smoke test 전에 소량의 sample을 채우기 위한 opt-in 개발용
workflow다. 수집 범위는 KR `RANKED_SOLO_5x5` / queue 420으로 고정하며, Riot 호출 전에 tier, division,
`startPage`, `pageCount`, `playerLimit`, `matchesPerPlayer`를 검증한다. 요청한 page 범위는 유한하고 `page`는
1-based다. 빈 응답은 순회를 중단하며, 각 page에서 PUUID를 정렬한 뒤 중복을 제거하므로 collector는 결정적인 순서로
각 sampled player를 최대 한 번만 받는다. 기존 `(match_id, puuid)` uniqueness와 `saveIfAbsent`는 실행 간 이미
저장된 sample을 계속 처리하므로, 과거에 발견한 player를 전역적으로 skip하지 않는다.

seed는 run 내부 중복 제거 후 discovered player를 `BenchmarkMatchCollectionService.collect`에 그대로 전달한다.
따라서 Ranked Solo Match-ID filter, Match-ID de-duplication, 제한된 Match Detail loading, Redis detail cache,
participant validation, metric 계산 및 idempotent persistence를 재사용한다. 이 workflow에서 429는 terminal condition이다.
다음 discovery page 또는 새로운 collection request를 시작하지 않고 retry/sleep/backoff를 시도하지 않으며, 이미
commit된 sample은 유지하고 유효한 `Retry-After` 값은 `BenchmarkSeedResult`에 보존한다.

`BenchmarkCohortCoverageQueryService`는 seed 이후 exact-cohort 운영 coverage를 보고한다. PostgreSQL은 scope에 맞는
row를 `region, queue_id, tier, division, position, champion_id`로 grouping하고 `COUNT(*)` 및
`COUNT(DISTINCT puuid)`를 계산한다. distribution metric은 의도적으로 다시 계산하지 않는다. 각 row에는 cohort,
count, 기존 availability 상태 및 현재 30/10 policy에 대한 음수가 아닌 `samplesNeeded` / `uniquePlayersNeeded` gap이
포함된다. row는 AVAILABLE 우선, unique-player count 내림차순, sample count 내림차순, position, champion ID 순으로
결정적으로 정렬한다.

coverage에는 target player가 없으므로 항상 전체 corpus를 조회한다. AVAILABLE coverage row가 self-exclusion 뒤에도
analysis target의 AVAILABLE을 보장하지는 않는다. 실제 comparison은
`findBenchmarkExcludingPlayer(cohort, targetPuuid)`를 호출하고 그 결과를 적용해야 한다. smoke-test target은 30 samples와
10 players보다 여유가 있는 row를 선택하는 편이 좋지만, 별도의 운영 availability threshold를 도입하지는 않는다. 이
workflow는 representative 또는 운영 benchmark 수집이 아니라 convenience sampling과 pipeline 검증을 위한 것이다.

## Future player-level benchmark

A future player-level benchmark first aggregates each peer player's sufficiently comparable matches, then forms a
distribution over those player aggregates. That is a different population and may support carefully defined peer-player
percentiles. It is not implemented by the match-level `PeerBenchmark` in v0.1.
