# Agent v0.2 문서 검색 Tool 단독 실호출 smoke 결과

## 판정

**A — 통과**. 단일 localhost Agent HTTP 요청에서 실제 Agent model이 `search_patch_notes`를 선택했고, 실제 OpenAI query embedding과 격리 pgvector 검색 결과를 받은 뒤 `PATCH_E1` 한 건만 인용한 구조화된 최종 답변을 반환했다.

## 실행 식별과 입력 보호

| 항목 | 값 |
| --- | --- |
| 실행 ID | `agent-patch-note-25-10-ko-kr-20260927-001` |
| question ID | `agent-patch-note-lulu-r-25-10-ko-kr-001` |
| 경로 | `POST /api/v1/players/ExamplePlayer/KR1/agent-questions` |
| scope | `25.10` / `ko-KR` |
| plan SHA-256 | `0a8a4b70938570105b7c32462e2f2a4054c0d403a235166b489777b2df617465` |
| question UTF-8 SHA-256 | `b9456274f1550a144803d5e8209aa4e3005187cb729ba118efb82df4e6e14760` |
| 실제 HTTP | 1회, `200`, UTF-8 request/response byte hash 보존 |

서버는 첫 Responses 요청 전에 header의 question ID, URL path, player 식별자, scope와 UTF-8 question hash를 승인 plan과 대조했다. 이 검증을 통과한 뒤에만 model 호출을 시작했다. execution policy, UTF-8 source hash, HTTP assembly 문제로 중단된 준비 단계는 HTTP transport 이전이었고 server guard에도 도달하지 않았다.

## corpus와 격리 환경

- 새 `pgvector/pgvector:0.8.0-pg17` 컨테이너에서 application/RAG schema migration을 먼저 완료하고 export를 그대로 import했다.
- export SHA-256: `c75ae10e8b45c72eaf40e01ebcb4be6662a8d518908f59a0b00fd0586328a8d4`
- 활성 corpus: `25.10` / `ko-KR`, `text-embedding-3-small` / `1536`, revision `3e847db68bf0cef8e8e96677b3bb8f4dc758e9f8d273cba5d533764a7fd2f0b7`, snapshot hash `b454670fa7131175a6a6b59a2169dd81ffbacbc4d17887a16fda9965f3eeb54d`, 53 chunks.
- 문서 제목·공식 URL·룰루 한국어 heading/body를 import 후 SQL로 재확인했다. 수집, index, document embedding 또는 export 수정은 하지 않았다.

## 실제 Agent 실행

| 구분 | 관측값 |
| --- | --- |
| Responses model 요청 | 2회 (budget 3) |
| Tool 실행 | 1회 (budget 2) |
| `search_patch_notes` | 1회 (budget 1) |
| query embedding | 1회, input 9 tokens (budget 1) |
| OpenAI 총 시도 | 3회 = Responses 2 + embedding 1 (budget 4) |
| document embedding | 0회 |
| 독립 RAG answer generation | 0회 |
| Riot HTTP | 0회 |

첫 model turn의 실제 Tool query는 `Lulu R ultimate cooldown 25.10`이었고, Tool 목록을 축소하거나 강제하지 않았다. stats/comparison Tool은 선택되지 않았다. local guard는 선택되었을 경우 dispatcher 및 Riot Application Service 진입 전에 차단하도록 적용되어 있다. Agent Tool dispatcher는 독립 RAG answer generator가 아니라 `PatchNoteRetrievalService`를 직접 호출하며, capture에는 direct patch-note retrieval 결과만 존재한다.

Tool에 실제로 전달된 serialized payload는 5개의 검색 결과를 포함했고, 크기 제한 내에서 그대로 continuation에 전달되었다. 최종 답변은 그 중 룰루 근거인 `PATCH_E1`만 사용했으며 초가스·아이템·돌격전 결과를 답변에 포함하지 않았다.

## 근거와 답변 검증

| 항목 | 실제 값 |
| --- | --- |
| evidence ID | `PATCH_E1` |
| title / heading | `25.10 패치 노트` / `챔피언 > 룰루 > R - 급성장` |
| 근거 | `재사용 대기시간: 100/90/80초 ⇒ 120/100/80초` |
| citation URL | `https://www.leagueoflegends.com/ko-kr/news/game-updates/patch-25-10-notes/` |
| citation revision | `3e847db68bf0cef8e8e96677b3bb8f4dc758e9f8d273cba5d533764a7fd2f0b7` |

구조화된 최종 statement는 룰루 R(급성장)의 재사용 대기시간이 `100/90/80초`에서 `120/100/80초`로 변경되었다고 했고, `PATCH_NOTE` basis·`PATCH_E1`·`search_patch_notes`를 함께 연결했다. 수치, 방향, citation mapping은 모두 corpus의 해당 chunk와 일치한다.

## 기록과 제한

- gitignored server capture: `.local/agent-smoke/agent-patch-note-25-10-ko-kr-20260927-001/server-execution.jsonl`
- gitignored client capture: `.local/agent-smoke-client/agent-patch-note-25-10-ko-kr-20260927-001/client-http.json`
- capture에는 승인 hash, Tool query, 실제 Tool serialization, evidence, structured statements, citation mapping, usage/count만 남겼다. 일반 question 본문, raw HTTP output, provider raw response 및 reasoning은 저장하지 않았다.
- `RAG_ANSWER_ENABLED=false`였고 Agent execution boundary의 관측값도 direct retrieval 1회뿐이다. 독립 answer generator의 별도 runtime probe는 이 단일 run에 추가하지 않았다.

이번 smoke로 검증하지 않은 범위는 stats/comparison Tool의 live 차단 경로, provider 실패·rate limit·retry 경로, 다른 질문의 retrieval 품질, corpus 수집/색인/재임베딩, 일반 RAG answer API다.
