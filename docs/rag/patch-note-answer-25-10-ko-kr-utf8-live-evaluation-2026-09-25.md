# 25.10 한국어 RAG 답변·인용 UTF-8 실호출 평가 v0.1

## 판정

정상 UTF-8로 전송한 고정 5문항을 각각 한 번씩 실제 OpenAI 경로로 평가했다. 모든 요청은 서버의 승인 plan 입력 검증을 통과했고, 3개 문항은 전달 근거에 맞는 답변과 인용을, 2개 문항은 범위에 맞는 `INSUFFICIENT_EVIDENCE`를 반환했다.

이 문서는 25.10 한국어 patch-note corpus와 고정 5문항에서 확인한 첫 정상 입력 결과다. 전체 RAG 품질, 다른 패치/언어/질문에 대한 정답률 또는 환각률로 일반화하지 않는다. 이전 입력 손상 round의 기록과 사용량은 [기존 평가 기록](patch-note-answer-25-10-ko-kr-evaluation-2026-09-25.md)에 그대로 보존하며, 이 결과에 합산하지 않는다.

## 실행 범위와 재현 기준

- 실행일: 2026-09-25, run ID: `20260925-utf8-live-1`
- 시작 source revision: `7859c6b` (`fix: RAG 수동 평가 입력 무결성 검증 보강`), 시작 시 working tree clean
- 승인 plan: `.local/rag/patch-25-10-ko-kr.questions.properties`, SHA-256 `5f030d109f43514b01378ade3af0124de66fce246e5126521857973835e04eae`
- RAG-only export: `.local/rag/patch-25-10-ko-kr-rag-data.sql`, SHA-256 `c75ae10e8b45c72eaf40e01ebcb4be6662a8d518908f59a0b00fd0586328a8d4`
- snapshot HTML SHA-256: `b454670fa7131175a6a6b59a2169dd81ffbacbc4d17887a16fda9965f3eeb54d`
- 격리 DB active corpus: `25.10` / `ko-KR` / `text-embedding-3-small` / 1,536 dimensions / revision fingerprint `3e847db68bf0cef8e8e96677b3bb8f4dc758e9f8d273cba5d533764a7fd2f0b7`
- 활성 chunk 수는 복원 뒤와 요청 뒤 모두 53개였다. `25.09` / `ko-KR` active corpus는 없었다.

새 `pgvector/pgvector:0.8.0-pg17` 컨테이너와 별도 DB만 사용해 migration 후 byte-preserving 방식으로 export를 복원했다. localhost의 production controller/service/retrieval/generator bean을 기동했고 test provider나 fake embedding은 연결하지 않았다. 개발 DB·Redis·기존 volume·Benchmark/Automation/Analysis 데이터에는 접속하거나 변경하지 않았다.

기존 전송 스크립트 `scripts/rag/Invoke-PatchNoteAnswerEvaluation.ps1`만 사용했다. plan을 명시 UTF-8로 읽어 승인 hash를 확인한 뒤 `application/json; charset=utf-8` body를 localhost `POST /api/v1/knowledge/patch-note-questions`에 전송했다. 서버는 각 DTO의 ID, patch, locale, question을 plan과 대조했고, client transmission의 question SHA-256도 server capture의 actual question SHA-256과 일치했다.

## 적용 설정과 호출 한도

- embedding model: `text-embedding-3-small`, dimensions 1,536
- generation model: 기존 `gpt-5-mini`
- retrieval 최대 1회, query embedding 최대 1회, generation 최대 1회/문항
- `topK=5`, evidence 총량 5,000자, statements 최대 5개, `max_output_tokens=1200`
- generation timeout 30초, 전체 deadline 60초, retry 0, `store(false)`
- 실제 HTTP 요청 5/5, query embedding 4/4, generation 4/4, 유료 provider 시도 8/8, 문서 embedding 0, 재시도 0

`chogath-rupture-damage`는 실행하지 않았다. 실패·미완료 요청을 다시 보내지 않았으며, query embedding/generation 사용량이 없는 25.09 문항은 empty-corpus 경로에서 provider 단계에 들어가지 않은 결과다.

## 문항별 결과와 평가

모든 문항의 HTTP 응답은 200이고 `inputMatches=true`였다. `ANSWERED`의 최종 citation은 이번 evidence bundle에 실제 참조된 ID만 포함했으며, 모두 같은 25.10 한국어 source/title/revision으로 서버가 매핑했다.

