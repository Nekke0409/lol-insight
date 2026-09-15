# Player Comparison Context v0.1

## Purpose

`PlayerComparisonContext` is a provider-independent, user-side input for `PlayerComparisonFeature`'s exact Peer
Benchmark comparison.
It is not a benchmark result and does not decide whether the user is eligible for comparison.

```text
Riot ID
    -> Account-V1 -> PUUID
    -> current League-V4 Ranked Solo rank
    -> recent normalized Match sample
    -> championId + position user metrics
    -> PlayerComparisonContext
    -> PlayerComparisonFeature
```

The current implementation intentionally stops before creating a `BenchmarkCohort` or calling
`PeerBenchmarkQueryService.findBenchmark(...)`.

## Contract

| Field | Meaning |
| --- | --- |
| `player` | Resolved Riot ID (`gameName`, `tagLine`) for display and provider-independent identification. |
| `targetPuuid` | Backend-only target-player identity used by `PlayerComparisonFeature` to exclude own `BenchmarkSample` rows. It is not an LLM input. |
| `rankContext` | Current `RANKED_SOLO_5x5` `tier`, `division`, and `capturedAt`; `null` when the player has no Solo entry. |
| `sample` | Requested Match count and the target-player Match count used in cohort statistics. |
| `cohortStatistics` | Deterministically ordered `PlayerCohortStatistics` entries grouped by exact `championId + position`. |

`PlayerRankContext.capturedAt` is the time when the current League-V4 lookup was resolved. It is not an historical rank
at the time of any Match. This version does not persist a rank snapshot.

An empty League entries response, an entries response containing only Flex, or a 404 is a normal unranked result and
sets `rankContext` to `null`. Rate-limit responses, other provider responses, transport failures, and invalid provider
responses propagate through the existing Riot error policy; they are not converted to an unranked result.

## Cohort statistics

Each `PlayerCohortStatistics` contains:

- `championId`, `position`, `games`, `wins`, `winRate`
- `averageKda`, `averageCsPerMinute`, `averageGoldPerMinute`, `averageDamagePerMinute`
- `averageVisionPerMinute`, `averageKillParticipation`, `averageDamageShare`

Only the target PUUID's participant is used. `GRAGAS / TOP` and `GRAGAS / JUNGLE` remain separate entries; a champion
summary or position summary cannot replace this exact grouping. Each metric is the arithmetic mean of its per-Match
values from `MatchParticipantMetricsCalculator`, which remains the single source of truth for KDA, per-minute, and
team-relative formulas. Results are sorted by games descending, then position and champion ID ascending.

`games` is always included. v0.1 deliberately does not introduce a `minimumUserGamesForComparison` policy here;
`PlayerComparisonFeature` applies that policy explicitly without changing the context contract.

## Boundaries

`PlayerAnalysisFeature` remains the personal summary / future LLM self-analysis model. It is not expanded with rank or
benchmark comparison data. `PlayerComparisonContext` is the separate comparison-ready model.

This version excludes benchmark querying, benchmark differences, percentile ranks, “top X%” claims, LLM calls, a public
comparison endpoint, changes to benchmark collection, RankSnapshot persistence, and Timeline data.
