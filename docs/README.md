# Documentation

이 디렉터리는 프로젝트의 현재 구조와 중요한 기술적 의사결정을 기록합니다.

## Documents

### Architecture

[`architecture.md`](architecture.md)

현재 시스템이 어떤 방향으로 구성되어 있는지 설명하는 **living document**입니다.

코드 구조에 중요한 변경이 생기면 이 문서도 현재 코드 상태에 맞게 수정합니다.

### Riot API

[`riot-api-integration.md`](riot-api-integration.md)

Riot API endpoint별 routing host와 용도를 빠르게 확인하는 참고 문서입니다.

### AI and Benchmark Inputs

- [`ai/player-analysis-feature-v0.1.md`](ai/player-analysis-feature-v0.1.md): 개인 요약 feature의 범위
- [`ai/player-comparison-context-v0.1.md`](ai/player-comparison-context-v0.1.md): 향후 peer comparison의 사용자 측 입력

### Peer Benchmark v0.2

- [`benchmark/peer-benchmark-v0.2.md`](benchmark/peer-benchmark-v0.2.md): 명시적인 `POSITION`,
  `CHAMPION_POSITION` aggregate, coverage, 한계
- [`ai/player-comparison-context-v0.2.md`](ai/player-comparison-context-v0.2.md): scope에 맞춘 user statistic
- [`ai/player-comparison-feature-v0.2.md`](ai/player-comparison-feature-v0.2.md): fallback 없는 독립 comparison 결과
- [`adr/008-use-explicit-benchmark-scopes.md`](adr/008-use-explicit-benchmark-scopes.md): scope 결정 기록

### Performance Baselines

[`performance/recent-matches-latency-baseline.md`](performance/recent-matches-latency-baseline.md)

실제 Riot API를 사용하는 local development 환경에서 최근 경기 endpoint의 순차 구현 latency를
반복 측정하고 기록하는 절차입니다.

### Architecture Decision Records

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

## Current Decisions

- [`ADR-001: Use Modular Monolith for Initial Architecture`](adr/001-use-modular-monolith.md)
- [`ADR-002: Define a Shared Riot API Client Boundary`](adr/002-define-riot-api-client-boundary.md)
- [`ADR-003: Preserve Riot Rate-Limit Metadata and Translate Endpoint-Specific Not Found Errors`](adr/003-preserve-riot-rate-limit-and-not-found-errors.md)
- [`ADR-004: Bound Recent Match Detail Fan-Out with an Application-Managed Executor`](adr/004-bound-match-detail-fan-out.md)
- [`ADR-005: Cache Completed Match Details in Redis`](adr/005-cache-completed-match-details-in-redis.md)
- [`ADR-006: Use a Sampled Peer Benchmark for Relative Player Analysis`](adr/006-use-sampled-peer-benchmark.md)