| ID | 상태와 provider 사용량 | 검색·전달 근거 / 인용 | 내용 판정 |
| --- | --- | --- | --- |
| `unindexed-patch-25-09` | `INSUFFICIENT_EVIDENCE`; retrieval 1, query embedding 0, generation 0, 29ms | 결과/evidence/citation 모두 0 | “저장된 요청 범위에서 근거를 찾지 못했습니다.”라고 답했다. 25.09에 변경이 없다고 단정하지 않았으므로 **근거로 뒷받침됨**. |
| `chogath-mid-top-rationale` | `ANSWERED`; query 55 tokens / 2.656s, generation 1,585 / 442 / 2,027 tokens / 5.497s, 총 8.288s | topK 5개 중 초가스 설명, 기본 능력치, E, Q가 전달되었고 E1/E3/E4/E2만 인용했다. 바이 Q(E5)는 전달됐지만 최종 citation에 섞이지 않았다. | 중단에서 강하고 상단에 도움이 필요하다는 조정 의도, 1레벨 공격 속도 `0.625 → 0.658`, E 상향, Q 피해 하향을 설명했다. `+0.033`은 전달 수치의 차이이며, 개인 승률 원인으로 확대하지 않았다. **근거로 뒷받침됨**. |
| `lulu-wild-growth-cooldown` | `ANSWERED`; query 50 tokens / 0.733s, generation 1,532 / 231 / 1,763 tokens / 2.811s, 총 3.558s | topK 5개 중 룰루 R의 정확한 cooldown chunk(E1)를 전달·인용했다. 무관한 결과는 인용하지 않았다. | “재사용 대기시간이 `100/90/80초`에서 `120/100/80초`로 조정되었습니다.”라고 답했다. 단계·단위·방향이 E1과 같아 **근거로 뒷받침됨**. |
| `fiddlesticks-terrify-duration` | `ANSWERED`; query 52 tokens / 0.127s, generation 1,695 / 414 / 2,109 tokens / 4.009s, 총 4.148s | 피들스틱 Q 공포 지속시간의 정확한 수치 chunk(E2)가 전달·인용됐다. 나머지 4개 결과는 인용하지 않았다. | 레벨 1~5를 `1.25/1.5/1.75/2/2.25초 → 1.2/1.4/1.6/1.8/2초`와 각각의 감소량 `-0.05/-0.1/-0.15/-0.2/-0.25초`로 답했다. 모든 차이는 E2 수치와 일치하므로 **근거로 뒷받침됨**. |
| `personal-win-rate-decline` | `INSUFFICIENT_EVIDENCE`; query 39 tokens / 0.149s, generation 1,514 / 317 / 1,831 tokens / 4.268s, 총 4.433s | topK 5개는 아이템/소개/하이라이트 등 개인 경기 데이터와 무관한 내용이었다. statement와 citation은 0개다. | “제공된 25.10 패치 노트만으로는 개인 사용자의 최근 승률 하락 원인을 특정할 수 없습니다.”라고 한계를 명시했다. 개인 성적·인과를 만들지 않았으므로 **근거로 뒷받침됨**. |

초가스 답변에서 실제 전달된 핵심 문구는 “중단에서 지나치게 강하지만 상단에서는 도움이 필요”, “기본 공격 속도와 E를 올려 … 상단 성능을 끌어올림”, “Q 피해량을 하향해 … 중단 성능을 낮춤”이었다. 룰루와 피들스틱은 각각 필요한 단일 수치 chunk를 정확히 인용했다. 이 평가는 DB에 존재하지만 모델에 전달되지 않은 근거를 사용해 보정하지 않았다.

## 네 층 경계별 확인

1. **입력 무결성**: 5개 모두 plan expected/actual UTF-8 question hash와 client transmission hash가 같고 `inputMatches=true`였다. 이전 round의 `?` 손상은 재현되지 않았다.
2. **검색·전달 근거**: 25.09는 빈 corpus로 결과가 없었고, 나머지 4개는 topK 5와 evidence 5를 전달했다. 초가스·룰루·피들스틱의 질문 핵심 수치/설명은 bundle 안에 있었다. 개인 승률 문항에는 필요한 개인 데이터가 없었다.
3. **인용 참조**: `ANSWERED` 3개는 statement가 이번 evidence ID만 참조했고 citation metadata의 source/title/heading/revision이 해당 bundle과 일치했다. 전달됐지만 참조되지 않은 무관한 결과는 최종 citation에 포함되지 않았다.
4. **답변 내용**: 세 답변은 수치·단위·스킬·방향을 유지했고 무관한 챔피언/아이템 설명을 최종 답변에 넣지 않았다. 두 근거 부족 응답은 서로 다른 사유(빈 요청 범위, 개인 데이터 부재)를 구분했다.

## 한계와 보존 기록

- 이 결과는 고정 5문항의 단발 실행이다. retrieval 순위·모델 출력·latency는 다른 실행에서 달라질 수 있다.
- 개인 승률 문항은 올바르게 근거 부족을 반환했지만, 개인 전적 데이터를 결합한 분석 품질을 검증한 것은 아니다.
- OpenAI의 통화 비용이나 raw provider response는 기록하지 않았다. provider가 반환한 usage와 application latency만 위 표에 기록했다.
- raw provider response, API key, vector, 전체 HTML은 문서나 일반 로그에 남기지 않았다.

서버 capture와 client response/transmission은 Git-ignored `.local/rag/answer-evaluation/20260925-utf8-live-1/` 및 그 `client/` 하위에 보존했다. 이전 `.local/rag` snapshot, metadata, plan, export, 과거 evaluation artifact는 변경하거나 덮어쓰지 않았다.

실행 후 이 run에서 시작한 localhost 앱과 격리 PostgreSQL 컨테이너만 정리한다. 문서 변경 뒤 `git diff --check`를 수행한다. 코드 변경은 없으므로 자동 검증은 source가 일치했던 직전 337개 테스트 결과를 재사용하며, 이번 실호출 평가는 그 자동 검증과 별도로 해석한다.
