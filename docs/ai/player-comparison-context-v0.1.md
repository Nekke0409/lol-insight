# 플레이어 비교 컨텍스트 v0.1

## 목적

`PlayerComparisonContext`는 `PlayerComparisonFeature`의 정확한 Peer Benchmark 비교에 쓰는 provider 독립적인
사용자 측 입력이다. 이는 benchmark 결과가 아니며, 사용자의 비교 가능 여부를 판단하지도 않는다.

```text
Riot ID
    -> Account-V1 -> PUUID
    -> 현재 League-V4 Ranked Solo rank
    -> 최근 정규화된 Match 표본
    -> championId + position 사용자 지표
    -> PlayerComparisonContext
    -> PlayerComparisonFeature
```

현재 구현은 의도적으로 `BenchmarkCohort` 생성과 `PeerBenchmarkQueryService.findBenchmark(...)` 호출 전 단계에서 멈춘다.

## 계약

| 필드 | 의미 |
| --- | --- |
| `player` | 표시와 provider 독립 식별에 쓰는 확인된 Riot ID(`gameName`, `tagLine`) |
| `targetPuuid` | `PlayerComparisonFeature`가 자신의 `BenchmarkSample` 행을 제외하는 데 쓰는 Backend 전용 대상 플레이어 식별자. LLM 입력이 아니다. |
| `rankContext` | 현재 `RANKED_SOLO_5x5`의 `tier`, `division`, `capturedAt`. 플레이어에게 Solo 항목이 없으면 `null` |
| `sample` | 요청한 Match 수와 cohort 통계에 사용한 대상 플레이어 Match 수 |
| `cohortStatistics` | 정확한 `championId + position`으로 묶고 결정적 순서로 정렬한 `PlayerCohortStatistics` 항목 |

`PlayerRankContext.capturedAt`은 현재 League-V4 조회를 완료한 시각이다. 어느 Match 시점의 과거 랭크가 아니며,
이 버전은 랭크 스냅샷을 저장하지 않는다.

비어 있는 League entries 응답, Flex만 포함한 entries 응답 또는 404는 정상적인 미랭크 결과이며 `rankContext`를
`null`로 설정한다. Rate limit 응답, 그 밖의 provider 응답, 전송 실패와 잘못된 provider 응답은 기존 Riot 오류 정책을
따라 전파하며 미랭크 결과로 바꾸지 않는다.

## Cohort 통계

각 `PlayerCohortStatistics`는 다음을 포함한다.

- `championId`, `position`, `games`, `wins`, `winRate`
- `averageKda`, `averageCsPerMinute`, `averageGoldPerMinute`, `averageDamagePerMinute`
- `averageVisionPerMinute`, `averageKillParticipation`, `averageDamageShare`

대상 PUUID의 참가자만 사용한다. `GRAGAS / TOP`과 `GRAGAS / JUNGLE`은 별도 항목으로 유지하며, 챔피언 요약이나
포지션 요약으로 이 정확한 그룹을 대체할 수 없다. 각 지표는 `MatchParticipantMetricsCalculator`가 계산한 경기별 값의
산술평균이다. 이 계산기는 KDA, 분당, 팀 상대 공식의 단일 기준이다. 결과는 games 내림차순, position과 champion ID
오름차순으로 정렬한다.

`games`는 항상 포함한다. v0.1은 이 계층에 `minimumUserGamesForComparison` 정책을 의도적으로 도입하지 않으며,
`PlayerComparisonFeature`가 컨텍스트 계약을 바꾸지 않고 그 정책을 명시적으로 적용한다.

## 경계

`PlayerAnalysisFeature`는 개인 요약 및 향후 LLM 자기 분석 모델로 유지한다. 여기에 랭크나 benchmark 비교 데이터를
추가하지 않는다. `PlayerComparisonContext`는 비교 준비가 끝난 별도 모델이다.

이 버전은 benchmark 조회, benchmark 차이, 백분위 랭크, “상위 X%” 주장, LLM 호출, 공개 비교 endpoint, benchmark 수집 변경,
RankSnapshot 저장, Timeline 데이터를 포함하지 않는다.
