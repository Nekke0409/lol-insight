# 플레이어 분석 Feature v0.1

## 목적

`PlayerAnalysisFeature`는 LLM이 플레이어 대상 자연어 피드백을 생성하기 전에 준비되는
구조화된 provider 독립 입력 모델이다. Backend가 대상 플레이어를 선택하고 통계를 계산한
최근 Match 표본을 표현한다.

```text
Riot Match 데이터
    -> PlayerMatchHistoryLoader
    -> PlayerMatchStatisticsCalculator
    -> PlayerAnalysisFeatureBuilder
    -> PlayerAnalysisFeature
    -> 향후 LLM adapter
```

Feature에는 OpenAI, prompt, 특정 provider에 종속된 타입을 포함하지 않는다. 향후 LLM adapter가
직렬화와 prompt 구성을 담당한다.

## 전달 필드

| 구분 | 필드 | Backend 생성 기준 |
| --- | --- | --- |
| `player` | `gameName`, `tagLine` | 조회로 확인된 Riot 계정 식별 정보 |
| `sample` | `requestedCount`, `analyzedCount` | 요청한 Match 수, overall 통계에 사용된 대상 플레이어 Match 수 |
| `overall` | games, wins, losses, winRate, averageKills, averageDeaths, averageAssists, averageKda, averageCsPerMinute, averageGoldPerMinute, averageDamagePerMinute, averageVisionPerMinute, averageKillParticipation, averageDamageShare | 기존 `PlayerMatchStatisticsCalculator` 계산 결과 |
| `championStats` | championId, championName, games, wins, winRate, averageKda | 대상 participant를 champion별로 집계 |
| `positionStats` | position, games, wins, winRate, averageKda | 대상 participant를 position별로 집계 |

`overall`은 `PlayerMatchStatistics`를 그대로 사용하며, builder가 요약 통계를 다시 계산하지
않는다. champion과 position의 KDA는 경기별 `(kills + assists) / max(1, deaths)`의 산술평균이며,
기존 statistics calculator와 같은 공식을 사용한다.

champion과 position 목록은 결정적으로 정렬한다. `games` 내림차순을 우선 적용하고, 동률이면
championName 또는 position 오름차순으로 정렬한다. championName까지 같으면 championId를 최종
정렬 기준으로 사용한다.

## 의도적으로 제외한 항목

Builder는 사실과 파생 지표만 생성한다. "CS가 부족합니다"와 같은 결론이나 사용자 대상 피드백은
생성하지 않는다. 자연어 해석은 이후 LLM 연동의 책임이다.

이 버전에서는 provider API 호출, prompt, system prompt, vector/RAG 데이터, Database 영속화,
Feature cache, 새로운 rate limit 정책, public REST endpoint도 제외한다.

## Match Detail만으로 제공할 수 없는 항목

현재 Match Detail 표본만으로는 전체 rank 또는 tier 기준선을 만들 수 없다. 따라서 percentile,
rank 평균 CS, 특정 tier보다 뛰어나다는 비교 결과를 제공하지 않는다.

Timeline 의존 지표도 제외한다. 10분 CS, 15분 골드 차이, 초반 데스 비율, 첫 오브젝트 참여율은
Timeline 또는 추가 데이터가 필요하다.

## 향후 진입점

`PlayerAnalysisFeatureService.buildFeature(gameName, tagLine, start, count)`는
`PlayerMatchHistoryLoader`를 통해 기존 계정 조회, Match Detail cache, 제한된 detail fan-out,
partial result 정책을 재사용한다. 향후 LLM application flow는 이 service를 호출하고 반환된
Feature만 provider adapter에 전달하면 된다.
