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
7. 실제 필요가 확인된 경우에만 RAG / Vector Search
8. 배포와 확장 구조의 진화

일반적인 게시글·댓글 중심 community CRUD는 현재 핵심 로드맵에 포함하지 않습니다. 사용자 계정과 인증은 automation 설정, 분석 이력, 개인화, job ownership, Agent 개인화에 실제로 필요해지는 시점에 도입합니다.

Riot의 공식 Ranked Ladder를 대체하는 MMR, ELO 또는 자체 Skill Rating은 만들지 않습니다.

## 현재 구현과 향후 방향

| 영역 | 현재 구현 | 향후 방향 |
| --- | --- | --- |
| Riot 데이터 | Account-V1 Riot ID 조회, Match-V5 Match ID/Detail 조회, League-V4 기반 KR Ranked Solo 표본 player discovery | representative sampling, scheduled collection 정책 |
| 데이터 처리·통계 | Riot DTO 정규화, 일반 전적용 최근 전체 경기 통계와 AI comparison용 최근 Ranked Solo 경기 통계, participant-level `BenchmarkSample` 저장 | 추가 분석 feature와 freshness 정책 |
| Peer Benchmark | exact cohort 집계, target self-exclusion, role/champion-position scope 비교 | 표본 품질과 coverage 개선 |
| AI 분석 | `PlayerAnalysisInput` fingerprint 기반 Redis completed-result cache, OpenAI Responses API Structured Outputs, sync·async 제공 | 결과 품질 평가, 인증 사용자 quota, provider 전략 |
| 운영 경계 | Redis Match Detail·analysis result cache, PostgreSQL/Flyway, OpenAI usage·latency 계측, 분석 생성 rate limit, bounded async job과 in-flight dedupe | crash recovery, distributed 운영 정책, 배포·확장 구조 |
| AI Automation | 구현하지 않음 | 명시적 trigger와 Backend rule을 기반으로 기존 분석 job을 재사용 |
| Tool-using Agent | 구현하지 않음 | Application Service를 감싼 Backend Tool로 질의 응답을 구성 |
| RAG / Vector Search | 구현하지 않음 | 비정형 지식 검색이 실제 필요할 때 PostgreSQL + pgvector부터 검토 |

completed-result cache는 `PlayerAnalysisInput`의 결정적인 JSON을 SHA-256 fingerprint로 만든 Redis key
`analysis:result:{version}:{fingerprint}`에 성공한 `PlayerAnalysisResult`만 저장합니다. URL, Riot ID, PUUID, match ID는
key나 value에 넣지 않습니다. sync와 async worker는 같은 `PlayerAnalysisService`를 거치므로 이 cache를 공유합니다.

일반 전적·통계 조회의 최근 경기는 queue를 지정하지 않은 전체 Match-V5 목록을 사용합니다. 반면 AI comparison은
`queue=RankedSoloQueue.ID`로 조회한 최근 **Ranked Solo** 목록만 사용하며, `start`와 `count`도 그 filtered 목록의
pagination입니다. Detail 응답은 comparison 통계 직전에 다시 queue를 검증하므로, upstream 목록에 잘못 섞인 Flex·일반·ARAM
경기는 games, 승률, 7개 지표와 AI 입력에 포함되지 않습니다.

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

Tool-using Agent는 사용자의 질문에 따라 LLM이 명시적인 Backend Tool을 선택하고, Tool이 기존 Application Service를 호출한 구조화된 결과를 반환하는 형태로 시작합니다. Agent가 Riot HTTP client, PostgreSQL repository, Redis, OpenAI SDK 같은 infrastructure를 직접 다루지 않습니다.

구조화된 플레이어·경기 데이터는 Backend Tool로 조회합니다. RAG는 patch note, 챔피언·아이템 문서 같은 비정형 지식이 필요할 때의 선택지이며 Agent의 선행 조건이 아닙니다. 초기 도입이 필요하면 기존 PostgreSQL과의 운영 일관성을 위해 pgvector를 우선 검토하되, 지금 이를 필수 dependency로 선언하지 않습니다.

LangChain, LangGraph, 별도 Vector DB 같은 framework는 실제 복잡도를 해결해야 하는 시점에만 도입합니다. 초기에는 OpenAI Tool Calling, 명시적 Tool 정의, 직접 작성한 dispatcher와 bounded Agent loop를 우선 검토합니다.

## 기술 스택

현재 사용 중인 기술은 Kotlin, Spring Boot, JDK 21, Spring MVC `RestClient`, PostgreSQL, Spring Data JPA, Flyway, Redis, Caffeine, Bucket4j, Docker, OpenAI Java SDK입니다.

Spring Security, AWS, 인증 사용자 기준 quota, 다중 LLM Provider, AI Automation, Tool-using Agent, RAG는 현재 구현 범위가 아닙니다.

## 문서

- [문서 안내](docs/README.md): 문서별 목적과 현재 결정
- [아키텍처](docs/architecture.md): 현재 구현, 의존 방향, 미래 확장 경계
- [ADR](docs/adr/README.md): 시점별 기술 의사결정 기록
- [플레이어 분석 v0.2](docs/ai/player-analysis-v0.2.md): Structured Output 분석 contract
- [Async Player Analysis Job v0.1](docs/ai/async-player-analysis-jobs-v0.1.md): polling, lifecycle, dedupe와 recovery 제한
- [Peer Benchmark v0.2](docs/benchmark/peer-benchmark-v0.2.md): scope와 availability 정책

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

`local` profile은 PostgreSQL의 로컬 기본값(`localhost:5432`, database/user `lol_insight`)을 사용합니다. `POSTGRES_HOST`, `POSTGRES_PORT`, `POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD`로 값을 덮어쓸 수 있으며 production에서는 모든 값을 환경변수로 제공해야 합니다.

구체적인 OpenAI runtime·관측성 설정, Benchmark seed 절차, 성능 측정 방법은 해당 [아키텍처 문서](docs/architecture.md), [ADR](docs/adr/README.md), [성능 문서](docs/performance/)를 참고합니다.
