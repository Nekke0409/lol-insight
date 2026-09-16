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

`PeerBenchmarkQueryService.findBenchmark(cohort)`는 전체 corpus를 조회한다. Player comparison은
`findBenchmarkExcludingPlayer(cohort, targetPuuid)`를 사용한다. 두 operation 모두 PostgreSQL에서 `COUNT(*)`,
`COUNT(DISTINCT puuid)`, `AVG`, `percentile_cont`를 실행하며 raw sample을 JVM으로 가져오지 않는다.

`POSITION`에는 champion predicate가 없다. `CHAMPION_POSITION`에는 `champion_id` predicate가 있다. Excluding
query는 선택된 scope predicate에 `puuid <> :targetPuuid`를 추가하므로 sample count, unique-player count, mean,
median, p25, p75, p90, availability가 모두 exclusion 후 다시 계산된다.

각 aggregate는 KDA, CS/min, gold/min, damage/min, vision/min, kill participation, damage share를 제공한다. 이는
match-level observation의 distribution이며 player percentile이나 skill rating이 아니다.

## Availability와 coverage

기존 MVP heuristic을 두 scope에 독립적으로 적용한다.

- `minimumSampleCount = 30`
- `minimumUniquePlayerCount = 10`

`NO_DATA`, `INSUFFICIENT_SAMPLE`, `AVAILABLE`의 의미는 v0.1과 같다. Position aggregate나
champion-position aggregate에 대해 threshold를 완화하지 않았다.

`BenchmarkCohortCoverageQueryService`는 complete-corpus coverage를 두 scope 모두에 대해 보고한다. 반환되는
모든 row에는 `BenchmarkCohort`, 즉 `scope`, `position`, 선택적인 `championId`, count, availability,
`samplesNeeded`, `uniquePlayersNeeded`가 포함된다. Coverage는 analysis target을 제외하지 않으므로 AVAILABLE
coverage row가 target-excluded comparison의 AVAILABLE을 보장하지는 않는다.

## 한계

`POSITION`은 role-level baseline이다. Champion kit과 playstyle 차이가 metric distribution에 큰 영향을 줄 수
있으므로 champion-specific benchmark로 표현하지 않는다.

두 scope 모두 patch-aware하지 않고 heavy contributor가 match-level distribution에 영향을 줄 수 있으며, player
percentile, rank history, top-X-percent, player-level benchmark claim을 제공하지 않는다. Redis aggregate cache,
aggregate table, scheduler, 새 Riot seed request, public benchmark endpoint도 범위 밖이다.
