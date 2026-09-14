# Peer Benchmark v0.1

## Scope

v0.1 `PeerBenchmark` is an on-demand PostgreSQL aggregate over stored `BenchmarkSample` rows. It is an
application/domain read model; no JPA entity is returned outside the persistence boundary. The query entry point is
`PeerBenchmarkQueryService.findBenchmark(cohort)`. There is no public REST endpoint in this version.

The exact cohort key is:

```text
region + queueId + tier + division + position + championId
```

`division` is deliberately included. A GOLD I sample is not a whole-GOLD benchmark, and it must not be presented as
one. Tier-wide aggregation needs a separate sampling policy that deliberately covers all divisions.

## User-side comparison input

`PlayerComparisonContext` is the implemented provider-independent user input for `PlayerComparisonFeature`. It contains the
user's current `RANKED_SOLO_5x5` tier/division and capture time, plus target-player Match metrics grouped by the same
`championId + position` dimensions. A player without a Ranked Solo entry has `rankContext = null`; this is not a Riot
failure. The context deliberately does not invoke `PeerBenchmarkQueryService`, construct a `BenchmarkCohort`, apply a
minimum-user-games policy, calculate a difference, or claim a percentile. Those responsibilities belong to
`PlayerComparisonFeatureService`; see [Player Comparison Context v0.1](../ai/player-comparison-context-v0.1.md) and
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
`benchmark_sample`. It does not load all samples into the JVM to sort them. v0.1 intentionally has no aggregate table,
materialized view, scheduler, retention job, or Redis aggregate cache. Those choices can be reconsidered after data
volume and query latency are measured.

`gameVersion` and `gameStartTimestamp` are already retained on each sample, but v0.1 does not filter on them. The
aggregate is not patch-aware, so old and new patches can mix as data accumulates. A production benchmark needs a
freshness window or patch-aware cohort strategy before that mixing becomes material.

## Future player-level benchmark

A future player-level benchmark first aggregates each peer player's sufficiently comparable matches, then forms a
distribution over those player aggregates. That is a different population and may support carefully defined peer-player
percentiles. It is not implemented by the match-level `PeerBenchmark` in v0.1.
