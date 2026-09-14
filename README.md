# LOL Insight

League of Legends 데이터를 바탕으로 플레이어의 최근 경기와 통계를 조회하고, 같은 조건의 플레이어 집단과 비교한 분석으로 확장하는 Backend 중심 서비스입니다.

단순 기능 구현보다 외부 API 연동, 데이터 정규화, Rate Limit, 캐싱, 통계 계산, 실패 처리와 테스트를 실제 서비스 관점에서 다룹니다.

## Service Direction

서비스는 다음 흐름으로 확장합니다.

1. Riot Games API 기반 플레이어 검색, 최근 경기 및 개인 통계 조회
2. 정규화된 Match와 개인 통계를 바탕으로 한 Peer Benchmark 데이터셋 수집
3. 동일 region, queue, tier, position, champion cohort와의 상대 비교
4. Backend가 계산한 비교 feature를 설명하는 LLM 기반 자연어 피드백
5. 사용자 계정, 게시글, 댓글 등의 커뮤니티

Riot의 공식 Ranked Ladder를 대체하는 MMR, ELO 또는 자체 Skill Rating을 만들지 않습니다.

## Current Implementation and Planned Work

| Area | Current implementation | Planned / future work |
| --- | --- | --- |
| Riot integration | Account-V1 기반 Riot ID 조회, Match-V5 Match ID/Detail 조회와 queue filter, League-V4 기반 KR Ranked Solo player discovery | representative sampling, scheduled collection |
| Match processing | Riot Match DTO를 내부 `Match` 모델로 정규화하고 sampled player의 participant-level observation 추출 | aggregate용 추가 feature |
| Player statistics | 최근 Match 표본의 KDA, CS/min, DPM, 골드/비전, 킬 관여율, 피해 비중 계산 | player-level benchmark |
| Analysis feature | 개인 요약용 `PlayerAnalysisFeature`, 현재 Solo rank와 champion/position별 사용자 지표를 담는 `PlayerComparisonContext`, exact cohort·availability·metric difference를 담는 `PlayerComparisonFeature` | comparison feature의 LLM 설명 |
| Redis | 성공한 Match Detail을 7일 TTL로 캐시 | benchmark 전용 Redis 기능은 도입하지 않음 |
| Persistence | PostgreSQL, JPA, Flyway 기반 `BenchmarkSample` schema, idempotent 저장 진입점, 소규모 collector와 on-demand aggregate query | retention 정책 |
| LLM | 구현되지 않음 — OpenAI/다른 Provider client, prompt, endpoint 없음 | Backend가 만든 comparison feature의 자연어 설명 |

## Current Architecture

초기 구조는 하나의 Spring Boot 애플리케이션 안에서 기능별 경계를 나누는 Modular Monolith입니다.

```text
HTTP Request
    -> Controller
    -> Application / Service
    -> Riot Client / Cache
    -> Riot Games API / Redis
```

현재 Player의 최근 Match와 통계는 아래 흐름을 공유합니다.

```text
Riot ID -> Account-V1 -> PUUID -> Match IDs -> normalized Match
                                              -> player statistics
                                              -> PlayerAnalysisFeature
```

Match Detail은 `RiotMatchClient` 경계에서 Redis를 사용합니다. 최근 경기 Detail fan-out은 application lifecycle이 관리하는 고정 4-thread executor와 sliding window로 제한됩니다. cache hit은 Riot Match-V5 Detail 호출을 생략하지만, cache는 rate limiter나 retry 정책이 아닙니다.

Peer Benchmark의 ranked player discovery와 제한된 collection vertical slice가 구현됐습니다. League-V4의 KR
`RANKED_SOLO_5x5` entry를 page 단위로 읽어 entry가 제공하는 PUUID를 `SampledRankedPlayer`로 정규화합니다.
collector는 Ranked Solo Match ID를 `queue=420`으로 조회하고, Match ID별로
sampled player 관계를 보존한 채 Detail을 한 번만 읽어 participant-level sample을 idempotent하게 저장합니다.

```text
ranked player source
    -> sampled players
    -> recent Ranked Solo Match IDs (implemented)
    -> Match ID deduplication (implemented)
    -> normalized Match (implemented)
    -> BenchmarkSample (sampled player, matchId, implemented)
    -> saveIfAbsent PostgreSQL persistence (implemented)
    -> Benchmark Aggregate (implemented)
    -> PeerBenchmark (implemented)
    -> PlayerComparisonFeature
    -> LLM feedback
```

`BenchmarkSample`은 여러 경기를 평균 낸 값이 아니라, 표본 플레이어 한 명의 한 경기 participant-level observation입니다. 해당 플레이어의 수집 시점 rank만 sample에 귀속하며, 같은 Match의 다른 participant에게 tier를 추정하거나 부여하지 않습니다.

