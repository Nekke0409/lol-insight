# Architecture Decision Records

ADR(Architecture Decision Record)은 프로젝트에서 중요한 기술적 결정을
그 당시의 맥락과 함께 기록하기 위해 사용한다.

`architecture.md`가 **현재 구조**를 설명한다면,
ADR은 **왜 그런 구조가 되었는지**를 설명한다.

## When to Write an ADR

다음처럼 이후 구현에 지속적인 영향을 주는 결정을 기록한다.

- 주요 계층 또는 모듈 경계 변경
- Riot API Client 구조 결정
- 데이터 저장 정책 결정
- 캐싱 전략의 중요한 변경
- 인증/인가 방식 선택
- 동기 처리에서 비동기 처리로 변경
- 메시지 브로커 도입
- LLM Provider 추상화 방식
- 배포 구조의 중요한 변경
- 핵심 라이브러리 또는 기술 교체

다음은 일반적으로 ADR이 필요하지 않다.

- 변수명/클래스명 변경
- 작은 리팩터링
- 버그 수정 자체
- 단순 Endpoint 추가
- 구현 세부사항 변경
- 코드 스타일 변경

단, 작은 변경처럼 보여도 전체 시스템의 의존 방향이나 운영 특성을 바꾼다면 ADR 대상이 될 수 있다.

## File Naming

다음 형식을 사용한다.

```text
NNN-short-description.md
```

예:

```text
001-use-modular-monolith.md
002-separate-riot-api-client.md
003-cache-match-summary.md
```

번호는 저장소에서 기존 ADR의 다음 번호를 사용한다.

파일 번호는 결정의 중요도를 뜻하지 않는다.

## Status

필요한 경우 문서 상단에 상태를 표시할 수 있다.

```text
Status: Accepted
```

권장 상태:

- Proposed
- Accepted
- Superseded
- Deprecated

기존 결정을 변경할 때 이전 ADR을 삭제하거나 내용을 현재 결정에 맞게 덮어쓰지 않는다.

새 ADR을 만들고 기존 ADR에 `Superseded by ADR-XXX`와 같이 연결하여
결정의 변경 이력을 보존한다.

## Writing Principles

좋은 ADR은 다음 질문에 간결하게 답한다.

1. 어떤 상황과 문제가 있었는가?
2. 어떤 결정을 했는가?
3. 왜 이 선택을 했는가?
4. 어떤 대안을 고려했는가?
5. 어떤 장단점과 결과를 받아들이는가?

코드 구현 방법을 줄 단위로 설명하는 문서가 되지 않도록 한다.

## Template

새 ADR을 만들 때 [`000-template.md`](000-template.md)를 복사해서 사용한다.

사용자가 예시로 제시한 `002-separate-riot-api-client.md`와 같은 문서는
실제로 해당 구조 변경을 결정한 시점에 작성한다.

예시를 미리 Accepted ADR로 저장하면
아직 일어나지 않은 결정을 프로젝트의 실제 역사처럼 기록하게 되므로 피한다.
