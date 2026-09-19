# GOLD I replenishment one-tick 사전 검증 기록 (2026-09-19)

## 목적

Docker 기반 전체 회귀가 통과한 경우에만 기존 local corpus를 대상으로 GOLD I bounded replenishment one-tick을 실행하려 했다. 이 기록은 실행 전 중단 조건 확인 결과를 남긴다.

## 검증 설정과 결과

- Docker Desktop Linux engine 접근을 확인했다.
- 전체 회귀는 외부 smoke와 automation을 모두 `false`로 둔 상태에서 `.\gradlew.bat ktlintCheck test build`로 실행했다.
- 결과는 250 tests 중 247 passed, 0 failed, 3 skipped였다. skip은 `RUN_OPENAI_SMOKE_TEST`, `RUN_BENCHMARK_SEED`, `RUN_PLAYER_COMPARISON_SMOKE_TEST`로 비활성화한 manual smoke 세 건뿐이다.
- PostgreSQL Testcontainers 통합 테스트와 Redis Testcontainers 통합 테스트가 실제 `test` task 실행에 포함됐다.
- 최초 전체 명령에서 cache 상태였던 `ktlintCheck`는 `.\gradlew.bat ktlintCheck --rerun-tasks`로 별도 실제 실행했고, 7 tasks 모두 성공했다.
- `git diff --check`는 오류 없이 통과했다.

## local corpus 사전 확인

기존 `docker-compose.yml`로 PostgreSQL과 Redis를 기동한 뒤 PostgreSQL에 read-only 조회를 실행했다. local profile의 기본 대상은 `localhost:5432/lol_insight`이며 compose 기본 대상과 일치한다.

그러나 이 DB의 `flyway_schema_history`에는 V1, V2만 적용되어 있었다. `benchmark_replenishment_cursor` 테이블이 존재하지 않았고, `kr / 420 / GOLD / I` 조건의 `benchmark_sample`은 0건이었다. 따라서 기존 benchmark corpus가 보존된 DB라는 전제가 성립하지 않았다.

## one-tick 결과

`RUN_BENCHMARK_REPLENISHMENT_ONCE=true` 프로세스는 시작하지 않았다. Riot API 호출, sample 저장, cursor 생성·변경은 발생하지 않았다.

| Position | before sampleCount | after sampleCount | before/after uniquePlayerCount | availability | samplesNeeded / uniquePlayersNeeded |
| --- | ---: | ---: | ---: | --- | --- |
| TOP | N/A | N/A | N/A | N/A | N/A |
| JUNGLE | N/A | N/A | N/A | N/A | N/A |
| MIDDLE | N/A | N/A | N/A | N/A | N/A |
| BOTTOM | N/A | N/A | N/A | N/A | N/A |
| UTILITY | N/A | N/A | N/A | N/A | N/A |

- cohort와 one-tick budget: 실행하지 않음 (예정값은 `GOLD:I`, cohorts 1, page 1, players 10, matches/player 5).
- queryWindow: 실행하지 않아 생성되지 않음.
- requestedPage, pagesProcessed, nextPage 및 DB 저장값: cursor table 부재로 측정 불가.
- createdSamples, skippedDuplicates, skippedInvalidSamples: 실행하지 않아 모두 N/A.
- discovery/collection outcome, rateLimitStopped, retryAfterSeconds: 실행하지 않아 모두 N/A.

## 한계와 다음 조건

이번 결과는 run-once의 성공·429·cursor 전진 동작을 검증하지 않는다. 기존 corpus와 V4 cursor migration이 적용된 의도한 local DB를 확인한 뒤에만, 별도 one-tick으로 다시 검증할 수 있다. 이 기록에서는 API key, PUUID, Riot ID, Match ID, raw response를 저장하거나 출력하지 않았다.
