# 25.10 Korean RAG 답변·인용 수동 검증 v0.1

## 판정

이 기록은 실제 OpenAI 호출 경계와 인용 참조 무결성을 제한적으로 확인했지만, **고정한 한국어 질문의 답변 품질 평가는 미완료**다. Windows 수동 HTTP 클라이언트가 한국어 request body를 `?`로 손상해 서버에 전달한 것을 evidence capture에서 발견했다. 같은 문항의 재실행은 하지 않았고, 남은 유료 문항도 실행하지 않았다.

따라서 아래 25.10 결과는 모델 품질이나 retrieval 품질의 성공 근거가 아니다. 특히 초가스와 피들스틱 결과는 실제 전달 evidence가 질문의 기대 근거를 포함하지 않았고, 답변도 질문을 충족하지 못했다.

이 결과는 정상 입력의 Hit@K, 정답률, 환각률 또는 개선 전 baseline으로 사용하지 않는다. citation ID가 전달 evidence의 부분집합이었다는 사실도 손상된 입력에서의 답변 사실성을 보장하지 않는다. 반대로 이 출력만으로 원래 한국어 질문의 의미를 모델이 이해하지 못했거나 embedding이 특정 챔피언을 검색하지 못했다고 결론 내릴 수 없다.

## 격리·복원 확인

- 실행일: 2026-09-25
- source revision: `fab2b06`; 수동 검증 경계 보완으로 working tree는 dirty
- export: `.local/rag/patch-25-10-ko-kr-rag-data.sql`, SHA-256 `c75ae10e8b45c72eaf40e01ebcb4be6662a8d518908f59a0b00fd0586328a8d4`
- 새 `pgvector/pgvector:0.8.0-pg17` 컨테이너와 `rag_answer_eval` DB만 사용했다. 기존 개발 DB/Redis/volume은 접속하거나 변경하지 않았다.
- base Flyway와 RAG 별도 history를 적용한 뒤 data-only export를 복원했다.
- active revision: `25.10` / `ko-KR` / `text-embedding-3-small` / 1,536 dimensions / revision `3e847d…`
- chunk 수 53, `25.09`/`ko-KR` corpus 수 0, snapshot/content hash는 `b454670f…f3eeb54d`로 기존 retrieval 평가와 일치했다.

검증 컨테이너와 localhost 앱은 실행 뒤 제거했다. 원본 snapshot, metadata, question plan, export는 변경하지 않았다.

## 실제 시도와 사용량

manual opt-in server budget은 HTTP 5회, query embedding 4회, generation 4회로 고정했다. 실행한 것은 HTTP 4회이며, 유료 provider 시도는 query embedding 3회와 generation 3회, 합계 6회다. 문서 embedding은 0회, retry는 0회다.

| ID | HTTP / provider 결과 | query usage | generation usage | 평가 |
| --- | --- | ---: | ---: | --- |
| `unindexed-patch-25-09` | 200 `INSUFFICIENT_EVIDENCE`; corpus 결과 0 | 호출 0 | 호출 0 | 범위 fallback 없이 처리됨. 다만 손상된 질문 body 때문에 고정 문항의 HTTP 입력 검증으로는 세지 않는다. |
| `chogath-mid-top-rationale` | 200 `ANSWERED`; citation 5개 | 18 tokens / 4.730s | 1,230 / 410 / 1,640 tokens / 6.252s | 아이템·기본 능력치 evidence만 인용했고 초가스 중단/상단 조정 이유를 답하지 못함. 근거 불일치. |
| `lulu-wild-growth-cooldown` | 200 `ANSWERED`; citation 2개 | 20 tokens / 1.013s | 1,244 / 358 / 1,602 tokens / 6.366s | 룰루 `100/90/80초 → 120/100/80초` statement와 citation은 일치. 그러나 바이 R의 무관한 statement/citation도 포함되어 부분 충족. |
| `fiddlesticks-terrify-duration` | 200 `ANSWERED`; citation 3개 | 14 tokens / 0.296s | 1,342 / 457 / 1,799 tokens / 6.242s | 이번 topK에 피들스틱 Q 정확 수치 근거가 없었고 세나·초가스·스몰더 답변을 반환했다. 근거 불일치. |
| `personal-win-rate-decline` | 미실행 | - | - | 한국어 body 손상 발견 뒤 중단. |

