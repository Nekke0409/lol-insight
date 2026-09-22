# ADR-020: Opt-in pgvector 패치 노트 retrieval v0.1 사용

상태: Accepted

## 배경

챔피언·아이템 조정처럼 비정형 공식 문서 근거가 필요한 질문을 위해 문서 검색 기반을 학습할 필요가 생겼다. 그러나 기존
PostgreSQL/Flyway 운영 DB에는 pgvector extension이 없을 수 있고, 정형 player/game 데이터와 Peer Benchmark를 문서
embedding으로 바꾸면 안 된다. runtime URL fetch, hosted file search, 별도 Vector DB와 Agent 확장은 아직 필요가 확인되지
않았다.

## 결정

로컬 HTML snapshot으로 받은 소수의 공식 패치 노트만 대상으로, 기본 비활성화된 `rag` feature 안에 다음 흐름을 둔다.

```text
snapshot -> Jsoup parser -> section-preserving chunker -> EmbeddingGateway -> pgvector -> retrieval service
```

- Application은 provider-independent `EmbeddingGateway`와 retrieval/indexing service를 사용하고, OpenAI SDK는
  infrastructure adapter 안에 둔다.
- 기본 계약은 `text-embedding-3-small`, 1,536 dimensions다. document와 query는 같은 계약을 사용하며 계약 변경은
  재인덱싱을 요구한다.
- pgvector migration은 기본 Flyway 경로가 아닌 별도 location/history로 분리한다. `RAG_ENABLED=true`에서만 runner가
  실행되며, 기존 Flyway bean을 대체하지 않는다.
- 검색은 patchVersion/locale/model/dimension을 먼저 SQL filter로 적용한 exact cosine-distance query다. HNSW/IVFFlat,
  full-corpus in-memory sort, global threshold는 도입하지 않는다.
- revision은 새 vector를 준비한 뒤 짧은 DB transaction으로 active revision을 전환한다. 실패가 기존 searchable revision을
  삭제하지 않으며 duplicate input은 embedding을 다시 호출하지 않는다.

## 이유

기존 PostgreSQL 운영 관례와 Testcontainers 검증 방법을 그대로 활용하면서 별도 서비스의 운영 부담을 만들지 않는다. 또한
기본 비활성화와 별도 history가 pgvector가 없는 개발 DB·기존 Flyway 이력에 주는 영향을 차단한다. 정확한 patch/locale은
semantic similarity가 아니라 명시적인 SQL 범위로 보장한다.

## 검토한 대안

### 별도 Vector DB 또는 OpenAI hosted vector store

더 빠른 검색 기능이나 운영 기능을 얻을 수 있지만, 이 단계의 소수 문서와 exact search에는 추가 service·vendor boundary와
비용이 필요하다. pgvector 구조를 직접 학습한다는 목적에도 맞지 않아 선택하지 않았다.

### LangChain/LangGraph와 자동 수집 pipeline

문서 fetching, queue, scheduler, retry와 Agent 기능을 한 번에 도입하게 된다. 현재 필요한 것은 수동 snapshot의 retrieval
기반이므로 선택하지 않았다.

### 기본 Flyway migration에 vector schema 추가

pgvector extension이 없는 기존 DB의 기본 기동을 깨뜨릴 수 있다. 기본 경로와 history를 분리하는 편이 호환성 위험이 작다.

## 결과와 영향

RAG는 공식 문서 retrieval 결과까지 제공하지만 최종 답변 생성이나 Agent 연결은 제공하지 않는다. 실제 OpenAI embedding과
공식 Korean patch snapshot의 검색 품질은 아직 측정하지 않았다. timeout·batch·usage는 adapter가 통제하지만 자동 retry와
rate limit 정책은 추가하지 않았다.

후속 단계는 실제 snapshot의 수동 평가, citation 형식, 실제 provider 비용/latency 관측을 먼저 확인한 뒤에만 답변 생성 또는
Agent 경계를 검토한다. 문서 본문은 untrusted data로 취급하며 문서의 명령·URL·SQL·환경변수 접근을 실행하지 않는다.
