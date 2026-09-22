# ADR-021: RAG 답변 인용은 Backend에서 검증한다

상태: Accepted

## 결정

패치 노트 retrieval 뒤의 답변 생성은 provider-independent `PatchNoteAnswerGenerator`로 분리하고, 모델은 statement와 evidence ID만 반환한다. Backend는 이번 요청에서 실제 생성기에 전달한 evidence bundle을 기준으로 ID를 검증하고 URL, 제목, revision, chunk/document ID와 evidenceText를 citation으로 조합한다.

검색 결과가 없으면 모델을 호출하지 않고 `INSUFFICIENT_EVIDENCE`를 반환한다. 잘못된 ID, schema, provider 실패, refusal/incomplete 또는 deadline은 부족 근거로 변환하거나 재시도하지 않는다.

## 이유

모델이 만든 URL 또는 revision을 신뢰하면 retrieval 결과와 답변 출처가 분리될 수 있다. citation 후보를 전달 evidence로 제한하면 모델이 보지 못한 chunk나 다른 revision을 인용할 수 없다. 이는 출처 참조의 무결성 경계이며, 문장 사실성이나 prompt injection 완전 방어의 보증은 아니다.

## 결과

RAG answer는 기본 비활성화이고 기존 analysis/Agent의 prompt, schema, cache, tool loop를 재사용하지 않는다. 별도의 crawler, vector DB, hybrid/rerank, memory, queue를 도입하지 않는다. 기존 retrieval 평가와 scripted 자동 테스트는 실제 답변 품질 평가가 아니다.
