# ADR-003: Preserve Riot Rate-Limit Metadata and Translate Endpoint-Specific Not Found Errors

Status: Accepted

## Context

`RiotApiHttpClient`는 Riot HTTP 오류의 status code와 response body를 보존하지만,
429 응답의 `Retry-After` header는 보존하지 않았다. 따라서 우리 API가 rate limit을
의미 있게 응답하거나 후속 호출 정책을 구현할 근거가 없었다.

또한 Riot의 404는 Account-V1 player lookup과 Match-V5 single Match Detail lookup에서
서로 다른 서비스 의미를 가진다. GlobalExceptionHandler가 Riot status code만으로 이를
구분하면 HTTP 계층이 Riot endpoint 세부사항을 알아야 한다.

## Decision

공통 Riot HTTP client는 `RiotApiResponseException`에 유효한 `Retry-After` 정수 초 값을
선택적으로 보존한다. 원본 response header 전체는 보존하거나 외부 응답에 전달하지 않는다.

endpoint별 Client는 자신이 호출한 endpoint의 404만 feature의 내부 not-found 예외로 변환한다.
Account-V1 404는 `PlayerNotFoundException`, Match-V5 single Match Detail 404는
`MatchNotFoundException`으로 변환한다.

GlobalExceptionHandler는 내부 not-found 예외를 404로 변환하고, Riot 429만 429로 변환한다.
이때 보존된 초 값이 있으면 `Retry-After` response header에 전달한다. Riot 원본 response body,
API key 관련 정보, 내부 exception detail과 다른 upstream header는 응답에 노출하지 않는다.

## Result

```text
Riot 429
    -> RiotApiResponseException(retryAfterSeconds)
    -> GlobalExceptionHandler
    -> HTTP 429 + Retry-After (when valid)

Account-V1 404
    -> RiotAccountClient
    -> PlayerNotFoundException
    -> HTTP 404

Match-V5 Detail 404
    -> RiotMatchClient
    -> MatchNotFoundException
    -> HTTP 404
```

## Reason

- endpoint context가 있는 곳에서만 404의 서비스 의미를 정확히 판별할 수 있다.
- GlobalExceptionHandler가 Riot API endpoint 세부사항에 결합되지 않는다.
- rate-limit 대기 시간을 명확한 초 단위 값으로 보존하면서도 외부 header를 무분별하게 노출하지 않는다.
- 이후 최근 경기 다건 조회에서 호출 scheduling과 cooldown 정책을 추가할 수 있는 최소 정보를 제공한다.

## Alternatives Considered

### GlobalExceptionHandler에서 Riot 404를 구분

장점:

- feature Client의 예외 변환 코드가 없다.

단점:

- HTTP 계층이 Account와 Match endpoint를 판별해야 한다.
- 새로운 404 endpoint가 추가될 때 전역 handler가 Riot API 세부사항에 계속 결합된다.

선택하지 않은 이유:

- endpoint 의미를 가진 Client가 변환하는 현재 구조가 architecture의 외부 Client 경계와 더 맞는다.

### Riot response header 전체를 보존하고 전달

장점:

- 향후 필요한 header를 추가 파싱하지 않아도 된다.

단점:

- 우리 API 계약이 Riot의 내부 header와 불필요하게 결합된다.
- 외부로 전달해도 되는 header를 별도로 통제해야 한다.

선택하지 않은 이유:

- 이번 요구사항에는 429의 `Retry-After` 초 값만 필요하다.

## Consequences

### Positive

- player와 single Match Detail의 not-found 응답이 API 사용자에게 정확한 404가 된다.
- Riot 429의 대기 정보가 안전하게 우리 API 응답으로 전달된다.
- transport failure와 upstream response failure를 서로 다른 HTTP status로 다룬다.

### Negative / Trade-offs

- endpoint별 Client는 자신의 404 의미를 명시적으로 유지해야 한다.
- `Retry-After`가 없거나 정수 초로 파싱되지 않으면 downstream client에 대기 시간은 전달되지 않는다.

## Follow-up

- [ ] 최근 경기 다건 Detail 조회 전에 429 시 새 Detail scheduling을 중단하는 정책을 결정한다.
- [ ] JVM process-wide cooldown의 범위와 동시성 제어 방식을 별도 결정한다.
- [ ] retry, exponential backoff, jitter 도입 필요성을 실제 관측 결과를 기반으로 결정한다.
