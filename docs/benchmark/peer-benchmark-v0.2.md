# 동료 벤치마크 v0.2

## 범위 모델

`PeerBenchmark`는 participant-level `BenchmarkSample` row를 PostgreSQL에서 on-demand로 aggregate한 결과다.
v0.2는 서로 독립적인 두 benchmark population을 가진다.

| Scope | Cohort key | 의미 |
| --- | --- | --- |
| `POSITION` | `region + queueId + tier + division + position` | 같은 role에서 수집된 match-level observation이다. champion mix를 의도적으로 유지한다. |
| `CHAMPION_POSITION` | `region + queueId + tier + division + position + championId` | 같은 champion으로 같은 role에서 수집된 match-level observation이다. |

`BenchmarkCohort`에는 항상 `scope`가 있다. `championId`는 `POSITION`에서만 null이고
`CHAMPION_POSITION`에서는 필수다. 이를 통해 의미가 다른 두 aggregate를 모호한 nullable filter 하나로
표현하지 않는다. GOLD I는 전체 GOLD population이 아니므로 두 cohort key 모두 division을 포함한다.

## Aggregate와 self-exclusion

`PeerBenchmarkQueryService.findBenchmark(cohort)`는 유효 표본 corpus를 조회한다. Player comparison은
`findBenchmarkExcludingPlayer(cohort, targetPuuid)`를 사용한다. 두 operation 모두 PostgreSQL에서 `COUNT(*)`,
`COUNT(DISTINCT puuid)`, `AVG`, `percentile_cont`를 실행하며 raw sample을 JVM으로 가져오지 않는다.

`POSITION`에는 champion predicate가 없다. `CHAMPION_POSITION`에는 `champion_id` predicate가 있다. Excluding
query는 유효기간과 선택된 scope predicate에 `puuid <> :targetPuuid`를 추가하므로 sample count, unique-player count, mean,
median, p25, p75, p90, availability가 모두 exclusion 후 다시 계산된다.

각 aggregate는 KDA, CS/min, gold/min, damage/min, vision/min, kill participation, damage share를 제공한다. 이는
match-level observation의 distribution이며 player percentile이나 skill rating이 아니다.

## 유효 표본 기간 v0.1

Peer Benchmark의 기본 유효기간은 `BENCHMARK_SAMPLE_MAX_AGE=30d`다. `Clock`에서 조회 기준 시각 `asOf`를 한 번 얻고,
다음 rolling duration window를 명시적으로 PostgreSQL query에 전달한다.

```text
fromInclusive = asOf - maxSampleAge
toExclusive = asOf
valid gameStartTimestamp ∈ [fromInclusive, toExclusive)
```

30일은 UTC 기준 달력 날짜가 아니라 정확히 30 × 24시간이다. 하한과 정확히 같은 경기는 포함하고, 상한과 정확히 같은 경기와
미래 경기는 제외한다. 기간 판정은 오직 `gameStartTimestamp`로 한다. `collectedAt`이 최근이거나 `rankCapturedAt`이 최근이어도
과거 경기 sample은 유효하지 않다.

`POSITION`, `CHAMPION_POSITION`, self-exclusion aggregate 및 coverage `GROUP BY` 모두 동일한 SQL predicate를 사용한다.
한 Player comparison 요청은 window를 한 번만 만들어 모든 cohort에 재사용한다. repository는 JVM 현재 시각이나 SQL `now()`를
독자적으로 계산하지 않는다.

이 정책은 query-time exclusion이며 물리 retention이 아니다. 오래된 row를 삭제·수정하거나 collector가 `collectedAt`을 갱신해
재활성화하지 않는다. aggregate table, materialized view, Redis aggregate cache, 별도 expiry column도 추가하지 않는다.

## Availability와 coverage

기존 MVP heuristic을 두 scope에 독립적으로 적용한다.

- `minimumSampleCount = 30`
- `minimumUniquePlayerCount = 10`

`NO_DATA`, `INSUFFICIENT_SAMPLE`, `AVAILABLE`의 의미는 v0.1과 같다. Position aggregate나
champion-position aggregate에 대해 threshold를 완화하지 않았다.

`BenchmarkCohortCoverageQueryService`는 유효 표본 coverage를 두 scope 모두에 대해 보고한다. 반환되는
모든 row에는 `BenchmarkCohort`, 즉 `scope`, `position`, 선택적인 `championId`, count, availability,
`samplesNeeded`, `uniquePlayersNeeded`가 포함된다. Coverage는 analysis target을 제외하지 않으므로 AVAILABLE
coverage row가 target-excluded comparison의 AVAILABLE을 보장하지는 않는다. 유효기간 안에 행이 없으면 `NO_DATA`이며,
전체 저장 기간에 과거 행이 존재한다는 뜻을 포함하지 않는다. `GROUP BY`는 0건 cohort를 반환하지 않으므로, 표시를 위해 알려진
cohort를 보완하는 caller는 이를 0건 `NO_DATA`로 처리한다.

