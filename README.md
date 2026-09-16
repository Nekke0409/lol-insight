# LOL Insight

League of Legends 데이터를 바탕으로 플레이어의 최근 경기와 통계를 조회하고, 같은 조건의 플레이어 집단과 비교한 분석으로 확장하는 Backend 중심 서비스입니다.

단순 기능 구현보다 외부 API 연동, 데이터 정규화, Rate Limit, 캐싱, 통계 계산, 실패 처리와 테스트를 실제 서비스 관점에서 다룹니다.

## 서비스 방향

서비스는 다음 흐름으로 확장합니다.

1. Riot Games API 기반 플레이어 검색, 최근 경기 및 개인 통계 조회
2. 정규화된 Match와 개인 통계를 바탕으로 한 Peer Benchmark 데이터셋 수집
3. 동일 region, queue, tier, position, champion cohort와의 상대 비교
4. Backend가 계산한 비교 feature를 설명하는 LLM 기반 자연어 피드백
5. 사용자 계정, 게시글, 댓글 등의 커뮤니티

Riot의 공식 Ranked Ladder를 대체하는 MMR, ELO 또는 자체 Skill Rating을 만들지 않습니다.

## 현재 구현과 계획된 작업

| 영역 | 현재 구현 | 계획된 / 향후 작업 |
| --- | --- | --- |
| Riot integration | Account-V1 기반 Riot ID 조회, Match-V5 Match ID/Detail 조회와 queue filter, League-V4 기반 KR Ranked Solo player discovery | representative sampling, scheduled collection |
| Match processing | Riot Match DTO를 내부 `Match` 모델로 정규화하고 sampled player의 participant-level observation 추출 | aggregate용 추가 feature |
| Player statistics | 최근 Match 표본의 KDA, CS/min, DPM, 골드/비전, 킬 관여율, 피해 비중 계산 | player-level benchmark |
| Analysis feature | 개인 요약용 `PlayerAnalysisFeature`, comparison-ready `PlayerComparisonContext`, deterministic `PlayerComparisonFeature`, 그리고 AVAILABLE cohort만 설명하는 `PlayerAnalysisResult` | locale, result quality evaluation |
| Redis | 성공한 Match Detail을 7일 TTL로 캐시 | benchmark 전용 Redis 기능은 도입하지 않음 |
| Persistence | PostgreSQL, JPA, Flyway 기반 `BenchmarkSample`과 `AnalysisJob` schema, idempotent sample 저장, JSONB analysis snapshot | retention 정책, job crash recovery |
| LLM | OpenAI Responses API + Structured Outputs, sync `/analysis`와 polling `/analysis-jobs`, provider-independent `PlayerAnalysisGenerator` | result cache, per-user rate limit, cost observability, multi-provider |

## 현재 아키텍처

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
랭크 플레이어 원천
    -> 표본 플레이어
    -> 최근 Ranked Solo Match ID(구현됨)
    -> Match ID 중복 제거(구현됨)
    -> 정규화된 Match(구현됨)
    -> BenchmarkSample(표본 플레이어, matchId, 구현됨)
    -> saveIfAbsent PostgreSQL 영속성(구현됨)
    -> Benchmark 집계(구현됨)
    -> PeerBenchmark(구현됨)
    -> PlayerComparisonFeature
    -> AVAILABLE comparison gate
    -> OpenAI LLM feedback
```

`BenchmarkSample`은 여러 경기를 평균 낸 값이 아니라, 표본 플레이어 한 명의 한 경기 participant-level observation입니다. 해당 플레이어의 수집 시점 rank만 sample에 귀속하며, 같은 Match의 다른 participant에게 tier를 추정하거나 부여하지 않습니다.

자세한 설계와 현재 구현 경계는 [architecture.md](docs/architecture.md), Peer Benchmark 선택의 근거는 [ADR-006](docs/adr/006-use-sampled-peer-benchmark.md), v0.1 aggregate의 정확한 의미와 한계는 [Peer Benchmark v0.1](docs/benchmark/peer-benchmark-v0.1.md)에서 확인할 수 있습니다.

## AI 분석 원칙

계산과 설명을 분리합니다.

```text
Riot 데이터
    -> 정규화
    -> Backend의 통계 및 cohort 비교
    -> 결정적 feature
    -> LLM 설명
