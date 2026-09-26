# 문서

이 디렉터리는 프로젝트의 현재 구조와 중요한 기술적 의사결정을 기록합니다.

현재 구현과 설정의 source of truth는 code, `README.md`, `docs/architecture.md`, `docs/adr/`이다. Project Memory나
AI assistant context는 저장소의 실제 상태를 대체하지 않는다. `architecture.md`는 현재 구조와 검증된 미래 경계를,
ADR은 당시의 결정과 trade-off를 기록한다. 이미 Accepted인 ADR을 현재 roadmap에 맞춘다는 이유만으로 다시 쓰지 않는다.

## 문서 목록

### 아키텍처

[`architecture.md`](architecture.md)

현재 시스템이 어떤 방향으로 구성되어 있는지 설명하는 **living document**입니다.

코드 구조에 중요한 변경이 생기면 이 문서도 현재 코드 상태에 맞게 수정합니다.

### Riot API

[`riot-api-integration.md`](riot-api-integration.md)

Riot API endpoint별 routing host와 용도를 빠르게 확인하는 참고 문서입니다.

### AI 및 Benchmark 입력

- [`ai/player-analysis-feature-v0.1.md`](ai/player-analysis-feature-v0.1.md): 개인 요약 feature의 범위
- [`ai/player-comparison-context-v0.1.md`](ai/player-comparison-context-v0.1.md): 초기 peer comparison 사용자 입력 contract
- [`ai/player-analysis-v0.2.md`](ai/player-analysis-v0.2.md): 현재 Structured Output 분석의 input/output과 gate
- [`ai/async-player-analysis-jobs-v0.1.md`](ai/async-player-analysis-jobs-v0.1.md): async job lifecycle, polling, dedupe와 recovery 제한

### Peer Benchmark v0.2

- [`benchmark/peer-benchmark-v0.2.md`](benchmark/peer-benchmark-v0.2.md): 명시적인 `POSITION`,
  `CHAMPION_POSITION` aggregate, coverage, bounded replenishment·후보 선택·preflight와 한계
- [`ai/player-comparison-context-v0.2.md`](ai/player-comparison-context-v0.2.md): scope에 맞춘 user statistic
- [`ai/player-comparison-feature-v0.2.md`](ai/player-comparison-feature-v0.2.md): fallback 없는 독립 comparison 결과
- [`adr/008-use-explicit-benchmark-scopes.md`](adr/008-use-explicit-benchmark-scopes.md): scope 결정 기록

### Tool-using AI Agent v0.2

- [`agent/tool-using-agent-v0.2.md`](agent/tool-using-agent-v0.2.md): 조건부 패치 노트 retrieval Tool, `knowledgeScope`, citation, 실행/비용/보안 경계

### 공식 패치 노트 retrieval v0.1

- [`rag/patch-note-retrieval-v0.1.md`](rag/patch-note-retrieval-v0.1.md): local snapshot 정제·chunking·embedding 경계,
  opt-in pgvector migration, revision 및 검색 계약과 검증 한계
- [`rag/patch-note-retrieval-25-10-ko-kr-evaluation-2026-09-22.md`](rag/patch-note-retrieval-25-10-ko-kr-evaluation-2026-09-22.md):
  공식 한국어 25.10 snapshot 한 건의 실제 embedding·검색 평가 기록

### 성능 기준선

[`performance/recent-matches-latency-baseline.md`](performance/recent-matches-latency-baseline.md)

실제 Riot API를 사용하는 local development 환경에서 최근 경기 endpoint의 순차 구현 latency를
반복 측정하고 기록하는 절차입니다.

### 배포

- [`deployment/single-instance-private-v0.1.md`](deployment/single-instance-private-v0.1.md): 단일 EC2의 private
  Docker Compose 배포, SSM port forwarding, backup/restore와 운영 한계

### 아키텍처 결정 기록

[`adr/`](adr/)

프로젝트에서 장기간 영향을 주는 중요한 기술적 결정을 기록합니다.

