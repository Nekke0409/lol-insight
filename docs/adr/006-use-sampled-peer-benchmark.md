# ADR-006: 상대 플레이어 분석에 표본 기반 Peer Benchmark 사용

Status: Accepted

## Context

현재 플레이어 통계는 최근 Match 표본의 절대 수치를 보여 준다. 이 값만으로는 사용자가 자신의 CS/min,
damage/min, vision/min 같은 수치가 같은 조건의 다른 플레이어와 비교해 어떤지 알기 어렵다. 상대적인
강점과 개선 지점을 만들려면 비교 기준이 되는 Peer Benchmark가 필요하다.

Riot 전체 Ranked Ladder나 모든 Match를 수집하면 더 넓은 population을 표현할 수 있지만, Rate Limit, storage,
collection 시간, freshness, 운영 비용이 크게 증가한다. 이 ADR을 수립할 당시 application에는
PostgreSQL/JPA/Flyway, League API Client, collector, scheduler, benchmark persistence나 LLM 연동도 구현되어
있지 않았다.

따라서 구현을 시작하기 전에 비교 데이터의 관측 단위, tier attribution, 시간 해석과 표본의 한계를 먼저
명확히 한다.

## Decision

전체 population의 전수 수집 대신 sampled Peer Benchmark dataset을 사용한다.

```text
ranked player source
    -> sampled players
    -> recent Ranked Solo Match IDs
    -> Match ID deduplication
    -> normalized Match
    -> sampled player's participant-level BenchmarkSample
    -> aggregate
    -> PeerBenchmark
```

### BenchmarkSample

`BenchmarkSample` 한 건은 `(matchId, sampled player PUUID)`로 식별되는 한 경기 관측치다. 여러 경기의 평균이
아니며, sampled player가 해당 Ranked Solo Match에서 기록한 participant-level metric을 나타낸다.

예를 들어 GOLD I / MIDDLE / AHRI 조건에서 sampled player A가 Match `KR_xxx`에 참여했다면, A의 CS/min,
gold/min, damage/min, KDA, kill participation, vision/min, damage share 등이 하나의 `BenchmarkSample`이 된다.
average, median, percentile은 여러 sample을 모으는 aggregate 단계에서 계산한다.

KDA와 per-minute·ratio metric의 공식은 기존 `PlayerMatchStatisticsCalculator`가 사용하는 per-match 계산을
source of truth로 재사용하거나 같은 기준으로 추출한다. aggregate 결과를 `BenchmarkSample`으로 취급하지 않는다.

### Tier 귀속과 시간 의미

League API로 rank가 확인된 sampled player만 BenchmarkSample 생성 대상이다. sampled player A가 수집 시점에
GOLD I이고 Match에 참여했다면 A의 participant만 GOLD I sample이 된다. 같은 Match에 있다는 이유만으로 나머지
participant에게 GOLD I을 부여하거나 tier를 추정하지 않는다.

다른 participant B의 rank도 독립적으로 확인되어 B가 sampled player라면 B의 관측 rank로 별도의 sample을 만들 수
있다. 하나의 Match에서 여러 sample이 생길 수 있지만, 각 sample의 tier attribution은 독립적인 rank 확인을
전제로 한다.

MVP에서 rank는 일반적으로 **수집 시점의 rank**다. 과거 Match 시점의 정확한 rank라고 가정하지 않는다. sample은
최소한 tier, division, rank 관측 시각과 Match의 game start timestamp를 보존할 수 있어야 한다. 별도
`RankSnapshot` history는 향후 작업이다.

### Cohort

PeerBenchmark의 우선 cohort dimension은 다음과 같다.

- region
- queue
- tier
- division
- position
- championId

서로 다른 position의 수치를 같은 기준선으로 직접 비교하지 않는다. v0.1은 GOLD I sample을 전체 GOLD로 해석하지 않기 위해
division을 cohort에 포함한다. patch/gameVersion 및 다른 게임 맥락은 실제 필요가 확인된 뒤 추가한다.

### Initial Vertical Slice

초기 vertical slice는 `tier=GOLD`, `division=I`, `playerLimit=10`처럼 작은 범위로 League-V4 ranked player discovery와
entry가 제공하는 PUUID 사용을 검증한다. 이어서 이미 discovery된 `SampledRankedPlayer`를 입력으로 `queue=420` Match ID
조회, Match ID deduplication, bounded Detail 조회, participant metric 추출 및 idempotent persistence를 수행한다.

이 결과를 production-quality GOLD benchmark 또는 GOLD 전체 population의 대표 평균으로 표현하지 않는다.
실제 benchmark 품질을 높이려면 여러 division/page와 명시적인 sampling policy를 별도로 결정해야 한다.