자세한 설계와 현재 구현 경계는 [architecture.md](docs/architecture.md), Peer Benchmark 선택의 근거는 [ADR-006](docs/adr/006-use-sampled-peer-benchmark.md), v0.1 aggregate의 정확한 의미와 한계는 [Peer Benchmark v0.1](docs/benchmark/peer-benchmark-v0.1.md)에서 확인할 수 있습니다.

## AI Analysis Principle

계산과 설명을 분리합니다.

```text
Riot data
    -> normalization
    -> statistics and cohort comparison in Backend
    -> deterministic feature
    -> LLM explanation
```

Backend는 metric, exact cohort, sample size, 평균·중앙값·match-level percentile threshold, 차이와 비교 feature를 계산합니다. LLM은 계산된 결과를 자연어로 설명할 미래 역할입니다. 원본 Riot Match JSON 분석, cohort 선택, subtraction, player percentile 계산, benchmark 생성, MMR 추정은 LLM의 역할이 아닙니다.

## Technology

현재 사용 중인 기술은 Kotlin, Spring Boot, Spring MVC `RestClient`, PostgreSQL, Spring Data JPA,
Flyway, Redis, Docker입니다. JDK 21을 사용합니다.

Spring Security, AWS 및 OpenAI API 또는 다른 LLM Provider는 서비스 요구가 구체화될 때 도입을 검토하는
기술 방향이며, 현재 구현된 구성은 아닙니다.

## Documentation

- [AGENTS.md](AGENTS.md): 작업 원칙과 구현 가이드
- [Architecture](docs/architecture.md): 현재 구현과 향후 설계 경계
- [ADRs](docs/adr/): 장기적인 기술 의사결정
- [Player Match Statistics v0.1](docs/statistics/player-match-statistics-v0.1.md): 현재 통계의 계산 기준
- [Player Analysis Feature v0.1](docs/ai/player-analysis-feature-v0.1.md): 현재 provider 독립 feature의 범위
- [Player Comparison Context v0.1](docs/ai/player-comparison-context-v0.1.md): peer comparison 사용자 입력의 범위
- [Player Comparison Feature v0.1](docs/ai/player-comparison-feature-v0.1.md): exact benchmark comparison의 범위와 한계

## Local Environment

Riot API key는 환경 변수로만 주입합니다. 실제 값을 저장소에 커밋하지 않습니다.

```text
RIOT_API_KEY=your-riot-api-key
```

로컬 개발에서 PostgreSQL과 Match Detail cache용 Redis를 함께 실행합니다.

```text
docker compose up -d
```

`local` profile은 PostgreSQL의 로컬 기본값(`localhost:5432`, database/user `lol_insight`)을 사용합니다.
`POSTGRES_HOST`, `POSTGRES_PORT`, `POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD`로 값을 덮어쓸 수
있으며 production에서는 모든 값을 환경변수로 제공해야 합니다. Compose의 `lol-insight-local` password는
로컬 개발 기본값일 뿐 production secret이 아닙니다.

`BenchmarkSample` persistence는 migration으로 관리되며 JPA는 schema validation만 수행합니다. collector는 public REST
endpoint, startup runner, scheduler 없이 application service로만 제공됩니다. `PeerBenchmarkQueryService`는 raw
`benchmark_sample`을 PostgreSQL에서 on-demand 집계하며, percentile threshold와 availability만 제공합니다.
`PlayerComparisonFeature`는 이 aggregate와 사용자 context를 deterministic하게 결합합니다. player percentile rank와 LLM 기능은 아직 구현되지 않았습니다. 현재 설정의 기본 Redis 주소는
`localhost:6379`입니다. 실행 및 측정 방법은
[recent match latency baseline](docs/performance/recent-matches-latency-baseline.md)을 참고합니다.

`BenchmarkSample` persistence integration test는 Testcontainers PostgreSQL을 사용하므로 Docker daemon이
실행 중이어야 합니다.

## Development Smoke Procedure

실제 development Riot key로 작은 수집을 확인할 때는 IDE의 dev-only evaluation 또는 임시 local harness에서
`RankedPlayerDiscoveryService.discover("GOLD", "I", 2)`의 결과를
`BenchmarkMatchCollectionService.collect(players, 2)`에 전달합니다. 이 흐름은 `RIOT_API_KEY`와 local PostgreSQL/Redis를
필요로 하며, endpoint·scheduler·startup runner를 추가하지 않습니다. 결과는 representative GOLD benchmark가 아니라
작은 연결 확인용 표본으로만 해석합니다.
