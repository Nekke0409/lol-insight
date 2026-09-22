# 공식 패치 노트 검색 기반 v0.1

## 목적과 범위

이 문서는 공식 League of Legends 패치 노트의 **로컬 HTML snapshot**을 정제·분할·벡터화하여, 명시한
`patchVersion`과 `locale` 범위에서 근거와 출처를 찾아 주는 retrieval 기반을 정의한다. v0.1의 우선 locale은
`ko-KR`이며, 챔피언·아이템 조정과 공식 설명을 검색 대상으로 삼는다.

현재 구현은 최종 LLM 답변 생성, Agent Tool 연결, public upload/import endpoint, URL/파일 경로 입력, runtime fetch,
주기 수집과 자동 indexing을 포함하지 않는다. 따라서 SSRF 방어가 필요한 수집기는 후속 작업이다. `patchVersion`은
숫자가 아닌 문자열 도메인 값이며 Match `gameVersion`과 같은 값이라고 가정하지 않는다. 최신 패치도 자동 선택하지 않는다.

```text
명시적으로 준비한 local snapshot
    -> Jsoup 본문 정제
    -> 제목 계층 보존 chunking
    -> EmbeddingGateway
    -> pgvector 저장·active revision 전환
    -> patchVersion/locale 범위 cosine 검색
    -> 출처가 포함된 retrieval 결과
```

## 정제와 chunk 정책

`JsoupPatchNoteParser`는 `script`, `style`, `nav`, `header`, `footer`, `aside`, form과 navigation 역할 요소를 제거하고,
`article`/`main`/알려진 content 영역을 우선 사용한다. `h1`~`h6`, 문단, 목록과 표에서 본문을 만들며 heading path를 보존한다.
지원하는 searchable body가 없으면 빈 document를 성공으로 저장하지 않고 실패한다.

`DeterministicPatchNoteChunker`는 한 heading section 안에서만 chunk를 만든다. 그래서 챔피언·아이템 경계를 넘어 문단을
합치지 않는다. 긴 section만 문단 단위로 `RAG_CHUNKING_MAX_CHARACTERS`(기본 2,000자) 이하로 분할하며, 한 문단이 너무
길 때만 공백 경계에서 추가 분할한다. 같은 snapshot과 설정은 같은 순서·본문·heading path를 만든다.

각 chunk는 검색용 본문과 별도로 다음 context prefix를 갖는다.

```text
패치 버전: 16.99
문서 제목: 가상 fixture
제목 경로: 테스트 패치 노트 > 챔피언 > 아리
```

prefix와 본문을 합친 값만 embedding input으로 사용하며, 본문과 prefix 및 input hash는 별도로 보관한다. parser와
chunker의 버전도 revision metadata에 기록한다.

## 저장 모델과 revision

RAG migration은 기본 Flyway 위치가 아닌 `classpath:db/rag-migration`에 있다. 활성화할 때 `rag_flyway_schema_history`
라는 별도 history를 사용하므로 기존 `flyway_schema_history`와 V1~V4 migration을 변경하거나 checksum을 건드리지 않는다.
기본 Flyway가 먼저 실행된 뒤 opt-in runner가 RAG migration을 실행한다.

- `rag_patch_note_document_revision`: source URL, 제목, patch/locale, 확인된 게시 시각(없으면 `NULL`), snapshot 수집 시각,
  내용 hash, parser/chunker version, embedding model/dimension, active flag를 보관한다.
- `rag_patch_note_chunk`: revision의 chunk 순서, heading path, prefix, 본문, embedding input hash, `vector`를 보관한다.
- `source_url + patch_version + locale`당 active revision은 하나뿐이다. 검색 SQL은 active revision만 읽는다.

revision fingerprint는 source URL, patchVersion, locale, raw snapshot content hash, parser/chunker version과 embedding 계약의
SHA-256이다. 같은 fingerprint를 다시 등록하면 chunk 삽입과 embedding 호출을 하지 않는다. 이미 저장된 inactive revision을
의도적으로 다시 등록하면 저장된 vector를 재사용하여 active로 전환한다.

새 내용은 새 inactive revision과 chunks를 먼저 짧은 transaction에서 기록하고, 성공한 뒤 기존 revision을 inactive로
바꾼다. embedding 호출은 transaction 밖에서 수행한다. 따라서 새 embedding 또는 DB 저장이 실패해도 기존 active 검색
데이터는 먼저 삭제되지 않는다. v0.1은 수동 단일 indexing 흐름이며 multi-writer coordination, queue, 자동 retry가 없다.

## Embedding 계약과 비용 경계

Application 계층의 `EmbeddingGateway`는 provider SDK 타입을 노출하지 않는다. 현재 OpenAI adapter의 기본 계약은
`text-embedding-3-small`, 1,536 dimensions이고 분석 generation model 설정과 분리되어 있다. OpenAI Java SDK `4.63.1`의
Embeddings endpoint를 사용하며, 자동 provider retry는 `0`이다.

