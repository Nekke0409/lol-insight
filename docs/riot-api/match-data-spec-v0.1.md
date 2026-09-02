# Riot Match Data Specification v0.1

> 대상 프로젝트: LOL Stats & AI Platform  
> 대상 API: `GET /lol/match/v5/matches/{matchId}`  
> 기준 샘플: `match-by-match-id.json` (`KR_8361620721`)  
> 문서 버전: `v0.1`  
> 작성 목적: Riot Match-V5 응답 중 우리 서비스에서 사용할 데이터를 선별하고, 전적 조회 / 통계 계산 / AI 분석 / DB 저장 기준을 정의한다.

---

## 1. 문서 목적

Riot Match-V5의 Match Detail 응답은 한 경기의 매우 많은 데이터를 포함한다.

이 프로젝트에서는 Riot API 응답 구조를 그대로 DB나 서비스 모델로 복제하지 않는다. 대신 다음 목적에 따라 필요한 데이터를 선별한다.

1. 전적 조회 화면에 필요한 데이터
2. 백엔드에서 통계 계산에 필요한 데이터
3. 향후 AI 플레이 분석에 필요한 데이터
4. DB에 영속화할 가치가 있는 데이터
5. 현재 MVP에서는 사용하지 않을 데이터

이 문서는 **Riot Match API의 전체 공식 스펙을 복제하는 문서가 아니라, 우리 서비스가 어떤 데이터를 사용할지 결정하는 프로젝트 내부 데이터 명세**다.

---

## 2. 설계 원칙

### 2.1 Riot Raw Response와 서비스 모델을 분리한다

```text
Riot Match API
      ↓
RiotMatchResponse (외부 API DTO)
      ↓
Mapper / Feature Extractor
      ↓
Service Model / Derived Feature
      ↓
DB / API Response / AI Input
```

Riot API DTO는 외부 시스템의 응답을 받기 위한 모델이고, 서비스 모델은 우리 서비스가 실제로 사용하는 데이터 구조다.

둘을 동일하게 만들지 않는다.

---

### 2.2 Raw Data를 Source of Truth로 사용한다

Riot이 직접 제공하는 기본 값이 있으면 해당 Raw Data를 원본 데이터로 취급한다.

예:

```text
kills
deaths
assists
goldEarned
totalMinionsKilled
neutralMinionsKilled
totalDamageDealtToChampions
visionScore
```

KDA, CS/min, Damage Share 등은 Raw Data를 기반으로 Backend에서 계산한다.

---

### 2.3 LLM에 Riot Raw JSON을 직접 전달하지 않는다

AI 분석 구조는 다음을 기본으로 한다.

```text
Riot Match Detail
        +
Riot Match Timeline
        ↓
Backend 데이터 정제
        ↓
Derived Feature 계산
        ↓
AnalysisFeature 생성
        ↓
LLM
        ↓
자연어 피드백
```

LLM은 통계 계산 엔진이 아니라, Backend에서 계산된 구조화 데이터를 해석하고 설명하는 역할로 제한한다.

---

## 3. 데이터 분류 기준

| 분류 | 의미 |
|---|---|
| `CORE` | 전적 조회 MVP에서 핵심적으로 사용 |
| `ANALYSIS` | 통계 계산 또는 AI 분석에 유용 |
| `DEFER` | 향후 사용할 수 있으나 현재 MVP에서는 제외 |
| `IGNORE` | 현재 프로젝트에서 사용하지 않음 |

DB 저장 여부는 다음과 같이 표시한다.

| 표시 | 의미 |
|---|---|
| `O` | 저장 권장 |
| `△` | 요구사항에 따라 선택 |
| `X` | 현재 저장하지 않음 |

---

# 4. Match 전체 구조

실제 Match Detail 응답의 주요 구조는 다음과 같다.

```text
Match
├── metadata
│   ├── dataVersion
│   ├── matchId
│   └── participants[]
│
└── info
    ├── 경기 기본 정보
    ├── participants[]
    └── teams[]
```

일반적인 Summoner's Rift 5:5 경기에서는 `participants`에 10명의 플레이어가 존재하고 `teams`에 두 팀이 존재한다.

---

# 5. Metadata

## 5.1 필드 명세

| JSON Path | 타입 | 의미 | 분류 | DB 저장 | 비고 |
|---|---|---|---|---|---|
| `metadata.dataVersion` | String | Match 데이터 버전 | DEFER | X | 샘플 값 `"2"` |
| `metadata.matchId` | String | Riot Match 고유 ID | CORE | O | Match 식별자의 기준 |
| `metadata.participants[]` | String[] | 참가자 PUUID 목록 | DEFER | X | `info.participants[].puuid`와 중복 |

## 5.2 결정

### Match 식별자

```text
metadata.matchId
```

를 우리 서비스의 Match 식별 기준으로 사용한다.

예:

```text
KR_8361620721
```

DB에서는 문자열 형태로 저장한다.

---

# 6. Info - 경기 기본 정보

## 6.1 필드 명세

