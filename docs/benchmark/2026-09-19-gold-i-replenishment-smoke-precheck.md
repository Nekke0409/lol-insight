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

## 후속 read-only 진단 (2026-09-19)

이 절은 기능 명세가 아니라, 위 사전 검증 기록의 후속 시점 관측이다. 애플리케이션 시작, Riot/OpenAI 호출, benchmark seed·replenishment tick, Flyway 작업, 데이터 변경은 수행하지 않았다.

### 이전 진단 SQL과 region 조건 정정

기존 본문에는 `kr / 420 / GOLD / I` 조건에서 0건이었다는 **결과 표기**만 남아 있고, 이전 실행에 사용한 SQL 원문은 이 문서와 작업 첨부 기록에 보존되어 있지 않다. 따라서 실제 SQL이 `region = 'kr'`였는지는 확인할 수 없다.

`RankedPlayerDiscoveryService`의 `KR_REGION` 상수는 `"KR"`이다. PostgreSQL 문자열 비교는 기본적으로 대소문자를 구분하며, 이번에 확인한 저장값도 `KR`이다. 그러므로 이전 SQL이 실제로 `region = 'kr'`였다면 그 조건은 현재 corpus에 대해 0건을 반환하는 잘못된 조건이다. 기존 본문의 소문자 `kr` 표기는 단순 보고 표기였는지 실제 SQL 조건이었는지는 판정하지 않는다.

### 실행한 read-only SQL과 관측 결과

현재 Compose PostgreSQL에 대해 `BEGIN TRANSACTION READ ONLY` 안에서 catalog를 먼저 조회해 실제 schema를 찾은 후 아래 조회를 실행하고 `COMMIT`했다. 식별자 원문은 조회·기록하지 않았다.

```sql
BEGIN TRANSACTION READ ONLY;
SELECT current_database(), current_user;
SELECT current_schema(), current_schemas(true), current_setting('search_path');
SELECT COALESCE(inet_server_addr()::text, 'local-socket'), inet_server_port();
SELECT table_schema, table_name
FROM information_schema.tables
WHERE table_type = 'BASE TABLE'
  AND table_name IN ('benchmark_sample', 'flyway_schema_history');
SELECT COUNT(*) FROM public.benchmark_sample;
SELECT region, queue_id, tier, division,
       COUNT(*), COUNT(DISTINCT puuid)
FROM public.benchmark_sample
GROUP BY region, queue_id, tier, division;
SELECT installed_rank, version, description, type, success
FROM public.flyway_schema_history
ORDER BY installed_rank;
COMMIT;
```

- 현재 DB/user: `lol_insight` / `lol_insight`
- 현재 schema: `public`; `current_schemas(true)`: `{pg_catalog,public}`; `search_path`: `"$user", public`
- 서버 조회는 컨테이너 내부 Unix socket을 사용했으므로 주소는 `local-socket`, 포트는 SQL 결과상 NULL이었다. Compose의 host 포트 매핑은 `5432:5432`이다.
- `benchmark_sample`과 `flyway_schema_history`는 모두 `public` schema에 있었다.
- 필터 없는 `public.benchmark_sample` 전체 수는 **141건**이다. 즉 sample 전체가 0건이 아니다.
- 저장된 cohort는 한 가지뿐이며 `KR / 420 / GOLD / I = 141 samples / 31 distinct PUUID`이다. 원본 PUUID, player 또는 match row는 출력하지 않았다.
- Flyway 적용 이력은 성공한 V1 (`create benchmark sample`), V2 (`create analysis job`) 두 건이다. V3/V4가 미적용인 사실과 corpus 존재 여부는 별도 관측이다.

### 연결 및 corpus 판단

현재 `lol-insight-postgres-1` Compose service에 컨테이너 내부 `psql`로 연결해 위 결과를 얻었다. `application-local.yaml`의 기본 대상은 `localhost:5432/lol_insight`이고 Compose의 기본 DB/user 및 공개 포트와 일치한다. 다만 Compose가 읽은 값과 실행 중인 Spring 프로세스에 주입된 override가 같다고 가정하지 않았다. 이번 작업에서는 Spring 프로세스를 시작하거나 그 환경을 변경하지 않았다.