## 한계

`POSITION`은 role-level baseline이다. Champion kit과 playstyle 차이가 metric distribution에 큰 영향을 줄 수
있으므로 champion-specific benchmark로 표현하지 않는다.

## Coverage Replenishment v0.1

`BenchmarkReplenishmentTickService`는 동일한 rolling window에서 `POSITION` coverage만 읽고, TOP, JUNGLE,
MIDDLE, BOTTOM, UTILITY 다섯 position이 모두 AVAILABLE일 때만 cohort를 healthy로 판단한다. `GROUP BY`에 없는
position은 0 samples / 0 unique players의 `NO_DATA`로 보완한다. `CHAMPION_POSITION` sparse 상태는 trigger도 fallback도
아니며, 수집 결과로 늘어나는 opportunistic scope다.

지원 cohort는 `BENCHMARK_REPLENISHMENT_COHORTS`의 명시 allowlist(`TIER:DIVISION`, 기본 `GOLD:I`)다. 현재
League-V4 page discovery가 검증된 IRON~DIAMOND와 I~IV만 허용하며, 지원하지 않는 tier의 분석 요청이 인접 tier
benchmark를 사용하거나 수집을 시작하지 않는다.

tick은 `BENCHMARK_REPLENISHMENT_MAX_COHORTS_PER_TICK=1`, page 1개, player 10명, player당 match 5개라는
작은 budget만 사용한다. 부족한 coverage를 while loop로 채우지 않고 다음 tick에서 동일 window 기준으로 다시 판정한다.
선택 우선순위는 `NO_DATA` position 수, unavailable POSITION 수, sample/unique-player deficit, tier/division의
결정적인 tie-break 순서다.

### 제한된 seed 후보 선택

manual seed와 replenishment는 모두 `BenchmarkSeedService`의 같은 후보 선택 경로를 사용한다. discovery는 요청한 유한한
1-based page 범위를 순서대로 읽고 첫 빈 page에서 중단한다. page 내부 PUUID는 정렬한 뒤 전체 page 범위에서 중복을 제거하지만,
`playerLimit`으로 discovery를 조기 종료하지 않는다. 즉 `pageCount`는 Match API 호출 예산이 아니라 후보를 비교할 허용 범위를
정한다. `pagesProcessed`는 실제 Riot discovery 응답을 받은 page 수이며, 빈 page와 discovery 429/cooldown의 cursor 의미는
기존과 같다.

정상 discovery 뒤에는 후보 PUUID 전체에 대해 한 번의 PostgreSQL `GROUP BY puuid` query를 실행한다. scope는
`region + queueId + tier + division`이고, position/champion은 필터에 넣지 않는다. 유효 표본은 aggregate/coverage와 같은
`game_start_timestamp`의 `[fromInclusive, toExclusive)` window만 사용한다. query 결과가 없는 후보만 0건으로 해석하고,
빈 후보 묶음은 DB query를 실행하지 않으며 DB 오류를 0건 결과로 바꾸지 않는다. direct seed는 시작 시 window를 한 번 만들고,
replenishment는 coverage를 계산한 그 window를 request에 전달한다.

`BenchmarkSeedCandidateSelector`는 `validSampleCount ASC`, `PUUID ASC`로 결정적으로 정렬한 뒤에만 `playerLimit`을
적용한다. 따라서 0건 후보가 먼저 오고, 그 수가 부족하면 양수 중 표본이 적은 후보로 남은 자리를 채운다. 실제 collector에는
선택된 player만 전달되므로 후보를 더 많이 비교해도 Match 목록·Detail 호출 수는 기존 `playerLimit`을 넘지 않는다.
`BenchmarkSeedResult`는 raw discovery 수, 고유 후보 수(`candidatePlayers`), 실제 선택·collector 입력 수(`uniquePlayers`),
선택된 0건/양수 유효 표본 후보 수를 분리해 보고한다.

이 선택은 0건 후보가 최근 경기를 제공한다는 보장, TOP player를 직접 찾는 정책, 무작위/대표 표본 추출, page 내부 모든 후보의
언젠가 처리, 429 예방 또는 실제 HTTP 호출 수 절감을 제공하지 않는다. 만료 표본만 가진 비활성 player도 다시 0건 후보가 될 수
있고, 모든 후보가 0건이면 PUUID 순서가 기존 선택과 같을 수 있다. 다른 tier/division으로 저장된 row와 `(match_id, puuid)`
중복 저장 방지 정책의 기존 제약도 그대로다. 결정 근거는 [ADR-018](../adr/018-prioritize-benchmark-collection-candidates-by-valid-sample-count.md)을 따른다.