| 필드 | 타입 | 의미 | 분류 | DB 저장 |
|---|---|---|---|---|
| `endOfGameResult` | String | 경기 종료 결과 | ANALYSIS | O |
| `gameCreation` | Long | 경기 생성 Timestamp | DEFER | X |
| `gameStartTimestamp` | Long | 경기 시작 Timestamp(ms) | CORE | O |
| `gameEndTimestamp` | Long | 경기 종료 Timestamp(ms) | CORE | O |
| `gameDuration` | Long | 경기 시간(초) | CORE | O |
| `gameId` | Long | Riot 내부 Game ID | DEFER | X |
| `gameMode` | String | 게임 모드 | CORE | O |
| `gameName` | String | Riot 내부 게임 이름 | IGNORE | X |
| `gameType` | String | 게임 타입 | DEFER | X |
| `gameVersion` | String | 게임 버전 | CORE | O |
| `mapId` | Int | 맵 ID | CORE | O |
| `platformId` | String | Riot Platform | CORE | O |
| `queueId` | Int | Queue ID | CORE | O |
| `tournamentCode` | String | Tournament 관련 값 | IGNORE | X |

## 6.2 샘플 경기

```text
matchId      = KR_8361620721
platformId   = KR
queueId      = 420
gameMode     = CLASSIC
mapId        = 11
gameVersion  = 16.17.810.4348
gameDuration = 1557
```

## 6.3 gameVersion 정책

DB에는 원본 문자열을 그대로 저장한다.

```text
16.17.810.4348
```

서비스에서 필요하면 다음처럼 major patch를 계산한다.

```text
16.17.810.4348
↓
16.17
```

원본을 손실하지 않기 위해 DB에는 가공된 버전 대신 Riot 원본을 저장한다.

---

# 7. Participant - 플레이어 식별 정보

## 7.1 필드 명세

| 필드 | 의미 | 분류 | DB 저장 |
|---|---|---|---|
| `participantId` | Match 내부 참가자 번호 | CORE | O |
| `puuid` | Riot Account 고유 식별자 | CORE | O |
| `riotIdGameName` | Riot ID Game Name | CORE | △ |
| `riotIdTagline` | Riot ID Tag Line | CORE | △ |
| `summonerId` | LoL Summoner ID | DEFER | X |
| `summonerLevel` | 경기 당시 Summoner Level | DEFER | X |
| `profileIcon` | Profile Icon ID | DEFER | X |
| `summonerName` | 구 Summoner Name 계열 필드 | IGNORE | X |

## 7.2 플레이어 식별 정책

플레이어 식별의 기준은 PUUID로 한다.

```text
식별
PUUID

표시
riotIdGameName + riotIdTagline
```

Riot ID는 변경 가능성이 있으므로 내부 Primary Identifier로 사용하지 않는다.

## 7.3 Riot ID Snapshot 저장 여부

두 가지 선택지가 있다.

### A. Match 당시 Riot ID 저장

장점:

- 과거 경기 당시 표시명을 보존할 수 있다.
- Match 자체가 완전한 Snapshot이 된다.

단점:

- 중복 데이터가 발생한다.

### B. Player의 최신 Riot ID만 사용

장점:

- 데이터 중복이 적다.
- 항상 최신 이름이 표시된다.

단점:

- 과거 경기 당시 Riot ID를 알 수 없다.

### v0.1 결정

`riotIdGameName`, `riotIdTagline`은 **선택 저장(△)** 으로 남겨둔다.

MVP에서는 최신 Player 정보를 사용하는 방향도 충분하다.

---

# 8. Participant - Team / Position

## 8.1 필드 명세

| 필드 | 의미 | 분류 | DB 저장 |
|---|---|---|---|
| `teamId` | 팀 식별자 | CORE | O |
| `win` | 승패 | CORE | O |
| `teamPosition` | 팀 내 포지션 | CORE | O |
| `individualPosition` | 개인 포지션 | ANALYSIS | △ |
| `positionAssignedByMatchmaking` | 매칭 과정에서 할당된 포지션 | DEFER | X |
| `lane` | Riot Lane 값 | DEFER | X |
| `role` | Riot Role 값 | DEFER | X |
| `selectedRolePreferences` | Queue 진입 시 Role preference 계열 값 | IGNORE | X |

## 8.2 Position 대표값 결정

샘플에서는 다음과 같이 값이 일치하지 않는 사례가 존재했다.

```text
teamPosition                  = MIDDLE
individualPosition            = MIDDLE
positionAssignedByMatchmaking = MIDDLE
lane                          = TOP
```

따라서 `lane`을 사용자에게 표시할 대표 Position으로 사용하지 않는다.

### v0.1 결정

```text
대표 Position = teamPosition
```

예상 값:

```text
TOP
JUNGLE
MIDDLE
BOTTOM
UTILITY
```

다른 Match 샘플을 추가 분석한 후 이 정책은 재검증한다.

---

# 9. Participant - Champion

