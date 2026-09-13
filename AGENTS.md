# AGENTS.md

이 파일은 Codex를 포함한 코딩 에이전트가 이 저장소에서 작업할 때 따라야 할 기본 규칙을 정의한다.

## Project Context

이 프로젝트는 League of Legends 데이터를 활용한 Backend 중심 웹 서비스다.

주요 기능은 다음 순서로 확장한다.

1. Riot Games API를 이용한 플레이어 검색 및 전적/통계 조회
2. Peer Benchmark 기반 상대 분석과 AI 플레이 피드백
3. 사용자 계정, 게시글, 댓글 등을 포함한 League of Legends 커뮤니티

이 프로젝트는 단순한 기능 구현보다 실제 운영 가능한 서비스 수준의 설계와 구현을 목표로 한다.
기능 구현 시 유지보수성, 확장성, 테스트 가능성, 명확한 책임 분리와 실무적인 개발 관행을 우선한다.
AI 기능은 단순한 API 호출 데모가 아니라 사용자에게 실질적인 가치를 제공하도록 설계한다.

## Engineering Priorities

구현 시 다음 항목을 중요하게 고려한다.

- 유지보수성
- 확장성
- 데이터 정합성
- 동시성
- 외부 API Rate Limit
- 캐싱
- 보안
- 테스트 가능성
- 장애 처리
- 운영 비용
- 관측 가능성

다만 학습용 프로젝트라는 점을 고려하여 MVP 단계에서 필요하지 않은
과도한 추상화, 분산 시스템, 마이크로서비스 도입은 피한다.

복잡한 구조가 필요한 경우에는 먼저 현재 문제를 단순한 구조로 해결할 수 있는지 검토한다.

## Default Technology Direction

별도의 결정이 없다면 다음 기술을 우선 고려한다.

- Kotlin
- Spring Boot
- JDK 21
- Spring Security
- JPA / Hibernate
- PostgreSQL
- Flyway
- Redis
- Docker
- AWS
- Riot Games API
- OpenAI API 또는 기타 LLM API

기술 스택은 절대적인 제약이 아니다.
더 적절한 선택지가 있다면 기존 선택의 문제점과 변경 이유를 먼저 설명하고 ADR이 필요한지 판단한다.

## Before Making Changes

작업을 시작하기 전에 다음을 확인한다.

1. 관련 코드와 테스트를 먼저 읽는다.
2. `docs/architecture.md`에서 현재 구조와 의존 방향을 확인한다.
3. 관련 ADR이 있는지 `docs/adr/`를 확인한다.
4. 기존 구조를 유지하면서 해결할 수 있는 가장 작은 변경을 우선한다.
5. 요구사항과 무관한 리팩터링을 같은 변경에 섞지 않는다.

코드와 문서가 다를 경우 실제 코드의 현재 동작을 우선 확인하고,
문서가 오래된 것이라면 해당 작업 범위 안에서 문서도 함께 수정한다.

## Architecture Guidelines

기본 아키텍처 방향은 `docs/architecture.md`를 따른다.

특히 다음 원칙을 우선한다.

- MVP는 Modular Monolith로 구성한다.
- 기능 단위(package-by-feature) 구성을 우선한다.
- Controller는 HTTP 요청/응답 처리에 집중한다.
- Application/Service 계층은 유스케이스와 비즈니스 흐름을 담당한다.
- Repository는 영속성 접근을 담당한다.
- Riot Games API, LLM 등 외부 시스템 호출은 별도의 Client/Adapter 경계로 분리한다.
- 외부 API DTO를 내부 도메인 모델처럼 직접 사용하지 않는다.
- 핵심 통계 계산은 Backend에서 수행하고 LLM에게 계산 책임을 넘기지 않는다.
- LLM에는 정제되고 구조화된 feature를 전달하며 자연어 분석과 피드백 생성에 집중시킨다.

구체적인 클래스 수나 계층 수를 맞추기 위해 불필요한 인터페이스를 만들지 않는다.
테스트 대역, 구현 교체, 의존성 역전 등 실제 필요가 있는 경계에 추상화를 둔다.


## Database Migration

- Database schema 변경은 Flyway migration으로 관리한다.
- 운영 환경에서 Hibernate `ddl-auto=update`에 의존하지 않는다.
- 기존 migration 파일은 적용된 이후 수정하지 않는다.
- schema 변경이 필요하면 새로운 migration 파일을 추가한다.
- Entity 변경과 DB migration이 함께 필요한 경우 같은 작업 범위에서 처리한다.
- 가능한 경우 application startup 시 JPA schema validation을 사용한다.
- migration 파일에는 변경 목적이 드러나는 이름을 사용한다.

예:

`V1__create_player_table.sql`
`V2__create_match_table.sql`
`V3__add_match_created_at_index.sql`


## Riot API Rules

Riot Games API 연동에서는 다음을 지킨다.

