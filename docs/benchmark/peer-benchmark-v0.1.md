# 동료 벤치마크 v0.1

## 범위

v0.1의 `PeerBenchmark`는 저장된 `BenchmarkSample` 중 유효 표본을 대상으로 요청 시 실행하는 PostgreSQL 집계다. 이는
애플리케이션/도메인 읽기 모델이며, 영속성 경계 밖으로 JPA entity를 반환하지 않는다.
`PeerBenchmarkQueryService.findBenchmark(cohort)`는 전체 cohort corpus를 읽고,
`findBenchmarkExcludingPlayer(cohort, puuid)`는 명시적인 플레이어 비교 조회다. 이 버전에는 공개 REST endpoint가 없다.

정확한 cohort 키는 다음과 같다.

```text
region + queueId + tier + division + position + championId
```

`division`을 의도적으로 포함한다. GOLD I 표본은 GOLD 전체 benchmark가 아니며, 그렇게 표시해서도 안 된다.
티어 전체 집계에는 모든 division을 의도적으로 포괄하는 별도 sampling 정책이 필요하다.

## 사용자 측 비교 입력

`PlayerComparisonContext`는 구현된 `PlayerComparisonFeature`의 provider 독립 사용자 입력이다. Backend 전용 집계 제외에
쓸 대상 PUUID, 사용자의 현재 `RANKED_SOLO_5x5` tier/division 및 확인 시각, 동일한 `championId + position` 차원으로 묶은
대상 플레이어 Match 지표를 보유한다. Ranked Solo 항목이 없는 플레이어의 `rankContext`는 `null`이며 Riot 실패가 아니다.
이 컨텍스트는 의도적으로 `PeerBenchmarkQueryService` 호출, `BenchmarkCohort` 생성, 최소 사용자 경기 정책 적용,
차이 계산, 백분위 주장을 하지 않는다. 이 책임은 `PlayerComparisonFeatureService`에 있으며, 별도 계약은
[플레이어 비교 컨텍스트 v0.1](../ai/player-comparison-context-v0.1.md)과
[플레이어 비교 Feature v0.1](../ai/player-comparison-feature-v0.1.md)를 참고한다.

## 관측 단위와 결과

`BenchmarkSample` 한 건은 `(sampled player PUUID, matchId)` 하나의 참가자 단위 관측치다. 플레이어 평균이 아니다.
따라서 `PeerBenchmark`는 정확한 cohort 안의 **경기 단위 관측치** 분포를 나타낸다.

KDA, CS/분, 골드/분, 피해량/분, 시야 점수/분, 킬 관여율, 피해 비율 각각에 대해 결과는 다음을 가진다.

- `mean`: 경기 관측치의 산술평균
- `median`: 경기 관측치의 p50 임곗값
- `p25`, `p75`, `p90`: 경기 관측치의 연속 백분위 임곗값

예를 들어 `csPerMinute.p90 = 8.1`은 해당 cohort에서 수집한 경기 관측치의 90번째 백분위 임곗값이 8.1이라는 뜻이다.
CS/분이 8.1인 플레이어가 상위 10%라는 뜻은 아니다. v0.1은 사용자 백분위 랭크, 플레이어 백분위, 점수, MMR, 순위,
“상위 X% 플레이어” 주장을 계산하지 않는다.

`PeerBenchmark`는 다음 두 건수를 제공한다.

- `sampleCount = COUNT(*)`: 적격한 모든 경기 관측치
- `uniquePlayerCount = COUNT(DISTINCT puuid)`: 기여한 표본 플레이어

적격 경기 100건을 제공한 플레이어와 1건을 제공한 플레이어는 표본 101건과 고유 플레이어 2명으로 기여한다. 이는
플레이어 균형 benchmark가 아닌 현재의 경기 단위 의미다. 많은 경기를 제공한 플레이어가 분포에 영향을 줄 수 있으며,
가중치 부여, 재표본화, 플레이어별 균형화는 플레이어 단위 benchmark와 함께 다룰 향후 과제다.

## 사용 가능 여부 정책

`PeerBenchmarkResult`는 데이터 존재 여부와 사용 가능 여부를 분리한다.

| 상태 | 의미 | `benchmark` |
| --- | --- | --- |
| `NO_DATA` | 일치하는 `BenchmarkSample`이 없다. | `null` |
| `INSUFFICIENT_SAMPLE` | 표본은 있지만 최소 정책을 충족하지 않는다. | `null` |
| `AVAILABLE` | 두 정책 최소값을 모두 충족한다. | `PeerBenchmark` |

