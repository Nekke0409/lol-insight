# Player Match Statistics v0.1

## Scope

`GET /api/v1/players/{gameName}/{tagLine}/stats?start=0&count=20` returns aggregate statistics
for the player over recent Match-V5 details. `start` defaults to `0`; `count` defaults to `20` and
must be from `1` through `20`.

The sample is loaded through the existing flow:

```text
Riot ID -> Account-V1 -> PUUID -> Match IDs -> Match Detail -> domain Match
```

Only a participant whose `puuid` equals the Account-V1 PUUID is analyzed. A Detail that is not
found or has no matching participant is omitted, as in the recent-Matches API. No Riot DTO, PUUID,
or complete Match data is returned by this endpoint.

```json
{
  "player": { "gameName": "...", "tagLine": "..." },
  "sample": { "start": 0, "requestedCount": 20, "analyzedCount": 19 },
  "statistics": { "games": 19, "wins": 11, "losses": 8, "winRate": 0.5789 }
}
```

`analyzedCount` and `statistics.games` are the number of Match details containing the target PUUID.
An empty sample returns zero for every numeric statistic. The endpoint does not cache aggregate
statistics; Match Details continue to use the existing `match:detail` Redis cache.

## Aggregation

Each metric is first calculated per Match, then arithmetic-averaged across the analyzed Matches.
This applies to KDA, per-minute values, kill participation, and damage share. `wins`, `losses`, and
`games` are counts. `winRate` is a `0.0` through `1.0` ratio: `wins / games`.

| Field | Per-Match formula | Unit | Domain data source | Edge case |
| --- | --- | --- | --- | --- |
| `games` | count of target participants | games | `Match.participants.puuid` | `0` for no target participants |
| `wins` | count where `won` is true | games | `MatchParticipant.won` | `0` for no samples |
| `losses` | count where `won` is false | games | `MatchParticipant.won` | `0` for no samples |
| `winRate` | `wins / games` | ratio (0.0–1.0) | calculated counts | `0.0` when games is `0` |
| `averageKills` | mean of `kills` | kills/game | `MatchParticipant.kills` | `0.0` for no samples |
| `averageDeaths` | mean of `deaths` | deaths/game | `MatchParticipant.deaths` | `0.0` for no samples |
| `averageAssists` | mean of `assists` | assists/game | `MatchParticipant.assists` | `0.0` for no samples |
| `averageKda` | mean of `(kills + assists) / max(1, deaths)` | ratio | target participant KDA fields | deaths of `0` uses divisor `1` |
| `averageCsPerMinute` | mean of `(laneMinionKills + neutralMinionKills) / durationMinutes` | CS/min | target participant and `Match.duration` | non-positive duration returns `0.0` |
| `averageGoldPerMinute` | mean of `goldEarned / durationMinutes` | gold/min | target participant and `Match.duration` | non-positive duration returns `0.0` |
| `averageDamagePerMinute` | mean of `championDamageDealt / durationMinutes` | damage/min | target participant and `Match.duration` | non-positive duration returns `0.0` |
| `averageVisionPerMinute` | mean of `vision.score / durationMinutes` | vision/min | target participant and `Match.duration` | non-positive duration returns `0.0` |
| `averageKillParticipation` | mean of `(kills + assists) / teamKills` | ratio | participants with the target `teamId` | `0.0` when team kills is `0` |
| `averageDamageShare` | mean of `championDamageDealt / teamDamageToChampions` | ratio | participants with the target `teamId` | `0.0` when team damage is `0` |

`teamKills` is the sum of `kills` for every `Match.participants` entry with the target participant's
`teamId`. `teamDamageToChampions` is the equivalent sum of `championDamageDealt`. Opponent-team
participants are not included in either denominator. All zero-denominator cases explicitly produce
`0.0`; no statistic may return `NaN` or infinity.

## Non-goals

v0.1 excludes champion, lane, rank-average, timeline, and AI-specific statistics. Riot `challenges`
values are not used as a source of truth; the Backend calculates these values from the normalized
domain Match data.
