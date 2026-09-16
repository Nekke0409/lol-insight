# 플레이어 비교 Feature v0.2

`PlayerComparisonFeature`는 scope가 같은 user statistic과 peer benchmark를 연결한다. 각 statistical unit에 대해
별도의 `PlayerCohortComparison`을 만들며, 한 scope 결과를 다른 scope 결과로 대체하지 않는다.

```text
PlayerPositionStatistics
    -> POSITION BenchmarkCohort
    -> POSITION aggregate excluding target PUUID

PlayerChampionPositionStatistics
    -> CHAMPION_POSITION BenchmarkCohort
    -> CHAMPION_POSITION aggregate excluding target PUUID
```

모든 comparison은 `scope`, `position`, `CHAMPION_POSITION`에서만 존재하는 `championId`, `userGames`, status,
benchmark count와 AVAILABLE일 때만 존재하는 metric을 가진다. Benchmark cohort가 존재하면 comparison과 같은 scope,
position, champion identity여야 한다.

User sample heuristic은 기존과 같이 `games >= 5`이며 scope별로 적용한다. Benchmark availability도 target
self-exclusion 후 각 scope에 독립적으로 30 samples와 10 unique players를 요구한다. 다음 상태는 유효하며 기대한
결과다.

```text
POSITION / MIDDLE                  -> AVAILABLE
CHAMPION_POSITION / Ahri / MIDDLE -> BENCHMARK_INSUFFICIENT_SAMPLE
```

두 번째 결과를 첫 번째 결과로 fallback하지 않는다. 마찬가지로 eligible MIDDLE aggregate는 Ahri-only benchmark와
비교하지 않고, Ahri MIDDLE aggregate도 position-only benchmark와 비교하지 않는다.

`AVAILABLE` metric의 `playerValue`는 같은 scope의 user aggregate를 사용하며, Backend가 모든 difference를 계산한다.
Model은 계속 match-level benchmark comparison이며 player percentile, rank score, top-X-percent, skill label을
제공하지 않는다.

OpenAI analysis adapter는 `AVAILABLE`인 `POSITION`과 `CHAMPION_POSITION` comparison을 모두 scope와 함께 전달한다.
POSITION은 role-level evidence, CHAMPION_POSITION은 champion-specific evidence로만 설명하며, 두 scope는 fallback이
아니다. Unavailable comparison은 OpenAI metric evidence로 전달하지 않는다.