### Analysis Boundary

Backend는 cohort 선택, sample size 검증, average·median·match-level percentile threshold, player와 benchmark의
numeric difference, deterministic `PlayerComparisonFeature`를 계산한다. LLM은 구조화된 비교 결과를 자연어로
설명하고 피드백을 생성한다.

LLM은 원본 Riot Match JSON을 직접 분석해 percentile을 계산하거나, 임의 benchmark를 만들거나, MMR을 추정하지
않는다.

### Cache Reuse

collector는 기존 `RiotMatchClient.findMatchById`를 통해 Match Detail을 읽고 Redis `match:detail:{matchId}` cache를
재사용한다. benchmark 전용 Match Detail cache는 도입하지 않는다.

## Result

```text
PlayerAnalysisFeature + PeerBenchmark
    -> PlayerComparisonFeature (implemented)
    -> LLM explanation (planned)
```

`PeerBenchmark`는 participant-level `BenchmarkSample`의 aggregate이며, 전체 ladder의 공식 순위나 MMR을
대체하지 않는다.

## Implementation Status

`BenchmarkSample` persistence foundation은 구현됐다. PostgreSQL Flyway migration, domain model과 JPA Entity의
분리, `(match_id, puuid)` unique constraint, 그리고 `saveIfAbsent`의 idempotent write가 포함된다. KR
`RANKED_SOLO_5x5`의 League-V4 page 조회, entry PUUID 사용 및 `SampledRankedPlayer` 생성도 구현됐다.
collector는 Match-V5 `queue=420` filter, Match ID deduplication과 sampled-player 관계 보존, bounded Detail loading,
participant metric 계산 및 `BenchmarkSample` 저장을 구현한다. 429는 이후 Riot 요청 scheduling을 중단하고, collector 전체는
transaction을 열지 않는다. raw `benchmark_sample`의 PostgreSQL on-demand aggregate, match-level percentile threshold,
`PeerBenchmarkQueryService`와 availability policy, exact cohort·availability·numeric difference를 결합하는
`PlayerComparisonFeature`는 구현됐다. scheduler와 player percentile rank는 계속 계획 상태다.
LLM integration은 이후 [ADR-007](007-use-structured-llm-analysis-boundary.md) 범위에서 구현됐다.

## Reason

- 비교 기준을 제공하면서도 전수 수집의 Rate Limit·storage·운영 비용을 피할 수 있다.
- participant-level 관측 단위와 독립적인 tier attribution으로 잘못된 cohort 오염을 막는다.
- 기존 normalized Match, metric 계산, Match Detail cache 경계를 재사용해 점진적으로 구현할 수 있다.

## Consequences

### Positive

- 전수 수집보다 upstream 호출, storage와 운영 비용을 제한할 수 있다.
- `sampleCount`로 cohort별 신뢰도와 부족한 표본을 명시적으로 관리할 수 있다.
- 기존 normalized `Match`, Match Detail cache, per-match metric 계산을 재사용할 수 있다.
- incremental collection, aggregate materialization과 더 큰 표본으로 단계적으로 확장할 수 있다.

### Negative / Trade-offs

- 결과는 전수 통계가 아니며 sampling bias를 가진다.
- low-pick champion이나 세분화된 cohort는 sample 부족과 sample imbalance가 발생할 수 있다.
- 수집 시점 rank와 Match 시점 rank의 차이가 남는다.
- 작은 vertical slice의 결과는 benchmark 품질이나 대표성을 입증하지 않는다.

## Alternatives Considered

### 전체 래더와 Match 모집단 수집

더 넓은 population을 표현할 수 있지만, 현재 단계에서 Rate Limit, storage, freshness 및 운영 비용이 과도하다.
샘플링 정책과 수집 파이프라인을 검증한 뒤에도 전수 수집이 실제로 필요한지 별도로 판단한다.

### 표본 플레이어 한 명의 tier를 Match의 모든 참가자에게 부여

구현은 간단하지만 Match 동시 참가만으로 다른 participant의 tier를 알 수 없다. 잘못된 cohort attribution을
만들므로 사용하지 않는다.

### LLM이 비교 기준선을 추론하도록 허용

설명은 유연해 보이지만 비교 수치의 재현성, 검증 가능성, 비용 통제가 불가능하다. 통계 계산과 cohort 선택은
Backend의 결정적 책임으로 유지한다.

## 향후 계획

- retention 정책
- scheduled collection과 process-wide rate-limit handling
- larger/stratified sampling 및 sampling policy 문서화
- patch-aware benchmark와 rank snapshot history
- aggregate materialization, player percentile rank
- LLM Provider adapter와 자연어 피드백 endpoint
