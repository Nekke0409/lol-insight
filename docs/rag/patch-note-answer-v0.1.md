# 패치 노트 답변 생성 v0.1

## 범위

`POST /api/v1/knowledge/patch-note-questions`는 명시한 `patchVersion`, `locale`, 질문으로 저장된 패치 노트만 검색한다. 요청은 URL, 파일 경로, SQL, 모델, provider, prompt 또는 evidence metadata를 받을 수 없으며 질문 내용으로 범위를 바꾸거나 다른 패치로 fallback하지 않는다.

기능은 개인 local 검증용 opt-in이다. `RAG_ENABLED=false`, `RAG_ANSWER_ENABLED=false`가 기본값이며, 비활성화 시 404를 반환하고 quota, embedding, generation을 호출하지 않는다. `RAG_ANSWER_ENABLED=true`에는 `RAG_ENABLED=true`가 필요하다.

## 흐름과 책임

```text
HTTP -> PatchNoteQuestionService -> PatchNoteRetrievalService(1회) -> evidence bundle -> PatchNoteAnswerGenerator(최대 1회) -> backend citation validation -> HTTP response
```

검색은 기존 pgvector retrieval을 그대로 사용한다. 서버는 topK 5 결과를 순서대로 중복 chunk 없이 `E1`, `E2`로 부여하고, 전달할 metadata와 `evidenceText`를 합쳐 최대 5,000 문자까지만 bundle에 넣는다. 이 문자 한도는 token 한도가 아니다. 제외된 chunk는 인용할 수 없다. 생성기에 전달한 immutable evidence snapshot만 최종 citation의 후보가 된다.

생성 port는 provider-independent `PatchNoteAnswerGenerator`이며, OpenAI adapter만 SDK를 사용한다. Structured Output은 `ANSWERED | INSUFFICIENT_EVIDENCE`, statement의 text/evidenceIds, limitations만 받는다. URL, title, revision은 모델이 생성하지 않으며 서버가 retrieval metadata에서 채운다.

서버는 ANSWERED에 비어 있지 않은 statements와 evidenceIds가 있는지, 모든 ID가 이번 bundle에 있는지, INSUFFICIENT_EVIDENCE의 statements가 빈 목록인지 검증한다. 검증된 statement로만 `answer`를 조합하며 citation도 실제 참조된 evidence만 포함한다. 따라서 citation 참조 무결성은 보장하지만 답변 사실성 자체를 보장하지는 않는다.

검색 결과가 없으면 generation 없이 `INSUFFICIENT_EVIDENCE`와 “저장된 요청 범위에서 근거를 찾지 못했습니다.”를 반환한다. provider 오류, 계약 오류, 구조 오류, deadline은 근거 부족으로 바꾸지 않는다.

## 제한과 관측

- 질문 1,000 문자, retrieval 1회, query embedding 최대 1회, generation 최대 1회
- statements 5개, `max_output_tokens` 1,200, generation timeout 30초, 전체 monotonic deadline 60초
- OpenAI retry 0, `store(false)`, 기존 analysis/Agent와 같은 generation quota 사용
- 질문, evidence, 답변, raw provider response, API key, vector, URL/chunk ID를 로그나 metric tag에 남기지 않는다. 결과 상태, 시도 수, 결과/evidence/citation 수, 지연 시간만 기록한다.

전체 deadline의 남은 시간과 호출별 설정 중 더 짧은 값을 query embedding과 generation의 SDK request timeout으로 전달한다. corpus 계약 조회와 pgvector 검색은 요청별 JDBC statement timeout을 사용하며 공유 JDBC 설정을 바꾸지 않는다. JDBC timeout은 초 단위이고 이미 시작한 HTTP/DB 작업의 즉시 취소를 보장하지는 않지만, deadline 뒤 새 embedding/generation을 시작하지 않는다.

답변 adapter는 provider usage가 제공되면 input/output/total token과 provider latency를 application 실행 요약으로 넘긴다. incomplete/refusal에도 응답 usage가 있으면 한 번 보존하며, usage 없는 실패를 0으로 만들지 않는다.