| 필드 | 의미 | 분류 | DB 저장 |
|---|---|---|---|
| `championId` | Champion ID | CORE | O |
| `championName` | Champion Name | CORE | △ |
| `champLevel` | 경기 종료 시 Champion Level | CORE | O |
| `champExperience` | 획득 경험치 | ANALYSIS | O |
| `championTransform` | 특정 Champion 상태 관련 필드 | DEFER | X |

## 9.1 Champion 식별 정책

Champion의 내부 식별은 `championId`를 기준으로 한다.

```text
championId
    ↓
Static Data
    ↓
Champion Name / Image
```

`championName`은 API 응답에는 존재하지만 정적 데이터와 중복될 수 있으므로 선택 저장으로 둔다.

---

# 10. Participant - KDA / Combat Result

| 필드 | 의미 | 분류 | DB 저장 |
|---|---|---|---|
| `kills` | Kill 수 | CORE | O |
| `deaths` | Death 수 | CORE | O |
| `assists` | Assist 수 | CORE | O |
| `doubleKills` | Double Kill | DEFER | X |
| `tripleKills` | Triple Kill | DEFER | X |
| `quadraKills` | Quadra Kill | DEFER | X |
| `pentaKills` | Penta Kill | CORE | O |
| `largestKillingSpree` | 최대 Killing Spree | ANALYSIS | △ |
| `largestMultiKill` | 최대 Multi Kill | DEFER | X |
| `killingSprees` | Killing Spree 관련 값 | DEFER | X |

## 10.1 KDA 계산 정책

KDA는 Raw 값이 아니라 Derived Feature로 계산한다.

```text
KDA = (kills + assists) / max(deaths, 1)
```

예:

```text
2 / 11 / 6
↓
(2 + 6) / 11
≈ 0.727
```

`challenges.kda`도 존재하지만 핵심 통계는 Backend에서 다시 계산할 수 있도록 한다.

---

# 11. Participant - CS / Gold / Growth

| 필드 | 의미 | 분류 | DB 저장 |
|---|---|---|---|
| `totalMinionsKilled` | Lane Minion Kill 수 | CORE | O |
| `neutralMinionsKilled` | Neutral Monster Kill 수 | CORE | O |
| `goldEarned` | 획득 Gold | CORE | O |
| `goldSpent` | 사용 Gold | ANALYSIS | O |
| `champExperience` | 획득 Experience | ANALYSIS | O |
| `totalAllyJungleMinionsKilled` | 아군 Jungle Minion 관련 값 | ANALYSIS | △ |
| `totalEnemyJungleMinionsKilled` | 적 Jungle Minion 관련 값 | ANALYSIS | △ |

## 11.1 Total CS

```text
totalCs =
    totalMinionsKilled
    + neutralMinionsKilled
```

특히 Jungler는 `totalMinionsKilled`만으로 CS를 평가하면 안 된다.

## 11.2 CS per Minute

```text
gameMinutes = gameDuration / 60.0

csPerMinute =
    totalCs / gameMinutes
```

---

# 12. Participant - Damage

| 필드 | 의미 | 분류 | DB 저장 |
|---|---|---|---|
| `totalDamageDealtToChampions` | Champion 대상 총 Damage | CORE | O |
| `physicalDamageDealtToChampions` | Champion 대상 Physical Damage | ANALYSIS | O |
| `magicDamageDealtToChampions` | Champion 대상 Magic Damage | ANALYSIS | O |
| `trueDamageDealtToChampions` | Champion 대상 True Damage | ANALYSIS | O |
| `totalDamageTaken` | 받은 총 Damage | CORE | O |
| `physicalDamageTaken` | 받은 Physical Damage | ANALYSIS | △ |
| `magicDamageTaken` | 받은 Magic Damage | ANALYSIS | △ |
| `trueDamageTaken` | 받은 True Damage | ANALYSIS | △ |
| `damageSelfMitigated` | 방어 / 감소된 Damage 관련 값 | ANALYSIS | O |
| `totalDamageDealt` | 모든 대상에게 가한 전체 Damage | DEFER | X |

## 12.1 Damage 분석 기준

플레이어 전투 기여도를 평가할 때는 다음 값을 우선 사용한다.

```text
totalDamageDealtToChampions
```

`totalDamageDealt`는 Minion, Monster, Structure 등을 포함하는 값이므로 Champion Damage와 혼동하지 않는다.

---

# 13. Participant - Heal / Shield / Crowd Control

| 필드 | 의미 | 분류 | DB 저장 |
|---|---|---|---|
| `totalHeal` | 총 Heal | ANALYSIS | △ |
| `totalHealsOnTeammates` | 아군에게 제공한 Heal | ANALYSIS | O |
| `totalDamageShieldedOnTeammates` | 아군에게 제공한 Shield | ANALYSIS | O |
| `timeCCingOthers` | 적에게 CC를 가한 시간 관련 값 | ANALYSIS | O |
| `totalTimeCCDealt` | 전체 CC 관련 값 | DEFER | X |

## 13.1 역할별 분석

Support, Tank, Enchanter 계열 Champion은 Damage만으로 기여도를 평가하지 않는다.

향후 Position / Champion Role에 따라 다음 지표의 가중치를 다르게 적용할 수 있다.

