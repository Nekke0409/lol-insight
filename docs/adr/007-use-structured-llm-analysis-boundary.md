# ADR-007: Use a Structured LLM Analysis Boundary

Status: Accepted

## Context

Peer Benchmark v0.1은 exact cohort의 match-level observation aggregate를 제공하고,
`PlayerComparisonFeature`가 deterministic하게 availability와 metric difference를 계산한다.
자유 형식 LLM text parsing이나 LLM에게 cohort/통계 계산을 맡기면 result contract와 statistical semantics를 보장하기 어렵다.

## Decision

`PlayerAnalysisGenerator`를 application의 유일한 provider-independent boundary로 둔다.
`PlayerAnalysisService`는 AVAILABLE comparison이 하나 이상일 때만 generator를 한 번 호출한다.
Backend가 cohort, sample eligibility, benchmark aggregate와 numeric difference를 계속 source of truth로 유지한다.

OpenAI 구현은 infrastructure에 두고 Responses API Structured Outputs와 SDK class-based JSON Schema를 사용한다.
OpenAI schema DTO는 infrastructure 전용 Java DTO이며, 결과는 provider-independent `PlayerAnalysisResult`로 즉시 변환한다.

## Consequences

- HTTP/application/domain 계층은 OpenAI SDK type과 raw provider failure에 의존하지 않는다.
- analysis result는 markdown/text parsing 대신 구조화된 contract를 가진다.
- AVAILABLE comparison이 없을 때 비용과 LLM hallucination surface가 발생하지 않는다.
- provider 교체가 필요해지면 `PlayerAnalysisGenerator` 구현을 추가할 수 있지만, 현재는 multi-provider factory나 strategy hierarchy를 만들지 않는다.
- cache, persistence, retry/backoff, streaming은 이 결정에 포함하지 않는다.