현재 실행 중인 Compose PostgreSQL에서 기존 corpus(141건)를 확인했으므로, 다른 Compose project·중지 컨테이너·volume·로컬 PostgreSQL 서비스의 추가 탐색은 불필요하여 수행하지 않았다. volume mount와 다른 volume 후보는 확인하지 않았다.

### 결론과 다음 최소 조치

이전의 0건은 sample 부재가 아니라 소문자 `kr` 조건이 실제로 사용됐을 경우 설명된다. 다만 이전 SQL 원문이 남아 있지 않아 이를 확정 원인으로 기록하지 않는다. 다음에 one-tick smoke를 검토할 때만, `KR / 420 / GOLD / I`와 현재 corpus를 전제로 하고 V3/V4 미적용 문제를 별도로 해결할지 판단한다. 이번 진단에서는 새 corpus 수집이나 DB 상태 변경을 하지 않았다.

## 운영 검증 실제 실행 기록 (2026-09-19)

이 절은 위 사전 진단과 구분한 실제 운영 검증 관측 기록이다. production/test 코드, migration 파일,
schema 설정 파일은 수정하지 않았다.

### 실행 대상과 백업

- 실행 시각: 2026-09-19 18:30~18:31 (Asia/Seoul)
- 대상: local profile의 `jdbc:postgresql://localhost:5432/lol_insight`, `public` schema. Compose PostgreSQL의
  공개 포트와 일치함을 확인했고, 실행 중인 Spring 애플리케이션은 없었다.
- Spring/Flyway 전용 datasource/schema override 환경 변수는 모두 unset 상태였다.
- 적용 전 read-only 집계: `benchmark_sample` 전체 141건, `KR / 420 / GOLD / I` 141건 / distinct PUUID 31명,
  Flyway V1/V2 성공, V3/V4 미적용.
- migration 전에 저장소 밖의 로컬 경로에 PostgreSQL native `pg_dump` custom-format archive를 생성했다.
  archive는 비어 있지 않았고 `pg_restore --list`가 성공했다. archive 경로나 내용은 이 문서에 기록하지 않는다.

### A 단계: 외부 호출 없이 migration 적용

실행 프로세스에 아래 opt-in/automation 값은 모두 `false`로 주입했고 Riot/OpenAI 키는 빈 값으로 두었다.

- `RUN_OPENAI_SMOKE_TEST`, `RUN_BENCHMARK_SEED`, `RUN_PLAYER_COMPARISON_SMOKE_TEST`
- `ANALYSIS_AUTOMATION_ENABLED`, `ANALYSIS_AUTOMATION_BOOTSTRAP_ENABLED`
- `BENCHMARK_REPLENISHMENT_ENABLED`, `RUN_BENCHMARK_REPLENISHMENT_ONCE`

기존 `bootRun` startup Flyway 경로는 `public` schema에 V3 (`create ranked match automation`)와
V4 (`create benchmark replenishment cursor`)를 성공적으로 적용했다. JPA `EntityManagerFactory` 초기화도
완료되어 `ddl-auto=validate` mapping 검증 구간을 통과했다.

하지만 애플리케이션의 최종 기동은 실패했다. 현재 `RiotApiProperties.key`는 `@NotBlank`이고,
요청한 빈 `RIOT_API_KEY`는 property bind validation에서 거부된다. 이 실패는 Riot client가 만들어지기 전의
configuration binding 단계에서 발생했으며, Riot/OpenAI HTTP 호출, benchmark collection, automation은
실행되지 않았다. 이 단계가 시작한 Spring 프로세스는 종료되었고, 자동 repair/rollback/restore나 코드 변경은
수행하지 않았다.

후속 read-only 확인 결과는 다음과 같다.

- Flyway V1~V4 모두 성공 상태다.
- `tracked_player_automation`, `automation_execution`, `benchmark_replenishment_cursor` 테이블이 존재한다.
- `benchmark_sample` 전체 141건, `KR / 420 / GOLD / I` 141건 / distinct PUUID 31명으로 적용 전과 같다.
- `benchmark_replenishment_cursor` row는 0건이다.

### B 단계: GOLD I bounded one-tick smoke

A 단계의 최종 startup/validation이 성공하지 않았으므로 B 단계는 실행하지 않았다. 따라서 아래 값은 관측되지
않았으며 모두 `N/A (미실행)`이다.

- queryWindow 및 POSITION별 before/after `sampleCount`, `uniquePlayerCount`, `availability`,
  `samplesNeeded`, `uniquePlayersNeeded`
