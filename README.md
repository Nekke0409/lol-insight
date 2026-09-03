# LOL Stats & AI

League of Legends 데이터를 활용하여 플레이어 전적/통계를 제공하고,
가공된 플레이 데이터를 기반으로 AI 피드백을 생성하는 Backend 중심 웹 서비스 프로젝트입니다.

단순한 클론 코딩보다 **API 설계, 데이터 모델링, 외부 API 연동, 테스트, 캐싱,
성능과 장애 대응, AI 활용 구조**를 직접 설계하고 구현하는 것을 목표로 합니다.

## Goals

기능은 다음 순서로 확장합니다.

1. **Player Search & Match Statistics**
   - Riot Games API 기반 플레이어 검색
   - Match 조회
   - 전적 및 통계 제공

2. **AI Play Analysis**
   - Match 데이터 정제
   - 통계 및 분석용 feature 계산
   - 구조화된 데이터를 LLM에 전달
   - 플레이 분석 및 자연어 피드백 제공

3. **Community**
   - 사용자 계정
   - 게시글
   - 댓글
   - 일반적인 커뮤니티 기능

## Architecture Direction

초기 버전은 Backend 중심의 **Modular Monolith**로 시작합니다.

복잡한 분산 시스템을 미리 도입하지 않고,
서비스가 성장하면서 실제 문제가 확인되는 시점에 필요한 구조를 추가합니다.

기본적인 의존 흐름은 다음과 같습니다.

```text
HTTP Request
    -> Controller
    -> Application/Service
    -> Repository / External Client
    -> PostgreSQL / Redis / Riot API / LLM API
```

Riot API와 LLM 같은 외부 시스템은 애플리케이션 로직과 분리된 Client/Adapter 경계를 둡니다.

자세한 내용은 [`docs/architecture.md`](docs/architecture.md)를 참고하세요.

## AI Analysis Principle

LLM이 원본 게임 데이터를 직접 해석하고 모든 계산을 수행한다고 가정하지 않습니다.

```text
Riot API data
    -> data normalization
    -> statistics calculation
    -> feature generation
    -> LLM
    -> feedback
```

통계, 비율, 비교값과 같은 결정적인 계산은 Backend에서 수행하고,
LLM은 구조화된 feature를 바탕으로 설명과 피드백을 생성하는 역할에 집중합니다.

## Technology Direction

현재 우선 고려하는 기술은 다음과 같습니다.

| Area | Technology |
| --- | --- |
| Language | Kotlin |
| Framework | Spring Boot |
| Security | Spring Security |
| ORM | JPA / Hibernate |
| Database | PostgreSQL |
| Cache | Redis |
| Container | Docker |
| Cloud | AWS |
| Game Data | Riot Games API |
| AI | OpenAI API 또는 기타 LLM API |

기술 스택은 프로젝트 요구사항에 따라 변경할 수 있으며,
중요한 기술적 결정은 ADR로 기록합니다.

## Documentation

```text
.
├── AGENTS.md
├── README.md
└── docs
    ├── README.md
    ├── architecture.md
    └── adr
        ├── README.md
        ├── 000-template.md
        └── 001-use-modular-monolith.md
```

- [`AGENTS.md`](AGENTS.md): Codex 등 코딩 에이전트가 따라야 할 작업 규칙
- [`docs/architecture.md`](docs/architecture.md): 현재 시스템 아키텍처의 기준 문서
- [`docs/adr/`](docs/adr/): 중요한 기술적 의사결정 기록

## Development Status

현재는 **프로젝트 기반 구성 및 Riot Games API 연동 구조를 설계하는 단계**입니다.

현재 애플리케이션 코드는 Spring Boot 진입점과 컨텍스트 로드 테스트만 포함합니다.
Riot API 호출, Controller, Service, DTO, Entity는 아직 구현하지 않습니다.

기능 구현을 시작하면 최상위 패키지는 다음과 같이 package-by-feature로 구성합니다.

```text
io.github.Nekke0409.lol_stats
├── player/
├── match/
├── analysis/
├── community/
└── global/
```

각 기능 내부의 `api`, `application`, `domain`, `persistence`, `infrastructure` 같은 하위 패키지는
실제 코드와 책임 분리가 필요해지는 시점에만 추가합니다. 현재는 빈 패키지를 미리 만들지 않습니다.

## Local Environment

Riot API 키는 환경변수로만 주입합니다. 로컬에서는 [`.env.example`](.env.example)를 참고해
IDE 실행 구성이나 셸 환경변수에 설정합니다. 실제 값이 들어 있는 `.env` 파일은 Git에 추가하지 않습니다.
현재 프로젝트는 `.env` 파일을 자동으로 읽는 라이브러리를 포함하지 않으므로, `.env`는 로컬 값 보관용 참고 파일입니다.

```text
RIOT_API_KEY=your-riot-api-key
```

구체적인 실행 방법, 환경 변수, Docker 구성, API 목록은
실제 구현이 추가되는 시점에 이 README에 업데이트합니다.

문서가 미래 구현을 미리 가정하여 실제 코드와 달라지는 것을 피하기 위해,
아직 구현되지 않은 세부 설정은 의도적으로 확정하지 않습니다.

## Working Principles

- 동작만 하는 코드보다 변경하기 쉬운 구조를 지향합니다.
- 외부 API와 핵심 비즈니스 로직의 결합을 줄입니다.
- Rate Limit, Timeout, 장애 상황을 정상적인 운영 조건으로 다룹니다.
- 테스트하기 어려운 구조는 설계 신호로 간주합니다.
- MVP에서 필요하지 않은 과도한 추상화를 피합니다.
- 중요한 구조 변경은 코드와 아키텍처 문서를 함께 변경합니다.
- 중요한 기술적 선택은 ADR로 남깁니다.

## License

라이선스는 배포 정책이 정해진 후 추가합니다.