provider usage가 없는 실패를 0으로 표시하지 않았다. 위 0회는 서버가 empty corpus에서 provider 호출을 시작하지 않았다는 실행 카운트다. 실제 전달 evidence, statement, citation, 본문 hash와 최대 600자 발췌는 Git-ignored `.local/rag/answer-evaluation/`에만 남겼으며 raw provider response, vector, API key, HTML 전체는 기록하지 않았다.

## 확인된 경계와 한계

- 25.09 active corpus 부재는 query embedding/generation 없이 `INSUFFICIENT_EVIDENCE`로 끝났다.
- 실행한 25.10 요청은 각 한 번의 retrieval, embedding, generation만 수행했고 backend citation ID 검증을 통과했다. citation은 실제 전달 evidence의 부분집합만 포함했다.
- 유효한 citation ID가 답변의 의미적 정확성을 보장하지 않았다. 이 실행에서는 topK retrieval/evidence bundle이 기대 근거와 어긋날 수 있음을 확인했다.
- 한국어 HTTP request body를 byte-preserving UTF-8로 전송하고 서버가 수신한 고정 문구를 유료 호출 전에 확인하는 준비가 다음 수동 round의 선행 조건이다. 이 task에서는 이를 보정하기 위한 재호출을 하지 않는다.

## 후속 무과금 UTF-8 전송·입력 무결성 검증

이후 별도 무과금 작업에서 기존 실패 artifact, snapshot, metadata, question plan, RAG-only SQL export를 수정·복원·삭제하지 않았다. 현재 Windows PowerShell은 `5.1.26100.9444` Desktop이며, 보존 plan은 BOM 없는 UTF-8 2,719 bytes, SHA-256 `5f030d109f43514b01378ade3af0124de66fce246e5126521857973835e04eae`다. 과거 client의 메모리 문자열·직렬화 JSON·실제 body bytes는 보존되지 않았으므로, 서버 수신값의 손상은 확인됐지만 최초 손상 지점은 미확정으로 유지한다.

새 client는 plan bytes를 명시 UTF-8로 읽고 승인 hash를 확인한 뒤 JSON을 다시 파싱하며, BOM 없는 UTF-8 `ByteArrayContent`와 `application/json; charset=utf-8`로만 localhost에 보낸다. response도 raw bytes로 저장한다. 실제 Windows localhost HTTP test는 test-only recording embedding/generation 대역과 함께 한글, 공백, 따옴표, 숫자, slash, `→`를 포함한 plan 문항이 controller DTO·embedding·generator까지 동일하게 전달되고, 한국어 statement/heading이 client 저장 response에 보존됨을 확인했다. 이 test에는 API key, OpenAI/Riot 요청, corpus export, DB/Redis가 필요하지 않았다.

수동 mode server guard는 read-only plan의 SHA-256을 시작 시 확인하고 수신 DTO의 ID·patch·locale·question을 plan과 대조한다. `?` 손상, 다른 question, patch/locale 변경, 미승인 ID는 provider 대역 0회인 `MANUAL_INPUT_MISMATCH`로, malformed JSON은 controller 역직렬화 오류로 끝난다. 동등한 Unicode escape JSON은 DTO 역직렬화 뒤 원문과 같으므로 허용한다. 정상 empty corpus와 active corpus에서 embedding 금지인 plan 오류도 각각 `INSUFFICIENT_EVIDENCE`와 `MANUAL_EXECUTION_PLAN_REJECTED`로 구분했다. 이번 변화는 전송·admission 검증일 뿐 실제 25.10 RAG 답변 품질 평가가 아니다.

따라서 이 문서 상단의 실제 provider 6회 사용량과 HTTP 4회 기록은 그대로 유지하며, 새 무과금 test를 그 사용량에 합산하지 않는다. 다음 live round는 새 run ID와 비어 있는 artifact directory, 승인된 plan hash·locale, 격리 DB의 기존 export hash/active revision 재확인, 명시적인 유료 호출 승인이 모두 갖춰진 뒤에만 별도로 실행한다.