prompt는 고정 instructions와 별도의 untrusted question/evidence data로 구성한다. tools, URL fetch, SQL, 파일, 환경 변수 또는 Riot/Backend 함수는 제공하지 않는다. 이는 prompt injection을 모두 해결했다고 주장하는 보안 보증이 아니다.

## 검증과 후속 smoke

자동 검증은 fictional fixture를 parser/chunker/indexing, fake embedding, pgvector retrieval, scripted generator, citation validation까지 연결한다. 이는 실제 생성 품질 평가가 아니며 `.local/rag` 자료나 실제 OpenAI/Riot 호출을 사용하지 않는다.

실제 수동 smoke에만 `RAG_ANSWER_MANUAL_CAPTURE_ENABLED=true`를 추가할 수 있다. 이 opt-in은 `RAG_ANSWER_MANUAL_PLAN_PATH`, `RAG_ANSWER_MANUAL_PLAN_SHA256`, `RAG_ANSWER_MANUAL_PLAN_LOCALE`, 새 `RAG_ANSWER_MANUAL_EVALUATION_RUN_ID`를 모두 요구한다. 서버는 시작 시 plan 원본 bytes를 한 번만 UTF-8(BOM 없음)로 읽어 승인 SHA-256과 대조하고, 이후 파일을 다시 읽지 않는다. 현재 보존 plan에는 문항별 locale이 없으므로 승인 hash와 함께 `MANUAL_PLAN_LOCALE=ko-KR`로 scope를 고정한다. 새 plan은 문항별 locale을 명시할 수 있다.

localhost 요청의 `X-Rag-Manual-Question-Id`는 plan의 ID를 식별할 뿐 request의 `question`을 대체하지 않는다. controller가 역직렬화한 `patchVersion`·`locale`·`question`이 서버가 읽은 plan과 정확히 같아야 retrieval 전에 admission된다. 불일치는 `422 MANUAL_INPUT_MISMATCH`로 끝나며 retrieval, query embedding, generation은 모두 0회다. `UNINDEXED` plan 문항은 먼저 실제 corpus 계약을 조회한다. corpus가 비어 있으면 기존 `INSUFFICIENT_EVIDENCE` 경로를 유지하지만, corpus가 있는데 plan이 embedding을 막으면 `422 MANUAL_EXECUTION_PLAN_REJECTED`로 실패한다. 따라서 예산 장치가 근거 부족 결과를 만들어 내지 않는다.

각 새 run은 `.local/rag/answer-evaluation/<run-id>/<question-id>.json`에 plan hash, 기대/실제 question hash·UTF-8 byte 수·code point 수, 입력 일치 여부, 안전한 거절 사유, provider 단계 진입 여부와 기존 evidence/citation 정보를 원자적으로 한 번만 기록한다. 같은 ID의 이후 거절은 `-attempt-N.json`으로 분리한다. 과거 run이나 같은 ID artifact는 덮어쓰지 않는다. 일반 로그와 metric에는 원문 question/evidence/answer를 추가하지 않는다.

수동 client는 [Invoke-PatchNoteAnswerEvaluation.ps1](../../scripts/rag/Invoke-PatchNoteAnswerEvaluation.ps1)만 사용한다. 이 스크립트는 plan을 명시 UTF-8로 읽고 hash를 검증한 뒤 JSON을 재파싱하여 세 요청 필드가 일치하는지 확인한다. `HttpClient`의 `ByteArrayContent`에 BOM 없는 UTF-8 body bytes와 `application/json; charset=utf-8`을 명시해 localhost에 한 번만 전송한다. response bytes와 ASCII transmission metadata를 별도 새 client directory에 저장하며, 기존 결과를 덮어쓰거나 자동 수정·재전송하지 않는다.

후속 유료 smoke는 이 경계가 실제 Windows localhost test provider로 통과한 뒤에도 자동 시작하지 않는다. 별도 승인된 live round에서만 보존한 `.local/rag`의 25.10 RAG-only export를 격리 DB에 복원하고, 기존 평가 질문으로 전달 evidence, 생성 답변, citation/source/revision 일치, 부족 근거 처리, embedding/generation 사용량을 확인한다. 이 작업에서는 export를 복원하거나 요청을 실행하지 않는다.