```text
Damage
Heal
Shield
CC
Vision
Kill Participation
```

---

# 14. Participant - Vision

| 필드 | 의미 | 분류 | DB 저장 |
|---|---|---|---|
| `visionScore` | Vision Score | CORE | O |
| `wardsPlaced` | 설치 Ward 수 | CORE | O |
| `wardsKilled` | 제거 Ward 수 | CORE | O |
| `visionWardsBoughtInGame` | Control Ward 구매 관련 값 | ANALYSIS | O |
| `detectorWardsPlaced` | Detector Ward 설치 관련 값 | ANALYSIS | △ |
| `sightWardsBoughtInGame` | Sight Ward 관련 값 | DEFER | X |

## 14.1 유사 필드 주의

Participant에는:

```text
detectorWardsPlaced
```

가 존재하고 Challenges에는:

```text
controlWardsPlaced
```

가 별도로 존재한다.

샘플에서 두 값이 항상 동일하지 않으므로 **같은 의미라고 가정해서 하나로 합치지 않는다.**

---

# 15. Participant - Objectives

| 필드 | 의미 | 분류 | DB 저장 |
|---|---|---|---|
| `damageDealtToObjectives` | Objective 대상 Damage | ANALYSIS | O |
| `damageDealtToEpicMonsters` | Epic Monster 대상 Damage | ANALYSIS | O |
| `damageDealtToTurrets` | Turret 대상 Damage | ANALYSIS | O |
| `damageDealtToBuildings` | Structure 대상 Damage | DEFER | X |
| `turretKills` | Turret Kill | CORE | O |
| `turretTakedowns` | Turret Takedown | ANALYSIS | O |
| `inhibitorTakedowns` | Inhibitor Takedown | ANALYSIS | O |
| `nexusTakedowns` | Nexus Takedown | DEFER | X |
| `dragonKills` | Dragon Kill 관련 값 | ANALYSIS | △ |
| `baronKills` | Baron Kill 관련 값 | ANALYSIS | △ |
| `objectivesStolen` | Objective Steal | ANALYSIS | O |
| `objectivesStolenAssists` | Objective Steal Assist | DEFER | X |

## 15.1 Match Detail의 한계

Match Detail에서는 다음과 같은 종료 시점 누적값은 확인할 수 있다.

```text
Dragon 관여
Baron 관여
Turret Damage
Objective Damage
```

하지만 정확한 이벤트 시각과 상황은 알 수 없다.

다음과 같은 분석에는 Timeline API가 필요하다.

```text
14:23 Dragon Fight
18:10 Baron Spawn 직전 Death
10분 시점 Gold / CS
특정 Objective 전후 Position
```

---

# 16. Participant - Items

## 16.1 필드

```text
item0
item1
item2
item3
item4
item5
item6
```

| 필드 | 의미 | 분류 | DB 저장 |
|---|---|---|---|
| `item0 ~ item5` | 종료 시 Item 슬롯 | CORE | O |
| `item6` | Trinket 계열 슬롯 | CORE | O |
| `itemsPurchased` | Item 구매 횟수 | DEFER | X |
| `consumablesPurchased` | Consumable 구매 횟수 | DEFER | X |

## 16.2 서비스 모델

Riot DTO에서는 원본 구조를 그대로 받을 수 있다.

```text
item0
item1
...
item6
```

서비스 모델에서는 배열 또는 Collection 형태로 변환할 수 있다.

```text
items = [
  item0,
  item1,
  item2,
  item3,
  item4,
  item5,
  item6
]
```

Item ID를 기반으로 정적 데이터에서 이름과 이미지를 조회한다.

---

# 17. Participant - Summoner Spells

| 필드 | 의미 | 분류 | DB 저장 |
|---|---|---|---|
| `summoner1Id` | 첫 번째 Summoner Spell ID | CORE | O |
| `summoner2Id` | 두 번째 Summoner Spell ID | CORE | O |
| `summoner1Casts` | 첫 번째 Spell 사용 횟수 | DEFER | X |
| `summoner2Casts` | 두 번째 Spell 사용 횟수 | DEFER | X |

Display 정보는 Static Data를 통해 해결한다.

```text
Spell ID
↓
Name
Image
```

---

# 18. Participant - Runes / Perks

## 18.1 구조

```text
perks
├── statPerks
│   ├── defense
│   ├── flex
│   └── offense
│
└── styles[]
    ├── style
    ├── description
    └── selections[]
        ├── perk
        ├── var1
        ├── var2
        └── var3
```

## 18.2 분류

| 필드 | 분류 | DB 저장 |
|---|---|---|
| `styles[].style` | CORE | O |
| `styles[].selections[].perk` | CORE | O |
| `statPerks.defense` | CORE | O |
| `statPerks.flex` | CORE | O |
| `statPerks.offense` | CORE | O |
| `description` | DEFER | X |
| `var1` | DEFER | X |
| `var2` | DEFER | X |
| `var3` | DEFER | X |

전적 화면에서는 어떤 Rune을 선택했는지가 가장 중요하므로 `perk` ID를 핵심 데이터로 취급한다.

