# LOL Insight

LOL Insight는 Riot Games 데이터를 Backend에서 결정적으로 정규화·계산하고, 생성형 AI가 그 결과를 해석해 플레이어 피드백을 제공하는 **AI 기반 플레이어 분석 워크플로우**입니다.

단순 전적 조회 서비스가 아닙니다. 외부 API 연동, 데이터 정합성, Rate Limit, 캐싱, 통계 계산, AI 비용과 실패 처리를 실제 운영 가능한 Backend 관점에서 다룹니다.

## 프로젝트 방향

프로젝트는 다음 순서로 확장합니다.

1. Riot 데이터 조회와 플레이어 통계
2. Peer Benchmark 수집·집계와 상대 비교
3. 비교 feature를 해석하는 AI 분석·피드백
4. 운영 안정성, 비용 통제, 관측성 강화
5. AI Automation
6. LLM 기반 Tool-using AI Agent
7. 공식 패치 노트 retrieval 기반과 수동 품질 평가
8. 평가 근거가 확인된 경우에만 RAG 답변 생성·Agent 연결 및 비공개 배포 재개

일반적인 게시글·댓글 중심 community CRUD는 현재 핵심 로드맵에 포함하지 않습니다. 사용자 계정과 인증은 automation 설정, 분석 이력, 개인화, job ownership, Agent 개인화에 실제로 필요해지는 시점에 도입합니다.

Riot의 공식 Ranked Ladder를 대체하는 MMR, ELO 또는 자체 Skill Rating은 만들지 않습니다.

EMERALD IV 실제 수집·본인 계정 분석, 로컬 비공개 배포와 AWS 작업은 보류합니다. 기존 배포 문서는 준비된
single-instance v0.1의 범위와 절차를 기록할 뿐, 이번 우선순위에서 실제 배포를 진행한다는 뜻이 아닙니다.

## 현재 구현과 향후 방향

| 영역 | 현재 구현 | 향후 방향 |
| --- | --- | --- |
| Riot 데이터 | Account-V1 Riot ID 조회, Match-V5 Match ID/Detail 조회, League-V4 기반 KR Ranked Solo 표본 player discovery | representative sampling 정책 |
| 데이터 처리·통계 | Riot DTO 정규화, 일반 전적용 최근 전체 경기 통계와 AI comparison용 최근 Ranked Solo 경기 통계, participant-level `BenchmarkSample` 저장 | 추가 분석 feature |
| Peer Benchmark | 최근 30일 유효 표본 집계, target self-exclusion, role/champion-position scope 비교, 유효 표본 수 우선의 bounded 후보 선택과 POSITION-first replenishment | 표본 품질과 coverage 개선 |
| AI 분석 | `PlayerAnalysisInput` fingerprint 기반 Redis completed-result cache, OpenAI Responses API Structured Outputs, sync·async 제공 | 결과 품질 평가, 인증 사용자 quota, provider 전략 |
| 운영 경계 | Redis Match Detail·analysis result cache, PostgreSQL/Flyway, OpenAI usage·latency 계측, 분석 생성 rate limit, bounded async job과 in-flight dedupe | crash recovery, distributed 운영 정책, 배포·확장 구조 |
| AI Automation | persisted Ranked Solo cursor·idempotent execution·bounded scheduler와 기존 analysis job 재사용, 429 이후 JVM-local Riot cooldown | distributed scheduler/claim, automation quota, notification |
| Tool-using Agent | 기본 비활성화된 Agent v0.1, Responses API function calling, 최근 20개 Ranked Solo 통계/peer comparison Tool, 전체 deadline·최종 Tool 금지·bounded loop | 실제 model smoke와 질문 품질 검증 후 범위 확대 |
| 공식 패치 노트 retrieval | 기본 비활성화된 local snapshot 정제·heading 보존 chunking·OpenAI embedding 경계·opt-in pgvector exact cosine 검색 | 실제 Korean snapshot 평가 후 답변 생성·Agent 연결 검토 |

completed-result cache는 `PlayerAnalysisInput`의 결정적인 JSON을 SHA-256 fingerprint로 만든 Redis key
`analysis:result:{version}:{fingerprint}`에 성공한 `PlayerAnalysisResult`만 저장합니다. URL, Riot ID, PUUID, match ID는
key나 value에 넣지 않습니다. sync와 async worker는 같은 `PlayerAnalysisService`를 거치므로 이 cache를 공유합니다.

일반 전적·통계 조회의 최근 경기는 queue를 지정하지 않은 전체 Match-V5 목록을 사용합니다. 반면 AI comparison은
`queue=RankedSoloQueue.ID`로 조회한 최근 **Ranked Solo** 목록만 사용하며, `start`와 `count`도 그 filtered 목록의
pagination입니다. Detail 응답은 comparison 통계 직전에 다시 queue를 검증하므로, upstream 목록에 잘못 섞인 Flex·일반·ARAM
경기는 games, 승률, 7개 지표와 AI 입력에 포함되지 않습니다.