ADR은 "현재 구조가 무엇인가"보다 다음 질문에 답하는 기록입니다.

- 어떤 문제가 있었는가?
- 어떤 선택을 했는가?
- 왜 그 선택을 했는가?
- 어떤 대안을 고려했는가?
- 선택의 결과와 trade-off는 무엇인가?

현재 구조 자체를 빠르게 파악할 때는 `architecture.md`,
그 구조가 만들어진 이유를 추적할 때는 ADR을 사용합니다.

Automation의 현재 contract는 [`automation/new-ranked-match-analysis-v0.1.md`](automation/new-ranked-match-analysis-v0.1.md)에,
Tool-using Agent v0.2의 현재 contract는 [`agent/tool-using-agent-v0.2.md`](agent/tool-using-agent-v0.2.md)에 기록한다.

## 현재 결정

- [`ADR-001: 초기 아키텍처에 Modular Monolith 사용`](adr/001-use-modular-monolith.md)
- [`ADR-002: 공용 Riot API Client 경계 정의`](adr/002-define-riot-api-client-boundary.md)
- [`ADR-003: Riot Rate Limit 메타데이터 보존 및 Endpoint별 Not Found 오류 변환`](adr/003-preserve-riot-rate-limit-and-not-found-errors.md)
- [`ADR-004: 애플리케이션 관리 Executor로 최근 Match Detail Fan-out 제한`](adr/004-bound-match-detail-fan-out.md)
- [`ADR-005: 완료된 Match Detail을 Redis에 캐시`](adr/005-cache-completed-match-details-in-redis.md)
- [`ADR-006: 상대 플레이어 분석에 표본 기반 Peer Benchmark 사용`](adr/006-use-sampled-peer-benchmark.md)
- [`ADR-007: Structured LLM analysis boundary 사용`](adr/007-use-structured-llm-analysis-boundary.md)
- [`ADR-008: 명시적 benchmark scope 사용`](adr/008-use-explicit-benchmark-scopes.md)
- [`ADR-009: 비동기 Player Analysis Job 도입`](adr/009-introduce-asynchronous-player-analysis-jobs.md)
- [`ADR-010: 단일 인스턴스 분석 생성 요청에 인메모리 rate limit 사용`](adr/010-use-in-memory-analysis-generation-rate-limit.md)
- [`ADR-011: 구조화된 분석 입력 기반 Redis completed-result cache 사용`](adr/011-cache-completed-analysis-results-by-input.md)
- [`ADR-012: Persisted Ranked Solo Match Automation Trigger 사용`](adr/012-use-persisted-ranked-match-automation-trigger.md)
- [`ADR-013: JVM-local Riot outbound cooldown 사용`](adr/013-use-jvm-local-riot-outbound-cooldown.md)
- [`ADR-014: Peer Benchmark에 경기 시작 시각 기반 유효 표본 기간 사용`](adr/014-use-game-start-validity-window-for-peer-benchmark.md)
- [`ADR-015: Coverage-driven bounded Benchmark Replenishment 사용`](adr/015-use-coverage-driven-benchmark-replenishment.md)
- [`ADR-016: Single-instance Private Deployment v0.1 준비`](adr/016-prepare-single-instance-private-deployment.md)
- [`ADR-017: Bounded Tool-using Agent 경계 사용`](adr/017-use-bounded-tool-using-agent.md)
- [`ADR-018: 유효 표본 수 기반 Benchmark 수집 후보 우선순위 사용`](adr/018-prioritize-benchmark-collection-candidates-by-valid-sample-count.md)
- [`ADR-020: Opt-in pgvector 패치 노트 retrieval v0.1 사용`](adr/020-use-opt-in-pgvector-patch-note-retrieval.md)
- [`ADR-021: Backend에서 RAG citation 검증`](adr/021-validate-rag-answer-citations-at-backend.md)
- [`ADR-022: Agent가 패치 노트 retrieval Tool을 통해 문서 근거 사용`](adr/022-connect-agent-to-patch-note-retrieval.md)