문서와 질의는 반드시 같은 model/dimension 계약을 사용한다. active patch/locale corpus의 계약이 현재 gateway와 다르면
질의 embedding을 호출하지 않고 명시적인 재인덱싱 설정 오류로 끝낸다. model 또는 dimension을 바꾸면 해당 corpus 전체를
재인덱싱해야 한다. migration의 `vector` 타입은 차원을 고정하지 않지만, document metadata와 SQL filter가 서로 다른
계약의 vector를 한 검색 대상에 섞지 않게 한다.

입력은 빈 문자열, 단일 입력 길이, batch 크기, 총 batch 길이를 검사한다. 기본값은 최대 16개, 단일 8,000자, batch 총
24,000자, timeout 30초다. provider 응답은 input 개수·index 순서·model·dimension을 확인하고 NaN, infinity, 영벡터를
거부한다. 응답 usage의 prompt token 수는 gateway 결과에 보존하지만 raw provider response, vector, API key는 검색 결과로
반환하지 않는다.

`RAG_ENABLED=false`가 기본값이다. 비활성화 상태에서는 RAG bean과 migration runner가 생성되지 않아 pgvector extension이
없는 기존 PostgreSQL에서도 기존 application·test가 동작한다. `RAG_ENABLED=true`인데 extension 또는 migration 계약이
맞지 않으면 시작 시 `RagPersistenceConfigurationException`으로 명확히 실패하며, 메모리 정렬이나 가짜 검색으로 대체하지
않는다. 개발 DB image·volume, Compose, Dockerfile과 배포 Compose는 이 작업에서 변경하지 않는다.

## 검색 계약

`PatchNoteRetrievalService` 입력은 `query`, `patchVersion`, `locale`, `topK`다. `topK`는 1부터
`RAG_RETRIEVAL_MAX_TOP_K`(기본 10)까지 제한한다. 해당 patch/locale에 active corpus가 없으면 query embedding을 만들지 않고
빈 결과를 반환한다. 이는 "그 패치에 변경이 없다"는 뜻이 아니다.

실제 SQL은 patch/locale/model/dimension을 `WHERE` 조건으로 적용한 뒤 PostgreSQL에서 다음처럼 exact cosine distance를
계산한다. 값은 모두 parameter binding으로 전달한다. HNSW/IVFFlat index와 JVM 전체 vector 정렬은 v0.1에 없다.

```sql
ORDER BY c.embedding <=> CAST(:queryVector AS vector), c.id
LIMIT :topK
```

결과에는 chunk/document ID, 제목, source URL, patch/locale, revision fingerprint, heading path, 길이 제한 근거 본문과
cosine distance를 포함한다. distance는 해당 필터 범위에서 작을수록 가까운 vector라는 의미일 뿐 답변의 사실 정확도나
확률·신뢰도가 아니다. 보편적으로 검증된 similarity threshold는 아직 없다.

## 검증한 범위와 한계

외부 Riot/OpenAI 호출 없이 다음을 자동 검증한다.

- 가상 HTML fixture에서 불필요한 요소 제거, heading path 및 수치·부호가 담긴 본문 보존
- 결정적 section chunking, 최대 길이와 챔피언/아이템 경계 보존, 빈 본문 실패
- 동일 snapshot 재등록 시 추가 vector 저장·embedding 호출이 없는 것
- 새 revision의 embedding 실패 뒤 기존 active revision이 여전히 검색되는 것
- `pgvector/pgvector:0.8.0-pg17` Testcontainers에서 실제 RAG migration, vector 저장, `<=>` cosine 정렬,
  patch/locale filter, topK 제한과 출처 반환
- 빈 corpus에서 query embedding 호출을 건너뛰는 것, embedding 계약 불일치와 비정상 vector 거부

연결 테스트의 명시적 대역은 3차원 `test-embedding` vector다. 예를 들어 가상 fixture의 질의
`아리 Q 피해량 변경`은 `테스트 패치 노트 > 챔피언 > 아리` 절과 source URL을 첫 결과로 반환한다. 이 fixture는 실제 Riot
패치 원문이 아니며 한국어 의미 검색 품질 또는 실제 OpenAI embedding 호출을 검증한 결과가 아니다.

후속 실문서 평가에서는 수동으로 고른 소수의 공식 Korean snapshot마다 다음을 기록한다.

| patchVersion | 질의 | 사람이 확인한 기대 근거 절 | 상태 |
| --- | --- | --- | --- |
| 지정할 실제 patch | 챔피언/아이템 조정 질문 | source URL과 heading path를 함께 기록 | 아직 snapshot·embedding 미실행 |

실문서 snapshot 수집, 실제 embedding 비용·latency 측정, 이 표의 채움은 별도 승인 task에서만 수행한다.

## 답변 생성 단계의 보안 경계

검색 문서는 신뢰할 수 있는 시스템 지시가 아니라 untrusted data다. 후속 답변 생성은 문서 안의 명령을 실행하거나, 문서가
제시한 URL을 조회하거나, SQL을 실행하거나, 환경변수·API key·파일을 읽는 권한을 얻지 않는다. 구조화된 플레이어/경기
데이터와 Peer Benchmark는 embedding으로 대체하지 않고 기존 Backend Tool/Application Service 경계를 유지한다.

최종 RAG 질의응답으로 확장하기 전에는 실제 공식 snapshot의 provenance·저작권 범위, 수동 평가 결과, 비용/timeout 관측과
answer citation 형식을 별도 task에서 결정한다.