Peer Benchmark의 표본은 `BENCHMARK_SAMPLE_MAX_AGE`(기본 `30d`)에 따라 `gameStartTimestamp`가 조회 기준 시각 이전의
최근 30 × 24시간 `[fromInclusive, toExclusive)`에 있는 경우만 집계와 coverage에 포함합니다. 이는 query-time exclusion일
뿐 DB retention·재수집 정책이 아니며, `collectedAt`이나 `rankCapturedAt`로 오래된 경기를 되살리지 않습니다. 사용자의 최근
Ranked Solo 최대 20경기 분석 범위와 peer 표본의 30일 유효기간은 서로 다른 정책입니다.

Benchmark coverage replenishment는 public API나 분석 요청에서 시작하지 않는 내부 workflow입니다.
기본적으로 비활성화되어 있으며, 허용한 `BENCHMARK_REPLENISHMENT_COHORTS`(기본 `GOLD:I`)에서만
POSITION coverage를 다시 평가합니다. tick 하나는 최대 1개 cohort, discovery 1 page, 10명, player당 5경기로
제한하고 PostgreSQL cursor로 page를 회전합니다. `CHAMPION_POSITION`은 수집 결과로 자연스럽게 늘 수 있지만 trigger가
아니며, Riot cooldown·429에서는 추가 수집을 중단하고 자동 retry나 synchronous on-demand seed를 하지 않습니다.

async 요청의 in-flight dedupe는 이 cache와 별개입니다. 같은 client의 같은 HTTP request가 `PENDING` 또는 `RUNNING`일 때만
기존 job을 재사용하며, terminal `AnalysisJob`을 재사용하거나 다른 client에 job ID를 공유하지 않습니다.

## 현재 아키텍처

초기 구조는 하나의 Spring Boot 애플리케이션 안에서 기능별 경계를 나누는 Modular Monolith입니다.

```text
Riot Games API
    -> normalization
    -> deterministic statistics
    -> peer benchmark / comparison feature
    -> PlayerAnalysisInput
    -> completed-result cache
    -> OpenAI Structured Output
    -> PlayerAnalysisResult
```

동기 `POST /analysis`는 기존 분석 pipeline의 결과를 바로 반환합니다. 비동기 `POST /analysis-jobs`는 `AnalysisJob`을 저장한 뒤 bounded worker가 같은 `PlayerAnalysisService`를 실행하고, 클라이언트는 polling으로 결과를 조회합니다.

분석 생성을 보호하기 위해 sync·async POST는 같은 client/IP 기반 quota를 사용합니다. async POST는 quota를 먼저 소비한 뒤 in-flight dedupe를 확인합니다. 따라서 중복 POST도 quota를 소비하지만, dedupe hit은 새 executor 작업이나 Riot/OpenAI 호출을 만들지 않습니다. 구체적인 lifecycle, backpressure, recovery 제한과 rate-limit 정책은 [아키텍처](docs/architecture.md), [ADR-009](docs/adr/009-introduce-asynchronous-player-analysis-jobs.md), [ADR-010](docs/adr/010-use-in-memory-analysis-generation-rate-limit.md)을 따릅니다.

Riot 429를 받으면 공통 HTTP 경계가 `Retry-After` 동안 같은 JVM의 신규 Riot 호출을 보수적으로 멈춥니다. header가 없거나 유효하지 않으면 `RIOT_COOLDOWN_FALLBACK`(기본 `60s`)을 사용합니다. Automation은 이 기간 중 tick을 건너뛰며 cursor를 성공처럼 갱신하지 않습니다. 이 state는 process restart·여러 instance·외부 script와 공유되지 않고, 첫 429를 예방하거나 자동 retry하지 않습니다. 자세한 trade-off는 [ADR-013](docs/adr/013-use-jvm-local-riot-outbound-cooldown.md)을 따릅니다.

## Backend와 LLM의 책임

```text
Riot data
    -> Backend normalization and calculation
    -> structured analysis feature
    -> LLM interpretation and feedback
```

Backend는 win rate, KDA, CS/min, damage/min, 기간 비교, benchmark 집계, availability, threshold와 rule 평가를 계산합니다. LLM은 계산된 데이터를 해석하고, 패턴을 설명하며, 자연어 피드백을 생성합니다.

LLM에 원본 Riot Match JSON을 전달해 핵심 통계를 다시 계산시키지 않습니다. cohort 선택, sample availability, 수치 차이, percentile threshold, MMR 추정도 LLM의 책임이 아닙니다.