---

# 19. Participant - Surrender / AFK / Analysis Eligibility

| 필드 | 분류 | DB 저장 |
|---|---|---|
| `gameEndedInEarlySurrender` | ANALYSIS | O |
| `gameEndedInSurrender` | ANALYSIS | O |
| `teamEarlySurrendered` | ANALYSIS | O |
| `teamIGNBSurrendered` | DEFER | X |
| `wasAfk` | ANALYSIS | O |

## 19.1 AI 분석 활용

향후 다음과 같은 Feature를 Backend에서 생성할 수 있다.

```text
analysisEligible
analysisQuality
abnormalGameReason
```

예:

```text
wasAfk = true
→ 일반적인 플레이 평가에서 제외 또는 경고 표시

gameEndedInEarlySurrender = true
→ 표본이 짧으므로 분석 신뢰도 하향
```

---

# 20. Challenges

## 20.1 특징

`participants[].challenges`는 매우 많은 통계 필드를 포함한다.

샘플에서는 다음과 같이 전적 및 AI 분석에 유용한 값이 존재한다.

```text
kda
killParticipation
damagePerMinute
goldPerMinute
teamDamagePercentage
visionScorePerMinute
laneMinionsFirst10Minutes
jungleCsBefore10Minutes
soloKills
skillshotsHit
skillshotsDodged
...
```

동시에 현재 프로젝트와 관련이 적은 필드도 다수 존재한다.

```text
SWARM_*
poroExplosions
dancedWithRiftHerald
...
```

또한 모든 Participant가 항상 동일한 Challenge 필드를 갖는다고 가정하지 않는다.

## 20.2 v0.1 사용 후보

| 필드 | 프로젝트 해석 | 분류 |
|---|---|---|
| `kda` | Riot 계산 KDA | CORE |
| `killParticipation` | Kill Participation | CORE |
| `damagePerMinute` | Champion Damage per Minute 계열 | CORE |
| `goldPerMinute` | Gold per Minute | ANALYSIS |
| `teamDamagePercentage` | 팀 Champion Damage 비율 계열 | CORE |
| `damageTakenOnTeamPercentage` | 팀 Damage Taken 비율 계열 | ANALYSIS |
| `visionScorePerMinute` | Vision Score per Minute | ANALYSIS |
| `visionScoreAdvantageLaneOpponent` | 상대 대비 Vision Score 차이 계열 | ANALYSIS |
| `laneMinionsFirst10Minutes` | 10분 이전 Lane Minion 수 계열 | ANALYSIS |
| `jungleCsBefore10Minutes` | 10분 이전 Jungle CS 계열 | ANALYSIS |
| `maxCsAdvantageOnLaneOpponent` | Lane 상대 대비 최대 CS 차이 계열 | ANALYSIS |
| `maxLevelLeadLaneOpponent` | Lane 상대 대비 Level 우위 계열 | ANALYSIS |
| `laningPhaseGoldExpAdvantage` | Laning Phase Gold/EXP 우위 관련 값 | ANALYSIS |
| `soloKills` | Solo Kill | ANALYSIS |
| `skillshotsHit` | Skillshot Hit 관련 값 | DEFER |
| `skillshotsDodged` | Skillshot 회피 관련 값 | DEFER |
| `controlWardsPlaced` | Control Ward 설치 관련 값 | ANALYSIS |
| `wardTakedowns` | Ward Takedown | ANALYSIS |
| `wardTakedownsBefore20M` | 20분 이전 Ward Takedown | ANALYSIS |
| `turretPlatesTaken` | Turret Plate 획득 | ANALYSIS |
| `dragonTakedowns` | Dragon Takedown | ANALYSIS |
| `riftHeraldTakedowns` | Rift Herald Takedown | ANALYSIS |
| `baronTakedowns` | Baron Takedown | ANALYSIS |

## 20.3 중요한 주의사항

위 Challenge 필드의 의미는 현재 샘플의 **필드명과 값에 기반한 프로젝트 해석**이다.

이 문서의 기준 샘플만으로 Riot 내부 계산 정의나 장기적인 필드 안정성까지 확정할 수는 없다.

따라서:

- 핵심 통계는 가능한 경우 Raw Data를 기반으로 Backend에서 계산한다.
- Challenges 값은 보조 Feature 또는 검증용으로 사용한다.
- 의미가 불명확한 Challenge 필드는 AI 피드백의 핵심 근거로 바로 사용하지 않는다.

## 20.4 DTO 정책

Challenges 전체를 강한 타입의 Kotlin DTO로 모두 구현하지 않는다.

선택지:

1. 필요한 필드만 typed DTO로 받기
2. Challenges 전체는 `JsonNode` / Map으로 받고 Feature Extractor에서 선택

v0.1에서는 **필요한 필드만 점진적으로 typed DTO에 추가하는 방식**을 우선 고려한다.

외부 API 변경에 대비해 Unknown Property를 허용한다.

예:

```kotlin
@JsonIgnoreProperties(ignoreUnknown = true)
data class RiotChallengesDto(
    val kda: Double?,
    val killParticipation: Double?,
    val damagePerMinute: Double?,
    val goldPerMinute: Double?,
    val teamDamagePercentage: Double?,
    val visionScorePerMinute: Double?,
)
```

Challenges 값은 누락 가능성을 고려해 nullable로 설계한다.

---

# 21. Team

## 21.1 구조

```text
teams[]
├── teamId
├── win
├── bans[]
└── objectives
```

## 21.2 기본 정보

| 필드 | 의미 | 분류 | DB 저장 |
|---|---|---|---|
| `teamId` | 팀 ID | CORE | O |
| `win` | 승패 | CORE | O |

---

# 22. Team - Bans

## 22.1 구조

```text
bans[]
├── championId
└── pickTurn
```

| 필드 | 분류 | DB 저장 |
|---|---|---|
| `championId` | CORE | O |
| `pickTurn` | DEFER | X |

## 22.2 Sentinel 값

샘플에는 다음 값이 존재한다.

```text
championId = -1
```

따라서 Riot DTO에서 다음과 같은 validation을 두지 않는다.

```text
championId > 0
```

외부 API의 Raw Value는 가능한 한 그대로 수용한다.

---

# 23. Team - Objectives

## 23.1 구조

샘플에서 다음 Objective가 존재한다.

```text
objectives
├── atakhan
├── baron
├── champion
├── dragon
├── horde
├── inhibitor
├── riftHerald
└── tower
```

각 Objective는 다음 구조를 가진다.

```text
first: Boolean
kills: Int
```

## 23.2 분류

| Objective | 분류 | DB 저장 |
|---|---|---|
| `atakhan` | CORE | O |
| `baron` | CORE | O |
| `champion` | CORE | O |
| `dragon` | CORE | O |
| `horde` | CORE | O |
| `inhibitor` | CORE | O |
| `riftHerald` | CORE | O |
| `tower` | CORE | O |

## 23.3 Team Kill 정책

샘플 경기에서 패배 팀의 Participant Kill 합과:

```text
objectives.champion.kills
```

값이 일치하지 않는 사례가 있었다.

따라서 Team Kill은 다음처럼 Backend에서 계산한다.

```text
teamKillCount =
    같은 teamId를 가진 participants의 kills 합
```

`objectives.champion.kills`는 별도의 Riot Raw Value로 유지한다.

두 값이 항상 같다고 가정하지 않는다.

---

# 24. Derived Feature v0.1

Riot Raw Data를 기반으로 Backend에서 계산할 Feature를 정의한다.

## 24.1 기본 통계

### gameMinutes

```text
gameMinutes =
    gameDuration / 60.0
```

### totalCs

```text
totalCs =
    totalMinionsKilled
    + neutralMinionsKilled
```

### csPerMinute

```text
csPerMinute =
    totalCs / gameMinutes
```

### calculatedKda

```text
calculatedKda =
    (kills + assists) / max(deaths, 1)
```

---

## 24.2 Team 기반 통계

### teamKillCount

```text
teamKillCount =
    SUM(participant.kills)
    WHERE participant.teamId == target.teamId
```

### calculatedKillParticipation

```text
calculatedKillParticipation =
    (kills + assists) / teamKillCount
```

단, `teamKillCount = 0`인 경우 별도 처리한다.

---

## 24.3 Damage 통계

### damagePerMinute

```text
damagePerMinute =
    totalDamageDealtToChampions / gameMinutes
```

### teamChampionDamage

```text
teamChampionDamage =
    SUM(totalDamageDealtToChampions)
    WHERE teamId == target.teamId
```

### damageShare

```text
damageShare =
    totalDamageDealtToChampions / teamChampionDamage
```

---

## 24.4 Gold 통계

### goldPerMinute

```text
goldPerMinute =
    goldEarned / gameMinutes
```

### teamGold

```text
teamGold =
    SUM(goldEarned)
    WHERE teamId == target.teamId
```

### goldShare

```text
goldShare =
    goldEarned / teamGold
```

---

## 24.5 Vision 통계

### visionPerMinute

```text
visionPerMinute =
    visionScore / gameMinutes
```

---

## 24.6 Damage Taken 통계

### teamDamageTaken

```text
teamDamageTaken =
    SUM(totalDamageTaken)
    WHERE teamId == target.teamId
```

### damageTakenShare

```text
damageTakenShare =
    totalDamageTaken / teamDamageTaken
```

---

# 25. Riot Challenges와 Derived Feature의 관계

일부 Derived Feature는 Riot Challenges에도 유사한 값이 존재한다.

예:

```text
Backend calculatedKda
↔
challenges.kda

Backend calculatedKillParticipation
↔
challenges.killParticipation

Backend damagePerMinute
↔
challenges.damagePerMinute

Backend goldPerMinute
↔
challenges.goldPerMinute
```

v0.1의 Source of Truth 정책은 다음과 같다.

```text
Riot Raw Value
        ↓
Backend Derived Feature
        ↓
Riot Challenge 값과 비교 / 검증
```

Challenges에만 의존하지 않는다.

