# ADR-010: 단일 인스턴스 분석 생성 요청에 인메모리 rate limit 사용

상태: Accepted

## 배경

sync analysis와 async analysis job은 모두 Riot pipeline과 OpenAI generation을 실행한다. client가 두 POST를
반복 호출하거나 endpoint별로 별도 quota를 사용하면 외부 API 비용을 우회해 증가시킬 수 있다. 현재는 사용자 계정과
Spring Security가 없고 async execution도 같은 JVM의 bounded executor를 사용하는 MVP다.

따라서 현재 단계에는 client identity를 HTTP remote address에서 얻되, 인증 도입 후 `userId`로 교체할 수 있는 경계가
필요하다. 동시에 무제한 client key가 process memory에 남지 않아야 하며, 다중 인스턴스용 분산 운영 복잡도는 아직
도입하지 않는다.

## 결정

- `AnalysisRateLimitKeyResolver`가 Servlet `remoteAddr`를 `analysis-generation:{clientKey}`로 변환한다. application
  flow와 limiter는 HTTP request를 받지 않는다.
- 임의의 `X-Forwarded-For` header는 신뢰하지 않는다. trusted proxy와 Spring forward-header strategy를 명시적으로
  구성한 배포에서만 resolver가 보는 remote address의 의미를 바꾼다.
- Bucket4j local token bucket과 Caffeine `expireAfterAccess(window)` cache 및 library scheduler를 사용한다.
  Bucket4j가 동시 소비를 처리하고 Caffeine이 유휴 bucket을 정리한다. 별도 DB persistence, rate-limit metric,
  IP logging, IP metric tag는 추가하지 않는다.
- 기본 quota는 client당 3회/1분이다. `ANALYSIS_RATE_LIMIT_CAPACITY`와 `ANALYSIS_RATE_LIMIT_WINDOW`으로
  override한다. interval refill을 사용하므로 세 token을 모두 소비하면 다음 1분 interval에 refill된다.
- sync `/analysis`와 async `/analysis-jobs`는 같은 bucket을 먼저 확인한다. async 요청은 job row 생성과 executor
  dispatch 전에 확인하므로 quota 초과 요청은 DB row, queue, Riot API, OpenAI API를 사용하지 않는다.
- 초과 응답은 `429 Too Many Requests`, `Retry-After`, `ANALYSIS_RATE_LIMIT_EXCEEDED` safe code를 사용한다.
  `Retry-After`는 limiter가 제공하는 다음 refill까지의 시간을 초 단위로 올림한다.
- limiter 통과 시 token을 소비한다. provider 실패, timeout, provider 429, analysis 실패, executor rejection에 대해
  refund하지 않는다. executor capacity는 기존의 `503`/`CAPACITY_EXCEEDED` semantics를 유지한다.

## 대안

직접 `ConcurrentHashMap` 기반 token bucket을 구현하는 방법은 동시성, retry time, clock test, key eviction 책임을
새로 만들어야 하므로 선택하지 않았다. 검증된 Bucket4j를 사용해 limiter algorithm을 직접 소유하지 않는다.

처음부터 Redis-backed distributed limiter를 도입하는 방법은 다중 인스턴스에서 정확한 전역 quota를 제공하지만, 현재
single-process executor MVP에는 Redis atomic operation, failure policy, 운영 및 테스트 복잡도를 추가한다. 실제
horizontal scaling이 필요한 시점까지 보류한다.

client가 보내는 `X-Forwarded-For`를 그대로 key로 쓰는 방법은 누구나 값을 변경해 quota를 우회할 수 있으므로
선택하지 않았다.

## 결과

현재 인스턴스 하나에서는 두 generation endpoint를 통한 비용 우회를 막고, 유휴 client key의 memory accumulation을
피한다. 기본 3회/1분은 worker 1개와 queue 2개에 맞춘 비용 보호 heuristic이며 provider의 보장된 처리량은 아니다.

인스턴스가 여러 개면 각 인스턴스가 독립 bucket을 가지므로 전역 client quota가 아니다. 다중 인스턴스 배포 전에는
Redis atomic rate limiter, API Gateway 또는 WAF를 선택해 이 ADR을 대체하거나 보완해야 한다. 인증 도입 시에는
resolver의 입력만 authenticated `userId`로 바꿔 같은 quota boundary를 유지한다.