- Riot API 응답 형식을 내부 모델과 분리한다.
- API Key를 코드 또는 Git 저장소에 저장하지 않는다.
- Rate Limit을 고려하여 불필요한 재호출을 피한다.
- 재시도는 모든 오류에 무조건 적용하지 않는다.
- Timeout, 4xx, 5xx, Rate Limit 응답을 구분해서 처리한다.
- 캐시 도입 시 TTL과 데이터 신선도 기준을 명확히 한다.
- Riot API가 제공하지 않는 데이터를 제공한다고 가정하지 않는다.

API 정책이나 스펙처럼 현재성이 중요한 정보는 구현 전에 공식 문서를 기준으로 다시 확인한다.

## AI Analysis Rules

AI 플레이 분석 기능은 다음 데이터 흐름을 기본으로 한다.

```text
Riot API data
    -> data normalization
    -> statistics calculation
    -> analysis feature generation
    -> LLM request
    -> natural-language feedback
```

LLM은 원본 Match JSON 전체를 그대로 받아 핵심 통계를 임의로 계산하는 역할을 맡지 않는다.

가능하면 다음을 Backend에서 먼저 계산한다.

- 비교 가능한 수치
- 비율
- 구간별 통계
- 평균/분산 등 집계값
- 분석에 필요한 파생 feature

LLM Provider에 종속된 요청/응답 형식이 핵심 도메인 로직으로 퍼지지 않도록 경계를 둔다.

Peer Benchmark를 도입할 때는 `(sampled player PUUID, matchId)` 한 건을 participant-level
`BenchmarkSample`로 다룬다. 수집 시점에 확인한 sampled player의 rank만 sample에 귀속하며,
같은 Match의 다른 participant에게 tier를 추정하거나 부여하지 않는다.

## Testing

새로운 동작을 추가하거나 기존 동작을 수정할 때는 가능한 범위에서 테스트를 함께 작성한다.

우선순위는 다음과 같다.

1. 순수 비즈니스 로직 단위 테스트
2. Service/Application 유스케이스 테스트
3. Repository 통합 테스트
4. 외부 API Client 테스트
5. 필요한 주요 HTTP Endpoint 통합 테스트

외부 API를 실제 호출해야만 통과하는 테스트를 기본 테스트 스위트에 두지 않는다.

버그 수정 시에는 가능하면 버그를 재현하는 테스트를 먼저 추가한다.

## Error Handling

- 예외를 무조건 `Exception` 하나로 뭉개지 않는다.
- 외부 시스템 오류와 내부 비즈니스 오류를 구분한다.
- 사용자에게 노출되는 오류와 내부 로그에 남길 정보를 구분한다.
- API Key, Access Token, 개인정보 등 민감한 값을 로그에 기록하지 않는다.
- 실패를 숨기기 위해 빈 값이나 임의의 기본값을 반환하지 않는다.

## Documentation

- 구현 과정에서 기존 아키텍처와 다른 설계가 더 적절하다고 판단되면
  임의로 구조만 변경하지 않는다.

- 중요한 구조 변경이 필요하면:
  1. 변경 이유를 설명한다.
  2. 변경을 구현한다.
  3. `docs/architecture.md`를 함께 수정한다.

- 중요한 기술적 결정은 `docs/adr/`에 기록한다.

- 코드와 문서가 충돌할 경우 실제 코드 상태에 맞게 문서를 갱신한다.

다음과 같은 변경은 ADR 작성을 고려한다.

- 주요 계층 또는 모듈 경계 변경
- DB 또는 캐시 전략의 중요한 변경
- 외부 API Client 구조 변경
- 인증/인가 방식 결정
- 비동기 처리 또는 메시징 도입
- LLM Provider 추상화 방식 결정
- 데이터 저장 정책 변경
- 배포 구조의 중요한 변경

단순 리팩터링, 클래스명 변경, 작은 구현 세부사항은 일반적으로 ADR을 만들지 않는다.

## Change Scope

한 작업에서는 가능한 한 하나의 목적에 집중한다.

좋은 변경 예시:

- Riot Match 조회 Client 추가 + 관련 테스트
- Match 저장 정책 변경 + migration + ADR
- 캐시 적용 + 캐시 무효화/TTL 정책 문서화

피해야 할 변경 예시:

- 기능 구현과 무관한 대규모 패키지 이동
- 필요성이 확인되지 않은 공통화
- 사용되지 않는 미래 기능을 위한 추상화
- 여러 독립 기능을 하나의 거대한 PR에 포함

## Completion Checklist

작업 완료 전 다음을 확인한다.

- 요구사항을 만족하는가?
- 기존 아키텍처의 의존 방향을 지키는가?
- 테스트가 필요한 변경에 테스트가 있는가?
- 외부 API 실패와 Rate Limit을 고려했는가?
- 민감정보가 코드/로그에 포함되지 않았는가?
- 문서 변경이 필요한가?
- ADR이 필요한 수준의 결정인가?
- 사용하지 않는 코드나 불필요한 추상화가 추가되지 않았는가?