```

Backend는 metric, exact cohort, sample size, 평균·중앙값·match-level percentile threshold, 차이와 비교 feature를 계산합니다. AVAILABLE comparison이 하나라도 있을 때만 LLM을 정확히 한 번 호출해 이 결과를 한국어로 설명합니다. 원본 Riot Match JSON 분석, cohort 선택, subtraction, player percentile 계산, benchmark 생성, MMR 추정은 LLM의 역할이 아닙니다.

## 기술 스택

현재 사용 중인 기술은 Kotlin, Spring Boot, Spring MVC `RestClient`, PostgreSQL, Spring Data JPA,
Flyway, Redis, Docker, OpenAI Java SDK입니다. JDK 21을 사용합니다.

OpenAI 연동은 Responses API Structured Outputs를 사용하는 v0.2 다중 scope 분석 경계와 v0.1 async job 실행 경계로 구현되어 있습니다.
Spring Security, AWS, 다중 LLM Provider, 결과 cache, 사용자별 AI rate limit과 비용 관측은 후속 기술 방향입니다.

## 문서

- [AGENTS.md](AGENTS.md): 작업 원칙과 구현 가이드
- [아키텍처](docs/architecture.md): 현재 구현과 향후 설계 경계
- [ADR](docs/adr/): 장기적인 기술 의사결정
- [ADR-007](docs/adr/007-use-structured-llm-analysis-boundary.md): Structured LLM 분석 경계 결정
- [ADR-009](docs/adr/009-introduce-asynchronous-player-analysis-jobs.md): async Player Analysis Job 결정
- [플레이어 경기 통계 v0.1](docs/statistics/player-match-statistics-v0.1.md): 현재 통계의 계산 기준
- [플레이어 분석 Feature v0.1](docs/ai/player-analysis-feature-v0.1.md): 현재 provider 독립 feature의 범위
- [플레이어 분석 v0.2](docs/ai/player-analysis-v0.2.md): 다중 scope OpenAI 분석 게이트, input/output 및 운영 제약
- [Async Player Analysis Job v0.1](docs/ai/async-player-analysis-jobs-v0.1.md): polling API, lifecycle, transaction 및 recovery 제한
- [플레이어 비교 컨텍스트 v0.1](docs/ai/player-comparison-context-v0.1.md): peer comparison 사용자 입력의 범위
- [플레이어 비교 Feature v0.1](docs/ai/player-comparison-feature-v0.1.md): exact benchmark comparison의 범위와 한계

## 로컬 환경

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
`PlayerComparisonFeature`는 이 aggregate와 사용자 context를 deterministic하게 결합합니다. player percentile rank는 구현되지 않았으며, LLM analysis는 AVAILABLE cohort가 있을 때만 이 feature를 설명합니다. 현재 설정의 기본 Redis 주소는
`localhost:6379`입니다. 실행 및 측정 방법은
[recent match latency baseline](docs/performance/recent-matches-latency-baseline.md)을 참고합니다.

`BenchmarkSample` persistence integration test는 Testcontainers PostgreSQL을 사용하므로 Docker daemon이
실행 중이어야 합니다.

## OpenAI 관측성 v0.1

OpenAI Responses 어댑터는 기존 Micrometer `MeterRegistry`에 provider 호출 메타데이터를 기록한다.
이는 계측만 추가하는 변경으로, metrics backend·exporter·Actuator endpoint 노출 정책·외부 동기 monitoring
호출은 추가하지 않는다.

| Meter | Tags | 의미 |
| --- | --- | --- |
| `ai.generation.requests` | `provider`, `model`, `outcome`, `error_category` | 분석 생성 결과 한 건. `outcome`은 `success` 또는 `failure`다. |
| `ai.generation.duration` | `provider`, `model`, `outcome`, `error_category` | `responses.create(...)`까지 도달한 요청의 OpenAI provider 호출 지연 시간이다. |
| `ai.generation.tokens` | `provider`, `model`, `token_type` | usage가 포함된 성공 응답의 SDK 제공 `input`·`output`·`total` 토큰 카운터와, SDK output detail이 있을 때의 `reasoning` 토큰 카운터다. |

성공한 요청의 `model` tag는 Responses API 응답의 실제 model을 사용한다. 실패한 요청에는 response model이
없으므로 configured request model을 사용하며, model 자체가 설정되지 않은 configuration 실패에는
`unconfigured`을 사용한다. 이 값은 request별 데이터가 아닌 deployment configuration이며, 의도적으로
고정값이 아닌 유일한 tag 값이다.

OpenAI Java SDK의 `StructuredResponse.usage()`는 optional이다. usage가 없는 응답도 성공으로 처리하고
request count와 latency만 기록한다. usage는 있지만 output detail이 없는 경우에도 분석과 기존
`input`·`output`·`total` counter는 유지하고 `reasoning` counter만 생략한다. v0.1은 currency가 아닌 token
count만 기록한다. model price, USD/KRW 변환, cost aggregation, billing dashboard는 구현하지 않는다.

success는 OpenAI 호출과 구조화 출력 매핑이 모두 완료된 상태를 뜻한다. failure는 기존 HTTP 오류 매핑을
유지하면서 `configuration`, `authentication_permission`, `rate_limit`, `upstream`,
`timeout_network`, `malformed_structured_output`으로 tag한다. configuration 실패는 OpenAI 요청을 보내지
않으므로 provider latency가 없다.

raw prompt, request/response JSON, 자연어 분석 본문, Riot ID, PUUID, match ID, API key, OpenAI request
ID, provider usage object은 log·persistence·metric·analysis REST response에 넣지 않는다. recorder 실패는
분석 결과나 기존 오류 의미를 바꾸지 않도록 격리한다. `/analysis` 전체 HTTP 지연 시간은 generator가 아닌
framework 제공 HTTP observation으로 측정한다.

## OpenAI 런타임 설정

OpenAI 분석의 production 기본 timeout은 `60s`이며 `OPENAI_TIMEOUT`으로 환경별 override할 수 있습니다.
이는 latency SLA가 아니라 `gpt-5-mini` Responses API Structured Outputs smoke의 실측에 기반한 MVP
operational default입니다. 20초에서는 실제 요청이 약 21.4~21.5초에 반복 timeout됐고, 45초에서는 약
38.258~43.469초에 성공했습니다. SDK retry는 비용과 중복 요청을 제어하기 위해 `maxRetries(0)`으로
유지합니다.

production Spring Boot runtime은 `application.yaml`의 기본값과 환경 변수 override를 함께 사용합니다.
반면 manual OpenAI smoke는 `StandardEnvironment` + `Binder`로 환경 값만 읽으므로
`OPENAI_MODEL=gpt-5-mini`과 필요한 `OPENAI_TIMEOUT`을 명시해 실행합니다.

production generation 기본값은 `reasoning.effort=low`, `text.verbosity=low`다. 따라서 기본 요청은 두 field를
명시적으로 전송한다. `OPENAI_REASONING_EFFORT`는 `minimal`·`low`·`medium`·`high`로, `OPENAI_TEXT_VERBOSITY`는
`low`·`medium`·`high`로 환경별 override할 수 있으며, 빈 값과 지원하지 않는 값은 configuration error로 거부한다.
이 정책은 model, prompt, Structured Output schema, `max_output_tokens`, timeout, retry를 변경하지 않는다.

representative 실제 `/analysis` 한 건에서 model default 대비 `low` + `low`는 provider latency를 74.306s에서
38.153s로 48.7%, endpoint latency를 75.448s에서 40.464s로 46.4%, total tokens를 7,697에서 4,277로 44.4%
줄였고 quality contract를 유지했다. 단일 표본이며 약 40초의 synchronous UX는 여전히 길므로, 추가 parameter
tuning 대신 [Async Player Analysis Job v0.1](docs/ai/async-player-analysis-jobs-v0.1.md)으로 HTTP lifecycle을
분리한다.

`max_output_tokens`는 추가하지 않는다. 이미 설정한 두 generation control만으로 개선이 확인됐고, hard cap은
reasoning과 visible output을 함께 제한해 Structured Output truncation 위험을 높일 수 있다.

실측과 해석은 [OpenAI reasoning effort latency experiment](docs/performance/openai-reasoning-effort-experiment-2026-09-16.md)를 참고합니다.

## 제한된 Benchmark Seed (개발 전용)

`BenchmarkSeedManualSmokeTest`는 소량의 제한된 Benchmark sample을 추가하기 위한 명시적 opt-in 개발용 harness다.
이는 public endpoint, startup runner, scheduler, crawler 또는 운영 수집 정책이 아니다. 수집 범위는 KR
`RANKED_SOLO_5x5` / queue 420으로 고정하며, Riot 요청 전에 tier, division, page 범위, player 예산,
player당 match 예산을 모두 받는다.

먼저 local PostgreSQL 및 Redis 의존성을 실행하고 application 연결 변수와 Riot key를 설정한 뒤, 의도적으로 작은
범위를 선택한다. 첫 실행은 일반적으로 page 하나, 최대 player 10명, player당 match 5건을 사용한다.

```powershell
docker compose up -d
$env:POSTGRES_HOST = "localhost"
$env:POSTGRES_PORT = "5432"
$env:POSTGRES_DB = "lol_insight"
$env:POSTGRES_USER = "lol_insight"
$env:POSTGRES_PASSWORD = "lol-insight-local"
$env:REDIS_HOST = "localhost"
$env:REDIS_PORT = "6379"
$env:RIOT_API_KEY = "your-development-riot-api-key"
$env:RUN_BENCHMARK_SEED = "true"
$env:BENCHMARK_SEED_TIER = "GOLD"
$env:BENCHMARK_SEED_DIVISION = "I"
$env:BENCHMARK_SEED_START_PAGE = "1"
$env:BENCHMARK_SEED_PAGE_COUNT = "1"
$env:BENCHMARK_SEED_PLAYER_LIMIT = "10"
$env:BENCHMARK_SEED_MATCHES_PER_PLAYER = "5"
.\gradlew.bat test --tests "*BenchmarkSeedManualSmokeTest" --no-daemon
```

`RUN_BENCHMARK_SEED`를 제외한 모든 `BENCHMARK_SEED_*` 값은 위의 작은 기본값을 사용한다. 전자는 반드시 정확히
`true`여야 한다. 잘못된 값은 seed가 Riot 요청을 보내기 전에 거부한다. 일반 `gradle test` 실행은
`RUN_BENCHMARK_SEED=true`와 `RIOT_API_KEY`가 모두 없으면 이 test를 비활성화하므로 Riot을 호출하지 않는다.

seed는 요청한 1-based League-V4 page만 순회하고 빈 page에서 멈춘다. 각 page의 PUUID를 정렬해 처리 순서를
결정적으로 유지하며, 각 PUUID는 기존 collector에 최대 한 번만 전달한다. Riot 429가 발생하면 retry나 sleep 없이
run을 중단한다. 이후 discovery page와 새로운 collection 작업은 시작하지 않지만, 이미 저장된 sample은 유지한다.
manual 출력은 Riot key, PUUID 목록, Match ID 목록을 절대 포함하지 않고 total과 일부 cohort coverage row만 보고한다.

coverage는 저장된 전체 corpus를 exact cohort(`region`, `queueId`, `tier`, `division`, `position`, `championId`)로
grouping하고 기존 30 samples / 10 unique players availability policy를 적용한다. 이는 대표성 있는 운영 benchmark의
근거가 아니라 개발용 후보를 찾기 위한 운영 정보다. coverage에는 미래 analysis target을 제외하지 않는다. target을
선택한 뒤 `/analysis`를 실행하려면 `findBenchmarkExcludingPlayer(cohort, targetPuuid)` 결과도 `AVAILABLE`인지
확인해야 한다. threshold에 정확히 맞는 row보다 30/10보다 충분한 여유가 있는 row를 우선한다.

## Peer Benchmark 범위 v0.2

Peer Benchmark은 `POSITION`(`region / queueId / tier / division / position`)과 `CHAMPION_POSITION`(기존 key에
`championId` 추가)이라는 두 독립 scope를 제공한다. Player는 scope가 일치하는 comparison을 각각 받으며,
AVAILABLE role-level 결과가 insufficient champion-specific 결과를 조용히 대체하지 않는다. 기존 availability policy는
scope별 target-player self-exclusion 후 30 match observations와 10 unique players를 요구한다.

`POSITION`은 해당 role의 champion mix를 포함하므로 role-level baseline으로만 사용한다. Champion-specific skill
baseline으로 표현하지 않는다. OpenAI analysis는 AVAILABLE인 두 scope를 하나의 요청에 함께 전달하되, scope를
명시하고 서로를 fallback으로 표현하거나 수치를 섞지 않는다. 자세한 내용은 [ADR-008](docs/adr/008-use-explicit-benchmark-scopes.md),
[Peer Benchmark v0.2](docs/benchmark/peer-benchmark-v0.2.md),
[Player Comparison Context v0.2](docs/ai/player-comparison-context-v0.2.md),
[Player Comparison Feature v0.2](docs/ai/player-comparison-feature-v0.2.md),
[Player Analysis v0.2](docs/ai/player-analysis-v0.2.md)를 참고한다.

## 개발 스모크 절차

실제 development Riot key로 작은 수집을 확인할 때는 IDE의 dev-only evaluation 또는 임시 local harness에서
`RankedPlayerDiscoveryService.discover("GOLD", "I", 2)`의 결과를
`BenchmarkMatchCollectionService.collect(players, 2)`에 전달합니다. 이 흐름은 `RIOT_API_KEY`와 local PostgreSQL/Redis를
필요로 하며, endpoint·scheduler·startup runner를 추가하지 않습니다. 결과는 representative GOLD benchmark가 아니라
작은 연결 확인용 표본으로만 해석합니다.
