# Documentation

이 디렉터리는 프로젝트의 현재 구조와 중요한 기술적 의사결정을 기록합니다.

## Documents

### Architecture

[`architecture.md`](architecture.md)

현재 시스템이 어떤 방향으로 구성되어 있는지 설명하는 **living document**입니다.

코드 구조에 중요한 변경이 생기면 이 문서도 현재 코드 상태에 맞게 수정합니다.

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
