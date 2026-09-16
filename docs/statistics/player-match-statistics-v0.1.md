# 플레이어 경기 통계 v0.1

## 범위

`GET /api/v1/players/{gameName}/{tagLine}/stats?start=0&count=20`는 최근 Match-V5 상세 데이터로부터
플레이어의 집계 통계를 반환한다. `start`의 기본값은 `0`이고, `count`의 기본값은 `20`이며
`1`부터 `20` 사이여야 한다.

표본은 기존 흐름으로 불러온다.

```text
Riot ID -> Account-V1 -> PUUID -> Match IDs -> Match Detail -> domain Match
```

Account-V1 PUUID와 `puuid`가 같은 참가자만 분석한다. 찾을 수 없거나 일치하는 참가자가 없는 상세 데이터는
최근 경기 API와 마찬가지로 제외한다. 이 엔드포인트는 Riot DTO, PUUID 또는 전체 Match 데이터를 반환하지 않는다.

```json
{
  "player": { "gameName": "...", "tagLine": "..." },
  "sample": { "start": 0, "requestedCount": 20, "analyzedCount": 19 },
  "statistics": { "games": 19, "wins": 11, "losses": 8, "winRate": 0.5789 }
}
```

`analyzedCount`와 `statistics.games`는 대상 PUUID를 포함한 Match 상세 데이터의 수다. 빈 표본의 모든 수치 통계는
0을 반환한다. 이 엔드포인트는 집계 통계를 캐시하지 않으며, Match 상세 데이터는 기존 `match:detail` Redis 캐시를 계속 사용한다.

## 집계

각 지표는 먼저 경기별로 계산하고, 분석한 경기 전체의 산술평균을 구한다. 이는 KDA, 분당 수치, 킬 관여율,
피해 비율에 적용한다. `wins`, `losses`, `games`는 건수이며, `winRate`는 `wins / games`로 계산하는
`0.0`부터 `1.0` 사이의 비율이다.

| 필드 | 경기별 공식 | 단위 | 도메인 데이터 원천 | 예외 상황 |
| --- | --- | --- | --- | --- |
| `games` | 대상 참가자 수 | 경기 수 | `Match.participants.puuid` | 대상 참가자가 없으면 `0` |
| `wins` | `won`이 true인 건수 | 경기 수 | `MatchParticipant.won` | 표본이 없으면 `0` |
| `losses` | `won`이 false인 건수 | 경기 수 | `MatchParticipant.won` | 표본이 없으면 `0` |
| `winRate` | `wins / games` | 비율(0.0–1.0) | 계산된 건수 | games가 `0`이면 `0.0` |
| `averageKills` | `kills`의 평균 | 킬/경기 | `MatchParticipant.kills` | 표본이 없으면 `0.0` |
| `averageDeaths` | `deaths`의 평균 | 데스/경기 | `MatchParticipant.deaths` | 표본이 없으면 `0.0` |
| `averageAssists` | `assists`의 평균 | 어시스트/경기 | `MatchParticipant.assists` | 표본이 없으면 `0.0` |
| `averageKda` | `(kills + assists) / max(1, deaths)`의 평균 | 비율 | 대상 참가자의 KDA 필드 | deaths가 `0`이면 분모로 `1` 사용 |
| `averageCsPerMinute` | `(laneMinionKills + neutralMinionKills) / durationMinutes`의 평균 | CS/분 | 대상 참가자와 `Match.duration` | 경기 시간이 0 이하이면 `0.0` |
| `averageGoldPerMinute` | `goldEarned / durationMinutes`의 평균 | 골드/분 | 대상 참가자와 `Match.duration` | 경기 시간이 0 이하이면 `0.0` |
| `averageDamagePerMinute` | `championDamageDealt / durationMinutes`의 평균 | 피해량/분 | 대상 참가자와 `Match.duration` | 경기 시간이 0 이하이면 `0.0` |
| `averageVisionPerMinute` | `vision.score / durationMinutes`의 평균 | 시야 점수/분 | 대상 참가자와 `Match.duration` | 경기 시간이 0 이하이면 `0.0` |
| `averageKillParticipation` | `(kills + assists) / teamKills`의 평균 | 비율 | 대상 `teamId`의 참가자 | 팀 킬이 `0`이면 `0.0` |
| `averageDamageShare` | `championDamageDealt / teamDamageToChampions`의 평균 | 비율 | 대상 `teamId`의 참가자 | 팀 피해량이 `0`이면 `0.0` |

`teamKills`는 대상 참가자의 `teamId`와 같은 모든 `Match.participants` 항목의 `kills` 합계다.
`teamDamageToChampions`는 이에 해당하는 `championDamageDealt` 합계다. 상대 팀 참가자는 두 분모 모두에
포함하지 않는다. 분모가 0인 모든 경우는 명시적으로 `0.0`을 반환하며, 어떤 통계도 `NaN`이나 무한대를 반환하지 않는다.

## 제외 범위

v0.1은 챔피언, 라인, 랭크 평균, 타임라인, AI 전용 통계를 포함하지 않는다. Riot `challenges` 값은
기준 데이터로 사용하지 않으며, Backend가 정규화된 도메인 Match 데이터로 이 값을 계산한다.
