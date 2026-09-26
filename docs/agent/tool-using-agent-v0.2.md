# Tool-using AI Agent v0.2: 패치 노트 검색 Tool

## 목적과 범위

v0.2는 기존 플레이어 Agent loop에 읽기 전용 `search_patch_notes` Tool을 추가한다. Tool은 기존
`PatchNoteRetrievalService`만 재사용하며, `PatchNoteQuestionService`나 `PatchNoteAnswerGenerator`를 호출하지 않는다.
따라서 독립 RAG answer 생성과 Agent의 Tool→최종 답변 흐름은 서로 의존하지 않는다.

지원 범위는 한 요청에 명시한 단일 `patchVersion`과 `ko-KR` locale이다. 최신 patch 선택, 여러 patch 자동 탐색,
`Match.gameVersion` 변환, 문서 수집·재embedding·재인덱싱은 포함하지 않는다. 문서 전용 질문도 기존 player path를
쓰지만, 문서 Tool만 선택되면 Riot account/rank/match 또는 comparison context를 로드하지 않는다.

## HTTP와 활성화 계약

기존 endpoint를 유지한다.

```json
POST /api/v1/players/{gameName}/{tagLine}/agent-questions

{
  "question": "25.10 패치에서 룰루 궁극기는 어떻게 바뀌었어?",
  "knowledgeScope": {
    "patchVersion": "25.10",
    "locale": "ko-KR"
  }
}
```

`knowledgeScope`는 선택 사항이다. 없으면 v0.1 통계/비교 Tool만 노출한다. `patchVersion`은 `NN.N` 또는
`NN.NN` 형식이고 locale은 현 v0.2에서 `ko-KR`만 허용한다. 잘못된 scope를 다른 patch나 언어로 대체하지 않는다.

패치 Tool은 다음을 모두 만족할 때만 scope 요청에서 노출된다.

- `AGENT_ENABLED=true`
- `AGENT_PATCH_NOTES_ENABLED=true` (기본 `false`)
- `RAG_ENABLED=true`

`RAG_ANSWER_ENABLED`는 조건이 아니다. `AGENT_PATCH_NOTES_ENABLED=true`인데 RAG retrieval이 비활성화되었거나
Agent Tool topK가 retrieval 상한을 넘으면 요청을 configuration error로 처리하며 fake retrieval로 대체하지 않는다.
RAG가 꺼져도 scope 없는 기존 Agent는 시작하고 동작한다.

## Tool과 실행 context

모델에 노출되는 Tool은 strict JSON schema를 사용한다.

```json
{"query":"룰루 궁극기 재사용 대기시간 변경"}
```

`query`만 허용한다. player, patch, locale, URL, 파일, SQL/table, model/provider, 임의 함수명, topK 및 호출 한도는
서버가 고정하거나 거절한다. dispatcher는 Tool 이름을 먼저 허용 목록과 대조한 뒤 Tool별 schema를 검증하므로 unknown Tool의
임의 JSON을 groupBy schema로 해석하지 않는다. 빈 문자열, 크기 초과, 잘못된 JSON 및 추가 필드는 거절한다.

Tool 결과에는 scope, 상태(`AVAILABLE`, `NO_EVIDENCE`, `INSUFFICIENT_EVIDENCE`), 실제 result 수, 요청 안에서만 유일한
`PATCH_E*` evidence ID, 제목, heading path, 제한된 evidenceText와 data limitation을 넣는다. URL, revision,
chunk/document ID는 모델이 아니라 request-local citation registry에만 둔다.

예를 들어 모델에 전달하는 성공 Tool 결과는 다음과 같고, source URL·revision·UUID는 포함하지 않는다.

```json
{
  "status": "AVAILABLE",
  "patchVersion": "25.10",
  "locale": "ko-KR",
  "resultCount": 1,
  "evidence": [
    {
      "evidenceId": "PATCH_E1",
      "title": "25.10 패치 노트",
      "headingPath": ["챔피언", "룰루"],
      "evidenceText": "궁극기 변경 내용"
    }
  ],
  "limitations": []
}
```

active corpus가 없으면 retrieval은 query embedding 없이 `NO_EVIDENCE`를 반환한다. 이는 해당 patch에 변경이 없다는
주장이 아니다. provider/DB/embedding 계약 오류는 `NO_EVIDENCE`로 바꾸지 않고 기존 RAG 오류 경계를 통해 실패한다.
검색은 자동 재시도하거나 query를 바꿔 반복하지 않는다.