## 향후 AI 확장 경계

Automation은 "최근 경기 성과가 유의하게 하락했다"와 같은 조건을 Backend의 기간 비교·통계 rule로 먼저 판단합니다. 조건이 충족되면 기존 AI analysis job을 재사용하고, LLM은 결과를 설명할 뿐 trigger를 임의로 결정하지 않습니다.

Tool-using Agent는 사용자의 질문에 따라 LLM이 명시적인 Backend Tool을 선택하고, Tool이 기존 Application Service를 호출한 구조화된 결과를 반환하는 형태로 시작합니다. Agent가 Riot HTTP client, PostgreSQL repository, Redis, OpenAI SDK 같은 infrastructure를 직접 다루지 않습니다. `CHAMPION_POSITION` 결과는 분석용 `championId`로 서로 다른 챔피언을 구분하지만 PUUID·Riot ID·match ID는 전달하지 않습니다. Agent는 실제 모델 요청과 Tool 실행에 전체 deadline을 적용하며, 마지막 모델 요청에는 추가 Tool을 허용하지 않습니다. 실제 local smoke 1회에서 모델의 통계·비교 Tool 연속 선택과 비교 불가 최종 응답 생성을 확인했지만, 실행 당시 detailed comparison 로그 누락으로 Backend comparison 값과 답변의 직접 대조 또는 `AVAILABLE` 수치 정확성은 확인하지 못했습니다. `AGENT_SMOKE_OBSERVATION_ENABLED=true`의 상세 comparison 출력은 이후 외부 호출 없는 회귀 테스트로 검증하며, 질문·최종 답변·식별자·raw provider 응답은 로그에 남기지 않습니다.

구조화된 플레이어·경기 데이터는 Backend Tool로 조회합니다. RAG는 patch note, 챔피언·아이템 문서 같은 비정형 지식이 필요할 때의 선택지이며 Agent의 선행 조건이 아닙니다. 현재는 공식 패치 노트 local snapshot을 대상으로 한 기본 비활성화 retrieval 기반만 구현했습니다. 기존 PostgreSQL/Flyway 이력과 분리한 opt-in pgvector migration을 사용하며, 최종 답변 생성·Agent 연결과 실제 의미 검색 품질 검증은 아직 구현하지 않았습니다.

즉, 정형 경기 데이터 Tool은 기존 Application Service의 결정적 계산 결과를 제공하고, 문서 검색은 비정형 patch note의
근거 절과 출처를 제공하는 별도 책임입니다. RAG 도입으로 Benchmark availability·비교 안전장치를 완화하거나 대체하지 않습니다.

LangChain, LangGraph, 별도 Vector DB 같은 framework는 실제 복잡도를 해결해야 하는 시점에만 도입합니다. 초기에는 OpenAI Tool Calling, 명시적 Tool 정의, 직접 작성한 dispatcher와 bounded Agent loop를 우선 검토합니다.

## 기술 스택

현재 사용 중인 기술은 Kotlin, Spring Boot, JDK 21, Spring MVC `RestClient`, PostgreSQL, Spring Data JPA, Flyway, Redis, Caffeine, Bucket4j, Docker, OpenAI Java SDK, springdoc-openapi입니다.

Spring Security, AWS, 인증 사용자 기준 quota, 다중 LLM Provider, 최종 RAG 질의응답은 현재 구현 범위가 아닙니다.

## 문서

- [문서 안내](docs/README.md): 문서별 목적과 현재 결정
- [아키텍처](docs/architecture.md): 현재 구현, 의존 방향, 미래 확장 경계
- [ADR](docs/adr/README.md): 시점별 기술 의사결정 기록
- [플레이어 분석 v0.2](docs/ai/player-analysis-v0.2.md): Structured Output 분석 contract
- [Async Player Analysis Job v0.1](docs/ai/async-player-analysis-jobs-v0.1.md): polling, lifecycle, dedupe와 recovery 제한
- [Peer Benchmark v0.2](docs/benchmark/peer-benchmark-v0.2.md): scope와 availability 정책
- [ADR-014](docs/adr/014-use-game-start-validity-window-for-peer-benchmark.md): Peer Benchmark 유효 표본 기간 결정
- [ADR-015](docs/adr/015-use-coverage-driven-benchmark-replenishment.md): bounded benchmark coverage replenishment 결정
- [ADR-018](docs/adr/018-prioritize-benchmark-collection-candidates-by-valid-sample-count.md): 유효 표본 수 기반 bounded 수집 후보 선택 결정
- [Tool-using AI Agent v0.1](docs/agent/tool-using-agent-v0.1.md): Tool 범위, 실행 제한, 보안 경계
- [ADR-017](docs/adr/017-use-bounded-tool-using-agent.md): bounded Tool-using Agent 경계 결정
- [공식 패치 노트 retrieval v0.1](docs/rag/patch-note-retrieval-v0.1.md): local snapshot·pgvector 검색 계약과 검증 한계
- [ADR-020](docs/adr/020-use-opt-in-pgvector-patch-note-retrieval.md): opt-in pgvector retrieval 결정
- [단일 인스턴스 비공개 배포 v0.1](docs/deployment/single-instance-private-v0.1.md): Docker Compose, SSM 접근, 운영·복구 절차와 현재 보류·재개 조건

