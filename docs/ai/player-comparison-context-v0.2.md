# 플레이어 비교 컨텍스트 v0.2

`PlayerComparisonContext`는 두 benchmark scope에 대응하는 user-side statistical unit을 별도로 가진다. 이 model은
계속 provider-independent input이며 benchmark를 query하지 않는다.

```text
normalized target-player Matches
    -> per-match MatchParticipantMetricsCalculator metrics
    -> PlayerPositionStatistics
    -> PlayerChampionPositionStatistics
    -> PlayerComparisonContext
```

Context는 target PUUID, 현재 Solo `rankContext`, resolved player data, Match sample metadata를 유지한다. Target PUUID는
Backend 전용 정보이며 이후 aggregate self-exclusion에만 사용한다.

| Field | Statistical unit |
| --- | --- |
| `positionStatistics` | position별로 grouping한 모든 target-player observation이다. MIDDLE entry에는 Ahri, Akali 등 모든 MIDDLE game이 포함될 수 있다. |
| `championPositionStatistics` | `(championId, position)`별로 grouping한 target-player observation이다. Ahri MIDDLE과 Ahri TOP은 분리한다. |

두 statistic type 모두 games, wins, win rate, 7개 comparison metric을 제공한다. 새 grouping을 위해 KDA, per-minute
value, team-relative ratio를 재구현하지 않고, 같은 per-match metric source of truth인
`MatchParticipantMetricsCalculator`를 사용한다.

각 list는 games 내림차순, position, champion ID 순으로 deterministic하게 정렬한다. 단 champion ID는 해당하는
경우에만 사용한다. 최소 5 user games policy는 이 context에서 적용하지 않으며, `PlayerComparisonFeature`가
scope별로 독립 적용한다. 따라서 MIDDLE 12 game은 eligible이고 Ahri MIDDLE 3 game은
`INSUFFICIENT_USER_SAMPLE`인 상태가 가능하다.