통계 context는 기존처럼 lazy/request-local이다. 문서 Tool 단독 실행은 comparison context를 만들지 않으며, 같은 요청의
evidence registry는 다른 요청과 공유하지 않는다.

## 최종 답변과 citation

OpenAI final turn은 structured output으로 다음 의미를 반환한다.

```text
statements[] = { text, basis, evidenceIds, toolName }
limitations[]

basis = PATCH_NOTE | TOOL | LIMITATION
```

- `PATCH_NOTE` statement는 `search_patch_notes`의 실제 전달 evidence ID를 하나 이상 참조한다.
- `TOOL` statement는 실제 성공한 통계/비교 Tool 이름만 참조하며 문서 ID는 필요하지 않다.
- `LIMITATION` statement는 문서 또는 Tool 근거가 없는 한계 설명이며 evidence ID와 Tool 이름을 갖지 않는다.

Backend는 길이·배열·enum을 확인하고, citation ID가 이 요청에서 Tool output으로 실제 전달된 registry entry인지 확인한다.
잘못된 ID를 가까운 검색 결과로 바꾸거나 삭제해 문장을 살리지 않는다. Tool result가 Agent result-size 제한으로 대체되면
그 결과의 evidence는 registry에 등록하지 않는다. HTTP response의 기존 `answer`, `usedTools`, `dataLimitations`,
`terminationReason`은 유지하며 `citations`를 추가한다. citations에는 실제 final statement가 사용한 문서 근거만 포함한다.

모델의 최종 structured output과 검증 뒤 HTTP 응답의 예시는 다음과 같다.

```json
{
  "statements": [
    {
      "text": "룰루 궁극기 변경 내용입니다.",
      "basis": "PATCH_NOTE",
      "evidenceIds": ["PATCH_E1"],
      "toolName": "search_patch_notes"
    }
  ],
  "limitations": []
}
```

```json
{
  "answer": "룰루 궁극기 변경 내용입니다.",
  "usedTools": [{"name": "search_patch_notes", "success": true, "invocation": 1}],
  "dataLimitations": [],
  "terminationReason": "COMPLETED",
  "citations": [{"evidenceId": "PATCH_E1", "sourceUrl": "https://..."}]
}
```

이 검증은 출처 참조 무결성 검증이며, 모델 문장의 의미적 사실성이나 prompt injection 전체 방어를 증명하지 않는다.

## 비용·deadline·관측

- Responses model request 최대 3회(최종 답변 포함)
- Tool 실행 시도 최대 2회
- `search_patch_notes` 최대 1회
- query embedding 최대 1회, 문서 embedding 0회
- retry 0, `parallel_tool_calls=false`, 최종 model turn `tool_choice=none`

Tool은 Agent 전체 deadline의 남은 시간만 `PatchNoteRetrievalService`에 전달한다. Agent 대기 중단은 이미 시작한 provider
작업의 실제 취소를 보장하지 않는다. observability에는 model/Tool/문서 검색/embedding 시도, provider usage, result 수,
전달 evidence 수, citation 수, 종료 사유와 latency만 남긴다. usage가 없는 실패를 0 token으로 추정하지 않으며 question,
Tool payload, answer, Riot ID, PUUID, match ID, vector, URL, chunk ID 및 raw provider response는 기록하지 않는다.

## 자동 검증과 남은 경계

자동 테스트는 scripted Agent model → 실제 loop → 실제 dispatcher → 실제 `PatchNoteRetrievalService` → fake query embedding
→ Testcontainers pgvector → Tool output → structured final citation 검증을 연결한다. 문서 Tool 단독에서 player context를
생성하지 않는 것, 빈 corpus에서 embedding을 생략하는 것, strict Tool schema, scope/feature 설정, cross-request 또는
미전달 evidence ID 거부를 검증한다.

이는 실제 OpenAI의 Tool 선택 품질, 실제 한국어 Agent 답변 정확성, prompt injection에 대한 완전한 방어를 검증하지 않는다.
후속 opt-in Agent smoke는 `AGENT_ENABLED=true`, `AGENT_PATCH_NOTES_ENABLED=true`, `RAG_ENABLED=true`, 승인된 local
snapshot/corpus, API key, 명시 patch/locale, quota 및 deadline 여유가 최소 조건이다. 독립 RAG의 고정 5문항 plan은
이 Agent smoke에 재사용하지 않는다.