현재 구현과 설정의 source of truth는 code, `README.md`, `docs/architecture.md`, `docs/adr/`입니다. Project Memory나 AI assistant context는 저장소의 실제 상태를 대체하지 않습니다.

## 로컬 환경

Riot API key는 환경 변수로만 주입합니다. 실제 값을 저장소에 커밋하지 않습니다.

```text
RIOT_API_KEY=your-riot-api-key
```

로컬 개발에서는 PostgreSQL과 Match Detail cache용 Redis를 함께 실행합니다.

```text
docker compose up -d
```

`.env.example`을 `.env`로 복사한 뒤 `RIOT_API_KEY`를 채운다. `.env`는 Git에서 제외되며 `bootRun` 실행 시에만 자동으로 주입된다. shell에 이미 설정된 환경변수는 `.env`보다 우선한다.

```powershell
Copy-Item .env.example .env
.\gradlew.bat bootRun
```

IDE 실행 구성과 일반 `java -jar` 실행은 `.env`를 자동으로 읽지 않으므로, 해당 실행 환경에는 필요한 변수를 별도로 설정한다.

`local` profile은 PostgreSQL의 로컬 기본값(`localhost:5432`, database/user `lol_insight`)을 사용합니다. `POSTGRES_HOST`, `POSTGRES_PORT`, `POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD`로 값을 덮어쓸 수 있으며 production에서는 모든 값을 환경변수로 제공해야 합니다.

## Local API 문서

`local` profile에서만 Swagger UI와 OpenAPI JSON이 활성화됩니다. 기본 profile과 `deploy` profile에서는 둘 다 비활성화되므로 배포 환경에 문서 endpoint가 의도치 않게 노출되지 않습니다.

```powershell
.\gradlew.bat bootRun
```

- Swagger UI: [http://localhost:8080/swagger-ui.html](http://localhost:8080/swagger-ui.html)
- OpenAPI JSON: [http://localhost:8080/v3/api-docs](http://localhost:8080/v3/api-docs)

예를 들어 Swagger UI에서 `GET /api/v1/players/{gameName}/{tagLine}`의 `gameName`에 `ExamplePlayer`, `tagLine`에 `KR1`을 입력한다. `tagLine`에는 Riot ID 구분자인 `#`를 포함하지 않는다.

문서를 열거나 명세를 조회하는 것만으로 Riot/OpenAI 요청은 실행되지 않는다. 다만 Swagger UI에서 플레이어·경기·분석·Agent API를 실제로 호출하면 외부 API 호출과 비용이 발생할 수 있고, 기존 rate limit과 Agent 활성화 정책이 그대로 적용된다. API key는 서버 환경변수로만 설정하며 Swagger UI에 입력하거나 노출하지 않는다.

구체적인 OpenAI runtime·관측성 설정, Benchmark seed 절차, 성능 측정 방법은 해당 [아키텍처 문서](docs/architecture.md), [ADR](docs/adr/README.md), [성능 문서](docs/performance/)를 참고합니다.

## 단일 인스턴스 비공개 배포 v0.1

개발용 `docker-compose.yml`은 그대로 둔다. 비공개 검증 또는 한 대의 EC2에는 별도 `compose.deploy.yaml`만 사용한다. 이 구성은
PostgreSQL·Redis 포트를 host에 게시하지 않고, 애플리케이션만 기본 `127.0.0.1:18080`으로 bind한다.

`deploy.env.example`과 `deploy.secrets.env.example`을 각각 Git이 무시하는 `deploy.env`, `deploy.secrets.env`로 복사해 값을 채운다.
배포용 이미지는 versioned tag로 먼저 만들고, Compose는 build를 수행하지 않는다.

```text
docker build --tag lol-insight:0.1.0 .
docker compose --env-file deploy.env --env-file deploy.secrets.env -f compose.deploy.yaml up -d
```

시작·SSM 접근·backup/restore·재배포 절차와 현재 한계는 [단일 인스턴스 비공개 배포 runbook](docs/deployment/single-instance-private-v0.1.md)을 따른다.
