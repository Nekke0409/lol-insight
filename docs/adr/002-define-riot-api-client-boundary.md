# ADR-002: Define a Shared Riot API Client Boundary

Status: Accepted

## Context

Riot Games API의 Account-V1, Summoner-V4, League-V4, Match-V5는 서로 다른 기능에서 사용될 예정이지만,
모두 API Key 인증, 외부 HTTP 호출, 오류 상태 처리라는 공통 관심사를 가진다.

또한 endpoint에 따라 platform routing host와 regional routing host를 구분해야 한다.
이 차이를 각 서비스나 Controller가 직접 처리하면 API Key 노출, 잘못된 host 선택,
그리고 외부 API 오류 처리의 불일치가 발생할 수 있다.

현재 애플리케이션은 blocking Spring MVC 구조이며, 아직 endpoint별 DTO와 도메인 use case가 없다.

## Decision

공통 Riot API 통신 경계를 `global/riot`에 둔다.

- `RiotApiProperties`가 환경 변수 `RIOT_API_KEY`, 기본 routing host, connect/read timeout을 설정으로 바인딩한다.
- Spring `RestClient`를 공통 blocking HTTP client로 사용하고 `X-Riot-Token` 헤더 및 `SimpleClientHttpRequestFactory`의 connect/read timeout을 기본 적용한다.
- `RiotApiRouting`으로 platform과 regional routing을 명시적으로 선택한다.
- `RiotApiHttpClient`는 GET 요청의 URI 조합과 HTTP 통신을 담당한다.
- Riot HTTP 오류는 상태 코드와 응답 본문을 보존하는 외부 API 예외로 변환한다.

이 단계에서는 endpoint별 Client, Riot 응답 DTO, 재시도, Rate Limit 대기, 캐시, 서비스 HTTP 오류 매핑을 구현하지 않는다.
각 endpoint Client는 필요해질 때 feature 패키지에 추가하고 공통 전송기를 사용한다.

## Reason

- API Key와 인증 헤더 처리를 한 곳에 제한할 수 있다.
- endpoint별 routing 선택을 호출 코드에서 명시할 수 있다.
- Spring MVC의 동기 실행 방식에 맞고 새 HTTP 라이브러리를 추가하지 않는다.
- 연결 또는 응답 지연으로 요청 thread가 장시간 점유되는 것을 막을 수 있다.
- 실제 외부 네트워크 없이 URL, 헤더, 오류 변환을 테스트할 수 있다.
- 아직 존재하지 않는 endpoint DTO나 provider 교체 요구를 위해 과도한 추상화를 만들지 않는다.

## Alternatives Considered

### Endpoint별 Client가 각자 HTTP 요청 수행

초기 파일 수는 적지만 인증, host 선택, 오류 처리 코드가 반복되고 일관성이 깨질 수 있어 선택하지 않는다.

### WebClient 또는 별도 HTTP 라이브러리 도입

비동기 처리 요구가 아직 없고 현재 Spring MVC 구조와 맞지 않아 선택하지 않는다.

### 범용 HTTP 추상화 계층 도입

Riot API 외의 모든 HTTP 통신을 하나의 자체 framework로 감싸는 방식은 현재 요구보다 복잡하다.
공통 Riot API 전송 책임에만 범위를 제한한다.

## Consequences

### Positive

- 이후 Account, Summoner, League, Match Client가 일관된 인증과 routing 방식을 사용한다.
- 외부 API의 실패를 서비스 오류와 구분할 수 있는 기반이 생긴다.
- Riot API 호출 단위 테스트가 실제 Riot API Key나 네트워크 없이 가능하다.

### Negative / Trade-offs

- 현재는 기본 host를 KR platform 및 ASIA regional로 둔다. 다른 shard 지원이 필요하면 설정값 또는 routing 모델 확장이 필요하다.
- timeout 값은 현재 호출 패턴이 없는 단계의 보수적인 기본값(연결 2초, 응답 5초)이다. 운영 관측 결과에 따라 환경별로 조정해야 한다.
- Rate Limit, 재시도, 캐시는 별도 요구가 확인될 때 추가해야 한다.

## Revisit Conditions

- 다른 platform 또는 regional routing을 사용자 선택으로 지원해야 할 때
- Rate Limit 응답을 기반으로 재시도 또는 대기 정책이 필요할 때
- 다수의 HTTP method 또는 대용량 응답 처리 요구가 반복될 때
- blocking 호출이 서비스 처리량의 실제 병목으로 확인될 때