이 설계를 통해 Challenges 필드가 변경되거나 누락되어도 핵심 통계를 유지할 수 있다.

---

# 26. AI Analysis Feature 후보

Match Detail만으로 만들 수 있는 AI Feature 후보는 다음과 같다.

## 26.1 전투

```text
kills
deaths
assists
calculatedKda
calculatedKillParticipation
damagePerMinute
damageShare
damageTakenShare
soloKills
```

## 26.2 성장

```text
totalCs
csPerMinute
goldEarned
goldPerMinute
goldShare
champLevel
champExperience
```

## 26.3 Vision

```text
visionScore
visionPerMinute
wardsPlaced
wardsKilled
controlWard 관련 값
```

## 26.4 Objective

```text
damageDealtToObjectives
damageDealtToEpicMonsters
damageDealtToTurrets
turretTakedowns
dragonTakedowns
baronTakedowns
riftHeraldTakedowns
```

## 26.5 게임 이상 여부

```text
wasAfk
gameEndedInEarlySurrender
gameEndedInSurrender
teamEarlySurrendered
```

---

# 27. Match Detail만으로 분석할 수 없는 영역

Match Detail은 경기 종료 시점의 요약 데이터를 제공한다.

다음 분석에는 Timeline이 필요하다.

```text
10분 CS
10분 Gold
15분 Gold
시간대별 Level
첫 Death 시간
Objective 직전 Death
특정 시점 Item 보유 상태
Ward 설치 시각
Kill 발생 시각
Dragon / Baron 발생 시각
Objective 전후 Gold 변화
Lane Phase 구간 분석
```

따라서 AI 분석 기능에서는 Match Detail과 Timeline을 결합해야 한다.

---

# 28. v0.1에서 제외하는 데이터

현재 MVP에서는 다음 필드 계열을 우선 DTO에서 제외한다.

```text
PlayerScore0 ~ PlayerScore11

PlayerBehavior

missions.*

playerAugment*

playerSubteam*

SWARM_*

poroExplosions

대부분의 ping 통계

spell1Casts ~ spell4Casts

roleBoundItem

selectedRolePreferences

일부 특수 게임 모드 전용 값
```

제외한다는 의미는 Riot 데이터가 불필요하다는 뜻이 아니다.

정확한 의미는 다음과 같다.

> 현재 요구사항에서 사용하지 않으며, 불필요한 외부 API 결합도를 줄이기 위해 v0.1 DTO에 포함하지 않는다.

향후 기능 요구사항이 생기면 추가한다.

---

# 29. 권장 Riot DTO 구조

```text
RiotMatchResponse
│
├── metadata
│   └── matchId
│
└── info
    ├── gameStartTimestamp
    ├── gameEndTimestamp
    ├── gameDuration
    ├── gameVersion
    ├── gameMode
    ├── mapId
    ├── platformId
    ├── queueId
    │
    ├── participants[]
    │   ├── identity
    │   ├── team / position
    │   ├── champion
    │   ├── KDA
    │   ├── CS / Gold
    │   ├── Damage
    │   ├── Vision
    │   ├── Objectives
    │   ├── Items
    │   ├── Summoner Spells
    │   ├── Perks
    │   ├── Analysis Flags
    │   └── selected Challenges
    │
    └── teams[]
        ├── teamId
        ├── win
        ├── bans[]
        └── objectives
```

---

# 30. DTO 구현 원칙

Riot API는 외부 시스템이므로 예상하지 못한 필드가 추가되어도 서비스가 깨지지 않도록 한다.

Kotlin/Jackson 기준:

```kotlin
@JsonIgnoreProperties(ignoreUnknown = true)
data class RiotMatchResponse(
    val metadata: RiotMatchMetadataDto,
    val info: RiotMatchInfoDto,
)
```

하위 DTO에도 동일한 원칙을 적용한다.

Challenges처럼 필드 존재 여부가 불안정한 영역은 nullable을 적극적으로 사용한다.

```kotlin
data class RiotChallengesDto(
    val kda: Double?,
    val killParticipation: Double?,
    val damagePerMinute: Double?,
)
```

외부 API DTO에서 과도한 validation을 수행하지 않는다.

---

# 31. DB 저장 전략 v0.1

현재 단계에서는 Riot JSON 전체를 그대로 Relational Table로 복제하지 않는다.

권장 개념 모델:

```text
Match
│
├── MatchParticipant
│
└── MatchTeam
```

향후 필요하면:

```text
MatchParticipantItem
MatchParticipantPerk
MatchBan
```

등을 별도 테이블 또는 JSON 컬럼으로 분리할 수 있다.

DB 구조는 Riot DTO 구조와 1:1로 일치하지 않아도 된다.

---

# 32. Raw JSON 저장 여부

전체 Riot Match JSON을 DB에 그대로 저장하는 전략은 v0.1에서는 보류한다.

가능한 장점:

- Riot 응답 원본 보존
- 향후 새로운 Feature를 다시 계산할 수 있음
- 디버깅 용이

가능한 단점:

- 저장 공간 증가
- 개인정보 / Riot 정책 검토 필요
- 데이터 중복
- Riot 응답 Schema 변경 관리 필요

MVP에서는 필요한 필드 중심으로 저장하고, 향후 AI Feature 재계산 요구가 커질 경우 Raw JSON 저장 전략을 다시 검토한다.

---

# 33. 데이터 정합성 검증 후보

서비스 개발 시 다음 값들을 비교하여 Riot 응답과 우리 계산 결과를 검증할 수 있다.

```text
Backend calculatedKda
vs
challenges.kda

Backend calculatedKillParticipation
vs
challenges.killParticipation

Backend damagePerMinute
vs
challenges.damagePerMinute

Backend goldPerMinute
vs
challenges.goldPerMinute

Participant Kill 합
vs
teams[].objectives.champion.kills
```

이 검증은 테스트 코드로 작성할 가치가 있다.

단, 값이 다르다고 바로 Riot 응답 오류라고 판단하지 않는다. 각 필드의 공식 정의가 다를 수 있기 때문이다.

---

# 34. 테스트 전략 후보

## 34.1 DTO Deserialize Test

실제 Riot 응답 샘플을 Test Fixture로 저장한다.

```text
src/test/resources/
└── riot/
    └── match/
        └── match-KR_8361620721.json
```

테스트:

```text
JSON Deserialize 성공
matchId 확인
participants size 확인
teams size 확인
특정 Participant 값 확인
unknown field 존재 시 실패하지 않는지 확인
```

## 34.2 Feature Calculation Test

예:

```text
KDA 계산
CS/min 계산
Damage/min 계산
Kill Participation 계산
Damage Share 계산
Gold Share 계산
Vision/min 계산
```

## 34.3 Edge Case Test

```text
deaths = 0
teamKillCount = 0
itemId = 0
ban championId = -1
challenge field 누락
surrender game
AFK participant
```

---

# 35. v0.1 기술적 결정 요약

| 항목 | 결정 |
|---|---|
| Player 내부 식별자 | `PUUID` |
| Match 식별자 | `metadata.matchId` |
| 대표 Position | `teamPosition` |
| Champion 식별자 | `championId` |
| Item 식별 | Item ID |
| Rune 식별 | Perk ID |
| Summoner Spell 식별 | Spell ID |
| KDA 등 핵심 통계 | Raw Data에서 Backend 계산 |
| Challenges | 보조 Feature / 검증 용도 |
| Unknown Riot Field | 무시 허용 |
| Challenges Nullable | 허용 |
| `lane` | 대표 Position으로 사용하지 않음 |
| Team Kill | Participant Kill 합으로 계산 |
| Riot DTO와 Entity | 분리 |
| Riot DTO와 API Response | 분리 |
| Riot DTO와 AI Input | 분리 |
| Raw Match JSON 저장 | v0.1 보류 |
| Timeline | 별도 데이터 명세로 정의 |
| AI에 Raw JSON 직접 전달 | 금지 |

---

# 36. 미확정 사항

다음 내용은 추가 Match 샘플 또는 공식 문서 검증 후 확정한다.

1. `teamPosition`을 모든 게임에서 대표 Position으로 사용할 수 있는가
2. `individualPosition`과 `teamPosition`의 차이를 어떻게 활용할 것인가
3. `detectorWardsPlaced`와 `controlWardsPlaced`의 정확한 관계
4. Challenges 각 필드의 공식 계산 정의
5. Challenges 필드의 장기적인 안정성
6. `objectives.champion.kills`와 Participant Kill 합의 차이 원인
7. Match Raw JSON을 장기 저장할 필요가 있는가
8. Match Participant에 Riot ID Snapshot을 저장할 것인가
9. Perks / Items를 정규화 테이블로 저장할지 JSON 컬럼으로 저장할지
10. Normal / ARAM / 특수 모드까지 같은 Match 모델을 사용할지

---

# 37. 다음 문서

이 문서 이후 다음 명세를 작성한다.

```text
riot-match-timeline-data-spec-v0.1.md
```

목표:

```text
Timeline Frame
Participant Frame
Champion Kill Event
Ward Event
Item Event
Building Event
Elite Monster Event
Gold / XP / CS 변화
10분 / 15분 Feature
Objective 전후 Feature
```

최종적으로:

```text
Match Detail
    +
Match Timeline
    ↓
MatchAnalysisFeature
    ↓
LLM Analysis Input
```

구조를 완성한다.

---

# 38. Version History

## v0.1

- 실제 Match-V5 Match Detail 샘플을 기준으로 초기 데이터 명세 작성
- Match / Participant / Team 핵심 필드 선정
- CORE / ANALYSIS / DEFER / IGNORE 분류 도입
- DB 저장 여부 초안 정의
- Position 대표값으로 `teamPosition` 선정
- PUUID를 Player 내부 식별 기준으로 선정
- Derived Feature 계산 정책 정의
- Challenges를 핵심 Source of Truth가 아닌 보조 데이터로 분류
- Riot DTO / Service Model / DB Model / AI Model 분리 원칙 정의