초기 설정 가능 휴리스틱은 `minimumSampleCount = 30`, `minimumUniquePlayerCount = 10`이며,
`benchmark.availability.minimum-sample-count`, `benchmark.availability.minimum-unique-player-count`에서 바인딩한다.
이 값은 작은 smoke test 데이터를 동료 benchmark로 취급하지 않기 위한 제품 안전장치일 뿐 통계적 타당성을 보장하지 않는다.
따라서 두 플레이어의 표본 네 건으로 구성된 기존 로컬 smoke 데이터는 `AVAILABLE`이 아니라 `INSUFFICIENT_SAMPLE`이다.

## 조회와 저장 정책

영속성 조회는 `benchmark_sample`에서 `COUNT(*)`, `COUNT(DISTINCT puuid)`, `AVG`, PostgreSQL `percentile_cont`를
직접 실행한다. 모든 표본을 JVM으로 불러와 정렬하지 않는다. `Clock`에서 한 번 만든 `[asOf - maxSampleAge, asOf)`
window를 각 query에 전달하고 `game_start_timestamp >= :fromInclusive AND game_start_timestamp < :toExclusive`로
유효 표본을 제한한다. 기본 `maxSampleAge`는 `BENCHMARK_SAMPLE_MAX_AGE=30d`이며 UTC 기준 rolling duration이다.
플레이어 비교 조회는 정확한 cohort와 기간 조건에 `puuid <> :excludedPuuid`를 추가하므로 대상 플레이어 자신의 표본은 모든 건수, 평균, 백분위 계산에서 제외한다.
이 제외 집계로 사용 가능 여부를 평가하며, 일치하는 표본을 모두 제외한 경우는 정상적인 `NO_DATA`다. v0.1은
aggregate table, materialized view, scheduler, retention job, Redis aggregate cache를 의도적으로 두지 않는다.
데이터 규모와 조회 latency를 측정한 뒤에 이 선택을 다시 검토할 수 있다.

기간 판정에는 오직 `gameStartTimestamp`를 사용한다. 최근 `collectedAt`이나 `rankCapturedAt`이 오래된 경기를
재활성화하지 않는다. 이 것은 query exclusion일 뿐 물리 retention이 아니므로 row 삭제·`expiresAt` column·재수집은
추가하지 않는다. 집계는 여전히 patch-aware하지 않으므로 30일 안에도 여러 patch가 섞일 수 있고, `rankCapturedAt`은
경기 당시 rank history가 아니다.

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

`BenchmarkCohortCoverageQueryService`는 seed 이후 유효 exact-cohort 운영 coverage를 보고한다. PostgreSQL은 scope에 맞는
row를 `region, queue_id, tier, division, position, champion_id`로 grouping하고 `COUNT(*)` 및
`COUNT(DISTINCT puuid)`를 계산한다. distribution metric은 의도적으로 다시 계산하지 않는다. 각 row에는 cohort,
count, 기존 availability 상태 및 현재 30/10 policy에 대한 음수가 아닌 `samplesNeeded` / `uniquePlayersNeeded` gap이
포함된다. row는 AVAILABLE 우선, unique-player count 내림차순, sample count 내림차순, position, champion ID 순으로
결정적으로 정렬한다.

coverage에는 target player가 없으므로 항상 유효 corpus를 조회한다. AVAILABLE coverage row가 self-exclusion 뒤에도
analysis target의 AVAILABLE을 보장하지는 않는다. 실제 comparison은
`findBenchmarkExcludingPlayer(cohort, targetPuuid)`를 호출하고 그 결과를 적용해야 한다. smoke-test target은 30 samples와
10 players보다 여유가 있는 row를 선택하는 편이 좋지만, 별도의 운영 availability threshold를 도입하지는 않는다. 이
workflow는 representative 또는 운영 benchmark 수집이 아니라 convenience sampling과 pipeline 검증을 위한 것이다.

## 향후 플레이어 단위 benchmark

향후 플레이어 단위 benchmark는 먼저 각 동료 플레이어의 충분히 비교 가능한 경기를 집계하고, 그 플레이어 집계값의
분포를 만든다. 이는 다른 모집단이며 신중하게 정의한 동료 플레이어 백분위를 지원할 수 있다. v0.1의 경기 단위
`PeerBenchmark`는 이를 구현하지 않는다.
