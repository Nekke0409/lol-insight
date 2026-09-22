# 공식 Korean 25.10 patch note retrieval 평가

## 결과 요약

공식 한국어 25.10 patch note 한 건을 실제 `text-embedding-3-small`(1,536 dimensions)로 indexing하고 exact cosine
검색했다. 고정한 grounded question 4개 중 Hit@1은 3/4, Hit@3과 Hit@5는 4/4였다. 이 결과는 문서 한 건의 retrieval
기반 확인일 뿐, 일반적인 한국어 검색 품질·similarity threshold·최종 답변 생성의 근거가 아니다.

## Snapshot과 실행 환경

| 항목 | 기록 |
| --- | --- |
| source URL | `https://www.leagueoflegends.com/ko-kr/news/game-updates/patch-25-10-notes/` |
| 제목 / locale / patchVersion | `25.10 패치 노트` / `ko-KR` / `25.10` |
| 공식 게시 시각 | `2025-05-13T18:00:00Z` (snapshot의 `time[datetime]` 및 JSON-LD 확인) |
| snapshot 수집 시각 / 방법 | `2026-09-22T07:47:54.5643284Z` / 한 번의 수동 `Invoke-WebRequest` |
| raw HTML SHA-256 | `b454670fa7131175a6a6b59a2169dd81ffbacbc4d17887a16fda9965f3eeb54d` |
| 실행 시작 | `2026-09-22T07:56:44Z` |
| source revision / worktree | `ffea83e` / dirty (manual smoke·parser 회귀 변경이 아직 commit 전) |
| isolated DB | Testcontainers `pgvector/pgvector:0.8.0-pg17`, PostgreSQL 17.6, application datasource/Redis와 분리 |

원문 HTML, 질문 계획, metadata와 RAG data-only export는 `.local/rag/`에만 보관하며 Git에서 제외한다. 기존 개발 DB,
Redis, Benchmark cursor, automation, analysis job과 Riot API는 이 실행에서 사용하거나 변경하지 않았다.

## 유료 호출 전 dry-run

실제 DOM에서 `#patch-notes-container`, section `header`, `blockquote`, 중첩 목록을 확인했다. 이에 따라 parser는 해당
container를 우선하고, DOM order/heading level을 따라 heading path를 만들며, 조정 사유 `blockquote`를 보존하도록 v4로
수정했다. 예시 경로는 `챔피언 > 초가스 > Q - 파열`, `챔피언 > 룰루 > R - 급성장`,
`챔피언 > 피들스틱 > Q - 공포`다. 챔피언 경계를 넘는 chunk는 만들지 않았다.

| dry-run 항목 | 값 |
| --- | --- |
| parser / chunker | `jsoup-patch-note-v4` / `section-preserving-chunker-v1` |
| sections / chunks | 53 / 53 |
| embedding input 문자 수 (prefix 포함) | 12,196 |
| 최대 input 문자 수 | 1,488 |
| 예상 문서 요청 | 4 |
| 강제 cap | chunks 128, 문서 120,000자, 문서 요청 16, query 요청 5, 총 요청 21 |

OpenAI 공식 embedding 안내의 `text-embedding-3-small` 최대 입력은 8,192 tokens다. 이 평가의 문자 수는 token 수가 아니며,
각 input은 기존 8,000자/배치 24,000자 제한도 통과했다. 자동 retry는 0이다.

## 고정 질문과 검색 결과

질문은 snapshot을 읽은 뒤 embedding 호출 전에 local question plan으로 고정했다. 검색 후 문구나 기대 근거를 바꾸지 않았다.
각 grounded 결과의 저장 body와 `evidenceText`를 비교했고, 필요한 수치·사유는 모두 600자 evidence 안에 있어 truncation되지 않았다.

| ID | 기대 heading path / 최소 근거 | 첫 기대 순위 | Hit@1 / @3 / @5 | 평가 |
| --- | --- | ---: | --- | --- |
| `chogath-mid-top-rationale` | `챔피언 > 초가스`; 중단 성능과 상단 도움 필요, Q/E 조정 방향 | 1 | Y / Y / Y | 사유와 방향이 evidence에 충분 |
| `chogath-rupture-damage` | `챔피언 > 초가스 > Q - 파열`; `80/140/200/260/320`에서 `80/135/190/245/300`, 주문력 100% | 1 | Y / Y / Y | 수치·계수가 evidence에 충분 |
| `lulu-wild-growth-cooldown` | `챔피언 > 룰루 > R - 급성장`; `100/90/80초`에서 `120/100/80초` | 1 | Y / Y / Y | 수치·단위가 evidence에 충분 |
| `fiddlesticks-terrify-duration` | `챔피언 > 피들스틱 > Q - 공포`; `1.25/1.5/1.75/2/2.25초`에서 `1.2/1.4/1.6/1.8/2초` | 2 | N / Y / Y | 1위는 피들스틱 요약, 정확 수치 chunk는 2위 |
| `personal-win-rate-decline` | 개인 최근 경기·포지션·챔피언·전적 통계 필요 | 해당 없음 | 해당 없음 | 상위 결과는 인접 patch text일 뿐 개인 데이터가 없으므로 답변 불충분 |
| `unindexed-patch-25-09` | `25.09`의 active `ko-KR` corpus 없음 | 빈 결과 | 해당 없음 | query embedding 0회, fallback 없음 |

## Provider 관측과 보존

문서 indexing은 4회(16/16/16/5 inputs), prompt tokens는 3,787/2,662/2,593/2,404로 합계 11,446이었다.
문서 provider latency 합계는 약 7.35초다. 정확히 같은 snapshot을 다시 index했을 때 embedding 추가 호출은 0회였다.

질의 embedding은 5회만 발생했고 prompt tokens는 55/54/50/52/39, 합계 250이었다. query provider latency 합계는 약
1.08초다. 401/403/429, timeout, malformed response, budget failure는 없었고 retry도 없었다. API key, raw vector,
provider raw response, 전체 HTML은 출력하거나 이 문서에 저장하지 않았다.

Testcontainers 종료 전 `rag_patch_note_document_revision`과 `rag_patch_note_chunk`만 data-only SQL로
`.local/rag/patch-25-10-ko-kr-rag-data.sql`에 export했다(1,056,496 bytes, Git ignored). 복원 시에는 같은
`pgvector/pgvector:0.8.0-pg17` target에 RAG migration을 먼저 적용한 뒤 이 export만 사용한다. 컨테이너는 test 종료 후
정리됐으며 application DB에는 잔존 데이터가 없다.

## 범위 밖

이 평가는 한 공식 Korean snapshot, 정확 cosine search, topK 5, 수동 질문 6개만 다룬다. 여러 patch/locale, crawler,
schedule, ANN/hybrid/reranker, public endpoint, RAG answer generation, Agent 연결, EMERALD 수집과 deployment는 추가하지 않았다.