각 cohort의 `benchmark_replenishment_cursor`는 다음 discovery page를 저장한다. 정상적으로 discovery가 끝난 실제 page 수만큼
다음 page로 전진하고, 빈 page는 1로 wrap한다. discovery 자체가 local cooldown 또는 Riot 429로 중단되면 마지막 시도 시각만
갱신하고 같은 page를 보존한다. discovery 뒤의 Match collection 429는 새 page cursor를 유지하되, 해당 tick의 이후 cohort 수집을 중단한다.

스케줄러는 `BENCHMARK_REPLENISHMENT_ENABLED=false`가 기본이며 `BENCHMARK_REPLENISHMENT_INTERVAL`(기본 `24h`)로
opt-in 한다. `RUN_BENCHMARK_REPLENISHMENT_ONCE=true`는 scheduler `enabled`와 독립적인 startup one-tick 검증 opt-in이며,
실행 결과를 민감정보 없는 JSON 요약 한 줄로 application log에 출력한다. public replenishment endpoint, request-time seed,
automatic retry, sleep, distributed lock은 없다. process 내부 guard만 동일 JVM의 중복 tick을 막으므로 multi-instance 운영에는
shared claim/lock 정책이 별도로 필요하다. metric은 outcome만 tag로 사용하며 cohort, PUUID, Riot ID, Match ID를 tag로 사용하지
않는다. run-once 요약에는 configured cohort, query window, POSITION coverage, cursor와 collection count만 포함하고 PUUID,
Riot ID, Match ID, API key, raw response는 포함하지 않는다.

두 scope 모두 patch-aware하지 않고 유효기간 안에서도 여러 `gameVersion`이 섞일 수 있다. `rankCapturedAt`은 수집 시점에
확인한 rank이며 경기 시작 시점 rank history가 아니다. heavy contributor가 match-level distribution에 영향을 줄 수 있으며,
player percentile, rank history, top-X-percent, player-level benchmark claim을 제공하지 않는다. 사용자의 최근 Ranked Solo
최대 20경기 분석 범위는 peer의 30일 유효기간과 별도다. Redis aggregate cache, aggregate table, demand-driven cohort
activation, public benchmark endpoint는 범위 밖이다.

## 공통 Benchmark Preflight

`BenchmarkPreflightManualSmokeTest`는 특정 티어/포지션을 코드에 고정하지 않는 opt-in live 진단이다. 기본적으로
비활성화되어 있으며, 대상의 최근 Ranked Solo 최대 20경기 context와 실제 Solo rank를 읽고, 지정 position의 사용자 경기 수,
전체 benchmark, self-excluded benchmark를 같은 query window에서 확인한다. sample, AnalysisJob, Automation, Agent와 OpenAI
호출은 시작하지 않는다. 단, context를 만들 때 Riot API 또는 기존 cache를 읽을 수 있다.

실행 전에 다음 환경 변수를 모두 명시한다. tier/division은 현재 League-V4 discovery 지원 범위(`IRON`~`DIAMOND`, `I`~`IV`),
position은 `TOP`, `JUNGLE`, `MIDDLE`, `BOTTOM`, `UTILITY`만 허용하며 누락·빈 값·지원하지 않는 값은 Riot 조회 전에 실패한다.

```powershell
$env:RUN_BENCHMARK_PREFLIGHT = 'true'
$env:RIOT_API_KEY = 'your-riot-api-key'
$env:TARGET_GAME_NAME = 'example-game-name'
$env:TARGET_TAG_LINE = 'KR1'
$env:BENCHMARK_PREFLIGHT_EXPECTED_TIER = 'EMERALD'
$env:BENCHMARK_PREFLIGHT_EXPECTED_DIVISION = 'IV'
$env:BENCHMARK_PREFLIGHT_POSITION = 'TOP'
.\gradlew.bat test --tests '*BenchmarkPreflightManualSmokeTest'
```

이 test는 실제 rank/expected rank 일치, 사용자 position 표본, self-excluded availability, 최종 `READY` 또는 `NOT_READY`와
사유를 별도로 출력한다. self-exclusion count 교차 검증이 통과해도 `0 - 0 = 0`만으로 READY가 되지 않으며, 기존 comparison의
사용자 최소 경기 수와 benchmark availability policy를 그대로 사용한다. 과거
`RUN_EMERALD_TOP_BENCHMARK_PREFLIGHT` flag는 더 이상 어떤 test도 활성화하지 않는다. preflight Spring context에서는
Automation/bootstrap, replenishment scheduler/run-once, Agent를 명시적으로 false로 고정한다.