- `requestedPage`, `pagesProcessed`, `nextPage`, cursor DB 상태 변화
- `createdSamples`, `skippedDuplicates`, `skippedInvalidSamples`
- discovery/collection outcome, `rateLimitStopped`, `retryAfterSeconds`

`RUN_BENCHMARK_REPLENISHMENT_ONCE=true` 프로세스는 시작하지 않았으므로 run-once opt-in 값의 잔존 상태도 없다.

## Riot key 설정 후 재실행 기록 (2026-09-19)

### 재실행 사전 확인과 A 단계

- `RIOT_API_KEY`가 실행 환경에 존재함을 값 노출 없이 확인했다. `OPENAI_API_KEY`는 실행 프로세스에서 빈 값으로
  덮어썼다.
- one-tick 전에 현재 V4 적용 DB를 다시 PostgreSQL native custom-format archive로 저장했다. archive는 비어 있지
  않았고 `pg_restore --list`가 성공했다. archive 경로나 내용은 기록하지 않는다.
- 재실행 전 read-only 집계는 `benchmark_sample` 141건, `KR / 420 / GOLD / I` 141건 / distinct PUUID 31명,
  cursor 0건이었다.
- A 단계는 local profile로 정상 기동했고 health endpoint가 `200`을 반환했다. Flyway는 V1~V4 네 migration을
  성공적으로 validate하고 `public` schema가 V4 최신 상태라 migration이 필요 없다고 확인했다. JPA
  `ddl-auto=validate` 구간도 통과했다.

### B 단계: GOLD I bounded one-tick

다음 값만 실행 프로세스에 적용했다: `RUN_BENCHMARK_REPLENISHMENT_ONCE=true`, `GOLD:I`, cohort 1개,
page 1개, player 10명, matches/player 5개. scheduler와 다른 smoke/automation opt-in은 모두 `false`였다.
이후 추가 tick, manual seed, cursor 수동 수정, 429/cooldown 재시도는 수행하지 않았다.

- tick outcome: `RATE_LIMIT_STOPPED`
- queryWindow: `2026-08-20T09:38:27.600688100Z` 이상,
  `2026-09-19T09:38:27.600688100Z` 미만

| Position | before sampleCount / uniquePlayerCount | after sampleCount / uniquePlayerCount | before availability / needed samples·players | after availability / needed samples·players |
| --- | --- | --- | --- | --- |
| TOP | 14 / 8 | 14 / 8 | INSUFFICIENT_SAMPLE / 16·2 | INSUFFICIENT_SAMPLE / 16·2 |
| JUNGLE | 18 / 8 | 18 / 8 | INSUFFICIENT_SAMPLE / 12·2 | INSUFFICIENT_SAMPLE / 12·2 |
| MIDDLE | 11 / 9 | 11 / 9 | INSUFFICIENT_SAMPLE / 19·1 | INSUFFICIENT_SAMPLE / 19·1 |
| BOTTOM | 15 / 7 | 19 / 7 | INSUFFICIENT_SAMPLE / 15·3 | INSUFFICIENT_SAMPLE / 11·3 |
| UTILITY | 7 / 6 | 8 / 7 | INSUFFICIENT_SAMPLE / 23·4 | INSUFFICIENT_SAMPLE / 22·3 |

- `requestedPage=1`, `pagesProcessed=1`, `nextPage=2`. 후속 read-only DB 조회에서도 `KR / 420 / GOLD / I`
  cursor의 `next_page`는 2였다.
- `createdSamples=5`, `skippedDuplicates=15`, `skippedInvalidSamples=0`.
- discovery outcome은 `COMPLETED`, collection outcome은 `RATE_LIMITED`, `rateLimitStopped=true`,
  `retryAfterSeconds=1`이다.
- 후속 read-only 집계는 `benchmark_sample` 전체 및 cohort 모두 146건, distinct PUUID 31명이다. queryWindow
  내 POSITION 집계는 summary의 after 값과 일치한다.

run-once summary가 출력된 뒤 이 실행이 생성한 Spring/Gradle 프로세스 트리만 종료했다. 비대화형 실행 환경이라
일반 종료 신호가 자식 JVM에 의해 거부되어 해당 프로세스 트리에 강제 종료를 사용했다. 이후 Spring/Gradle
run-once 프로세스가 남아 있지 않음을 확인했다.
