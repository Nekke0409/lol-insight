# ADR-022: Agent는 패치 노트 retrieval Tool을 통해 문서 근거를 사용한다

상태: Accepted

## 배경

기존 Agent는 정형 플레이어 통계와 peer comparison만 Tool로 사용하고, RAG는 독립 endpoint에서 retrieval 및 answer
생성을 제공한다. 문서 질문을 Agent에 연결할 때 독립 RAG answer generator를 다시 호출하면 Agent loop와 RAG가 서로
의존하고, 모델이 만든 URL 또는 다른 요청의 evidence를 인용할 위험이 생긴다.

## 결정

Agent v0.2는 `search_patch_notes(query)`라는 읽기 전용 function Tool을 추가한다. patchVersion/locale/topK는 검증된
HTTP `knowledgeScope`와 서버 설정에서만 주입한다. Tool은 `PatchNoteRetrievalService`를 한 번 호출하고 근거만 반환한다.

기능은 `AGENT_PATCH_NOTES_ENABLED=false`가 기본이며 Agent와 RAG retrieval이 모두 활성화되어야 한다. 독립 RAG
answer 생성 플래그는 별개다. scope가 없으면 Tool schema에도 노출하지 않으며, RAG가 비활성화된 기존 Agent 기동은 유지한다.

Tool output으로 실제 전달된 evidence만 request-local registry에 보관한다. 최종 structured Agent output은 문서 statement,
정형 Tool statement, limitation statement를 구분하고 Backend가 citation ID와 실제 Tool 실행 기록을 검증한다.

## 결과와 trade-off

- Agent는 기존 retrieval timeout, embedding 계약, pgvector filter를 재사용하고 검색 구현을 복사하지 않는다.
- 문서 근거와 개인 통계 근거를 섞지 않으며, size 제한으로 전달하지 못한 evidence나 다른 요청 evidence를 인용할 수 없다.
- 한 요청의 예산은 기존 3 model/2 Tool 상한 안에서 문서 Tool 1회와 query embedding 1회로 제한된다.
- reference integrity는 보장하지만 문장 의미의 정확성, 실제 Tool 선택 품질, prompt injection 전반은 별도 smoke/evaluation으로
  확인해야 한다.
- Agent memory, global evidence cache, Vector DB, hybrid/rerank, crawler, queue와 별도 Agent loop는 도입하지 않는다.

## 검토한 대안

### 독립 `PatchNoteQuestionService`를 Agent에서 호출

독립 answer generator를 중첩 호출해 model 비용과 loop를 늘리고 Agent/RAG 순환 의존을 만들므로 선택하지 않았다.

### 모델이 source URL과 citation metadata를 생성

모델이 보지 못한 revision/chunk를 인용할 수 있어 request-local registry 기반 조합보다 참조 무결성이 약하므로 선택하지 않았다.

### 모든 Agent statement에 문서 citation 강제

Backend가 계산한 플레이어 통계와 문서 사실을 혼동하게 되므로 statement basis를 구분하는 방식을 선택했다.
