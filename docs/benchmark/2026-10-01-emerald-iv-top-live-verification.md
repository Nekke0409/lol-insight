# EMERALD IV TOP 수집·분석 실사용 검증 기록 (2026-10-01)

## 범위와 실행 환경

지정 계정의 공통 `BenchmarkPreflightManualSmokeTest`로 현재 rank, 최근 Ranked Solo 최대 20경기, TOP 사용자 경기 수와 본인 제외 Benchmark 준비 상태를 확인하려 했다. 계정 식별자는 이 기록에 남기지 않는다. 실행 소스는 시작 시 clean working tree의 `a5a1634`였으며 코드 변경은 없었다. 기존 자동 테스트 353건의 직전 검증 근거를 재사용하고, 이번에는 해당 수동 테스트만 명시적으로 실행했다.

접속 전 컨테이너가 모두 중지된 것을 확인했다. 개발 DB는 `lol-insight_postgres-1`의 `lol_insight` 데이터베이스와 `lol-insight_postgres-data` volume으로 확인했다. 이 DB와 개발 Redis만 기존 Compose로 시작했다. 별도 RAG B volume과 local-private image는 사용하거나 변경하지 않았다. 다른 수집·Automation·Agent·분석 애플리케이션 프로세스는 확인되지 않았다.

수동 테스트 프로세스에는 Riot outbound pacing(`enabled=true`, `min-interval=2s`, `max-wait=10s`)을 적용했다. Automation/bootstrap, replenishment scheduler/run-once, seed, 다른 manual smoke, Agent와 patch-note Tool, RAG와 RAG answer를 비활성화했다. Riot/OpenAI key는 로컬 설정에서 값 노출 없이 존재 여부만 확인했다.

## 시작 상태와 중단

| 항목 | 시작 | 종료 |
| --- | ---: | ---: |
| EMERALD IV 전체 저장 표본 / 고유 플레이어 | 17 / 4 | 17 / 4 |
| EMERALD IV TOP 전체 저장 표본 / 고유 플레이어 | 0 / 0 | 0 / 0 |
| EMERALD IV cursor `next_page` | 2 | 2 |
| 수집 tick | 0 | 0 |
| 분석 POST / 신규 Job | 0 / 0 | 0 / 0 |

공통 preflight는 최근 경기의 Match Detail을 읽는 도중 `RiotApiOutboundPacingTimeoutException`으로 실패했다. 로컬 pacing admission 10초 한도를 넘긴 것이 확인됐으며, 해당 요청은 HTTP 전송 전에 거부됐다. preflight의 최종 출력 전에 실패했으므로 현재 rank, TOP 사용자 경기 수, 30일 유효 표본 수, 본인 제외 실제 comparison 상태는 **미확인**이다. 전체 저장 TOP 표본 0건만으로 공식 comparison 결과를 대체하지 않았다.

이 timeout은 명시된 중단 조건이다. 추가 진단 Riot 조회, 수집 tick, 분석 POST, Job polling, OpenAI generation은 실행하지 않았다. preflight JVM이 종료되어 Micrometer의 실제 Riot HTTP 시도·응답·pacing wait 계수는 회수하지 못했다. 실패한 한 요청의 admission timeout은 확인됐지만 전체 HTTP 시도 횟수나 429 발생 여부를 수치로 단정하지 않는다.

## 데이터 보존과 남은 검증

시작·종료 시 `tracked_player_automation` 1행, `automation_execution` 1행, 기존 `analysis_job` 2행(FAILED 1, SUCCEEDED 1)의 전체 상태 해시가 각각 일치했다. GOLD I 표본 146건과 cursor 1행, EMERALD IV 외 표본 전체의 상태 해시도 일치했다. 신규 표본, cursor 전진, Job 결과는 없었다. 시작한 개발 DB·Redis 컨테이너는 종료해 원래 중지 상태로 되돌렸고 volume은 보존했다.

현재 rank와 사용자 TOP 경기 수를 확인하지 못했으므로 수집·분석 준비 여부는 판정하지 않는다. 이번 실행에서 실제 피드백, provider usage·latency, 동일 입력과 결과의 수치 대조는 수행되지 않았다. 별도 재시도나 설정 변경 없이 여기서 중단했다.

## 후속 코드 재현·수정 (live 재실행 없음)

후속 작업에서 기존 pacer의 최초 허가 sentinel 산술이 **양수** `nanoTime`에서 오버플로하는 결함을 자동 테스트로 확인했다. 공정 timed lock과 실제 전체 admission 경과 시간 검사를 포함한 수정도 localhost mock HTTP와 자동 테스트로 검증했다. 그러나 위 live 실패가 발생한 정확한 timeout 분기나 실제 대기 시간은 과거 JVM의 지표가 남아 있지 않아 확인할 수 없다. 위의 “10초 한도”는 설정된 `maxWait`를 뜻하며 실제로 10초를 기다렸다는 측정값이 아니다. Match Detail 단계라는 기존 stack trace 근거는 유지한다.

공통 preflight에는 앞으로 성공·예외 종료 시 HTTP와 pacing 지표를 JVM 종료 전에 출력하는 요약을 추가했다. 직전 실행의 HTTP 횟수·429 여부를 후속 테스트 수치로 소급하지 않는다. 이 절의 검증은 Riot/OpenAI 호출이나 개발 DB 접속 없이 수행했으며, 수집·분석 검증을 재개한 결과가 아니다.

## 2026-10-01 공통 preflight 실데이터 재검증 1회

실행 시작은 2026-10-01 23:13:24 KST, 테스트 결과 파일 생성은 약 23:13:51 KST였다. source는 `e7d8dd5`의 clean working tree로, 직전 자동 검증(94 suites, 367 tests, failures 0, errors 0, skipped 7)의 수정 소스와 일치했다. 코드 변경이나 전체 test/build 반복 없이 `--tests`로 공통 `BenchmarkPreflightManualSmokeTest`만 지정하고 `--rerun-tasks`로 실제 실행했다. JUnit 결과는 **1 test, skipped 0, failures 1, errors 0**이다. PowerShell이 JVM stderr 경고를 종료 오류로 처리해 셸 종료 코드는 1이었고, 별도 Gradle 종료 코드는 수집되지 않았다. 테스트 결과 파일과 요약은 실제 preflight 1회가 실행됐음을 확인한다. 이 오류 뒤 재실행하지 않았다.

개발 DB는 `lol_insight`와 기존 `lol-insight_postgres-data` volume으로 확인했으며 Flyway 적용 4건과 source migration 4개가 일치했다. 실행 전후 EMERALD IV 전체 저장 표본 17건/고유 플레이어 4명, cursor page 2, GOLD I 표본 146건/고유 플레이어 31명과 cursor page 3이었다. 전체 `benchmark_sample` 163행과 cursor 2행의 상태 해시가 각각 일치했다. 기존 tracking 1행, Automation execution 1행(`TRIGGERED`), AnalysisJob 2행(`FAILED` 1, `SUCCEEDED` 1)의 상태 해시도 각각 일치했다. 신규 표본·cursor 전진·Job은 없다.

실제 실패는 `comparison_context`에서 첫 `account_by_riot_id` Riot 요청이 **HTTP 401**을 받은 것이다. `PlayerMatchHistoryLoader`는 이 Account 조회 후 Match ID·Detail을 조회하므로 rank, Ranked Solo 경기, TOP 사용자 경기 수, 유효 Benchmark window와 본인 제외 comparison 조회까지 진행하지 못했다. READY/NOT_READY는 판정되지 않았다. 인증 실패는 중단 조건이므로 key 교체, 다른 Riot 진단, preflight 재시도, 수집 tick, 분석 POST는 하지 않았다.

예외 종료 시점의 `benchmark_preflight_riot_observation` 요약은 HTTP 실행 시도 `account_by_riot_id=1`, 수신 상태 `401=1`, upstream 429 `0`, local admission timeout `0`(분기 없음), cooldown 차단 `0`, interrupt `0`이었다. lock 대기 timer는 **count 1 / total 0.029 ms**, interval 대기는 **count 0 / total 0 ms**, 전체 admission 경과는 **count 1 / total 0.3191 ms**였다. 이 timer 값은 해당 실행의 누적 통계이며, 각 count가 1인 항목만 이번 한 admission의 값으로 읽을 수 있다. HTTP 실행 시도 1회는 Riot 서버 내부 처리 횟수의 증명이 아니다. 요약은 preflight 예외 `finally` 시점 snapshot이며 `inFlightCompletion=not_verified`로 출력됐다. 다만 이번 stack trace와 순차 호출 코드를 보면 Match Detail batch worker는 시작되지 않은 것으로 판단한다.

프로세스 범위에서 Riot pacing은 `enabled=true`, `min-interval=2s`, `max-wait=10s`였고 나머지 live 진입점은 비활성화했다. OpenAI key를 전달하지 않았으며 OpenAI 호출 0회, 수집 tick 0회, 분석 POST 0회, 신규 Job 0건이다. 시작한 개발 PostgreSQL·Redis 컨테이너는 다시 중지했고 volume과 기존 RAG B image/volume, `.local` 자료는 건드리지 않았다. 수정된 pacer는 이번에는 **최초 Account admission만** 통과했다. Match Detail의 4-worker pacing이나 과거 timeout 원인은 이번 live 실행으로 확인되지 않았다.

## 2026-10-01 인증 점검 후 공통 preflight 1회

사용자가 직전 401 이후 로컬 key를 확인 또는 갱신했다고 확인했다. `.env`를 UTF-8로 읽어 `RIOT_API_KEY` 정의 1개, 비어 있지 않음, placeholder·따옴표·공백·줄 끝 주석 없음, 현재 프로세스 환경값과 메모리상 일치를 확인했다. `test`에는 `.env` 자동 로딩이 없으므로 실행 프로세스에 필요한 값을 명시적으로 전달했다. 기존 `application.yaml`의 `RIOT_API_KEY` → `RiotApiProperties.key` → `RiotApiConfiguration`의 `X-Riot-Token` 경로를 확인했다. 실제 key를 쓰지 않은 localhost dummy key 테스트로 설정된 RestClient의 헤더 전달을 검증했고 해당 `RiotApiHttpClientTest` 6개가 모두 통과했다. 외부 인증 진단 요청은 하지 않았다. Riot 공식 문서상 401은 필요한 인증 정보 부재, 403은 인증 거부·잘못된 경로 등을 포함하며 개발용 key는 24시간마다 비활성화된다. 따라서 직전 401의 정확한 원인을 소급 확정하지 않는다. 참고: [Riot Developer Portal](https://developer.riotgames.com/docs/portal).

실행 전 HEAD `a77c171`의 production 소스는 직전 367-test 검증 소스 `e7d8dd5`와 같았고, 이번에는 위 localhost 테스트만 추가했다. 전체 test/build는 반복하지 않았다. PowerShell의 stderr 결합 문제를 피하기 위해 `.NET Process`에서 stdout/stderr를 분리 수집했다. 로컬 stderr 경고를 내는 exit 0과 exit 1 명령으로 각각 실제 종료 코드 0과 1을 확인했다. 로그는 Git에서 제외된 `build/preflight-auth-check-20261001`에만 저장했다. 최초 실행 스크립트는 PowerShell 파일 인코딩 파싱 오류로 **프로세스 시작 전** 끝났고 marker가 없음을 확인한 뒤 UTF-8 BOM을 적용했다. 그 뒤 Gradle 테스트 프로세스를 **1회** 시작했다.

실제 시작은 2026-10-01 23:35:51 KST, JUnit XML 갱신은 23:36:29 KST였다. Gradle native 종료 코드는 **1**, JUnit 결과는 **1 test, failures 1, errors 0, skipped 0**이다. `--tests`로 공통 `BenchmarkPreflightManualSmokeTest`만 지정하고 `--rerun-tasks`로 실제 수행했다. JVM stderr 경고와 별개로 테스트가 실제 실패했다. 프로세스 범위에서 pacing은 `enabled=true`, `min-interval=2s`, `max-wait=10s`였고 다른 live 진입점은 비활성화했다. OpenAI key는 전달하지 않았다.

이번에는 첫 Account 인증이 통과했고 Match ID 조회도 완료됐다. Match Detail HTTP 실행을 시도한 뒤 JSON 역직렬화 단계에서 `RiotApiInvalidResponseException`이 발생했다. 하위 예외는 `RiotMatchObjectivesDto.atakhan`의 필수 값이 응답에 없어 객체를 만들 수 없다는 내용이다. `RiotApiHttpClient`는 성공 응답의 DTO 변환 뒤에 상태 계수를 기록하므로 해당 Match Detail의 HTTP 상태는 요약만으로 확정할 수 없다. 응답 원문이나 식별자는 기록하지 않았다. 실패 단계는 `comparison_context`, 안전한 요약 오류 코드는 `OTHER_FAILURE`이다. 새 결함이므로 코드 수정·키 교체·설정 완화·preflight 재실행 없이 종료했다. 재현 근거는 이번 JUnit 실패 XML의 예외 체인과 `RiotMatchTeamDto.kt`의 필수 `atakhan` 선언이다. 수정 범위는 별도 task에서 구형/변형 Match Detail의 선택적 objective 처리와 해당 응답 DTO 테스트를 검토해야 한다.

예외 `finally` 시점 요약의 HTTP 실행 시도는 `account_by_riot_id=1`, `match_ids=1`, `match_detail=1`이고, 상태 계수는 `200=2`였다. 순차 호출 코드상 앞의 두 200은 Account와 Match ID 조회이며, Match Detail 상태는 미확인이다. upstream 429 `0`, local pacing timeout `0`(분기 없음), cooldown 차단 `0`, admission interrupt `3`이었다. lock 대기는 **count 6 / total 4964.0793 ms**, interval 대기는 **count 3 / total 3281.0548 ms**, 전체 admission 경과는 **count 6 / total 8246.3236 ms**이다. 모두 snapshot 시점의 누적 통계이며 특정 한 요청의 시간으로 해석하지 않는다. `inFlightCompletion=not_verified`이므로 남은 Match Detail worker의 최종 상태와 전체 서버 처리 횟수는 확인되지 않았다. 추가 대기나 호출로 지표를 보정하지 않았다.

rank 조회와 공식 Benchmark 비교까지 이르지 못했다. 실제 rank·TOP 사용자 경기 수, 유효 Benchmark window, 전체/본인 제외 TOP 표본과 READY/NOT_READY는 **미확인**이다. 시작 시 저장된 EMERALD IV 전체 표본 17건/고유 플레이어 4명, TOP 표본 0건, cursor page 2는 현재 비교 결과를 대체하지 않는다. 개발 DB `lol_insight`와 기존 `lol-insight_postgres-data` volume을 사용했고 Flyway 적용 4건이 source와 일치했다. 시작·종료의 `benchmark_sample` 163행, cursor 2행, tracking 1행, Automation execution 1행, AnalysisJob 2행의 전체 상태 해시가 각각 일치했다. cache 상태 변화는 별도 측정하지 않았으며 업무 데이터 변경은 없었다. 시작했던 개발 PostgreSQL·Redis는 다시 중지했고 기존 volume, RAG B 자원과 `.local` 자료는 건드리지 않았다. 이번 preflight 1회, 수집 tick 0회, 분석 POST 0회, 신규 Job 0건, OpenAI 호출 0회이다.

## 2026-10-02 Match `atakhan` 선택적 응답 호환성 수정 (live 재실행 없음)

직전 실검증은 첫 Account 인증과 Match ID 조회를 통과한 뒤 Match Detail의 `info.teams[*].objectives.atakhan` 누락으로 DTO 변환에 실패했다. 그 실행의 Match Detail HTTP 상태, 전체 Detail 처리, rank 및 비교 준비 상태는 여전히 미확인이다. 직전 401의 정확한 원인도 확정하지 않는다. 이번 작업에서는 Riot/OpenAI 호출, preflight, 수집 tick, 분석 POST를 실행하지 않았다.

기존 비식별 Match fixture에서 두 팀의 `atakhan`만 제거한 변형으로 production과 같은 Jackson/Kotlin 역직렬화 실패를 먼저 재현했다. 기존 코드는 `RiotMatchObjectivesDto.atakhan` 필수 생성자 값 누락으로 대상 테스트가 실패했다. 이 변형은 실패 구조의 자동 재현 자료이며 당시 Riot 응답 전체를 확보한 자료가 아니다. [공식 26.1 패치 노트](https://www.leagueoflegends.com/en-us/news/game-updates/patch-26-1-notes/)의 아타칸 삭제 사실과 직전 API JSON의 필드 누락 관측은 구분한다.

이후 Riot DTO, 내부 `MatchObjectives`, 공개 `MatchObjectivesResponse`에서 `atakhan`의 부재를 `null`로 보존하도록 수정했다. 객체가 있고 `kills=0`인 값과 과거 양수 값은 유지한다. 잘못된 타입·빈 객체·중첩 필드 누락·다른 필수 objective 누락은 정상 값으로 대체하지 않고 실패시킨다. 미래의 미사용 objective 필드는 기존 설정대로 무시한다. 참가자 데이터와 KDA·CS/min·승패 등의 통계 계산은 변경하지 않았으며, 두 fixture에서 참가자와 계산 결과가 일치함을 확인했다. localhost Match HTTP를 실제 `RiotApiHttpClient → RiotMatchClient → Match` 경로로 통과시켜 두 형식을 검증했다.

실제 Match Detail cache는 Redis의 `JacksonJsonRedisSerializer<Match>`를 사용한다. 해당 serializer로 기존 `atakhan` 포함 payload를 읽고, 새 null payload를 왕복하며, 필드 생략 payload를 읽었다. 참가자와 다른 objective 값도 보존됐다. cache prefix·TTL·version, Redis 데이터는 변경하지 않았다. 공개 응답은 부재 시 JSON `null`이고, OpenAPI는 `atakhan`을 필수 목록에서 제외하고 객체 또는 `null`로 표현한다.

HTTP 관측은 상태를 본문 DTO 변환 전에 한 번 기록한다. 알려진 Jackson 메시지 변환 오류는 새 `riot.api.http.decode_failures` 계수로 분리하고 transport failure로 집계하지 않는다. 응답 이전의 연결·읽기 오류는 상태 없이 transport failure로 집계한다. localhost/mock 테스트에서 정상 200, malformed JSON의 200+decode failure, 429의 단일 상태·rate limit 기록과 cooldown, 응답 전 timeout을 각각 확인했다. 직전 실검증의 Match Detail 상태를 이번 자동 테스트의 200으로 소급하지 않는다. 향후 preflight 요약에는 endpoint별 decode failure가 포함된다.

개발 DB·Redis, Benchmark corpus/cursor, Automation/execution, AnalysisJob, RAG B 자원과 `.local` 자료에는 접근하지 않았다. 전체 자동 검증 결과는 아래 최종 검증 절에 따로 기록한다.

최종 자동 검증은 live flags를 끈 상태에서 `ktlintCheck`, `test`, `build` 모두 종료 코드 0이었다. JUnit XML 집계는 **94 suites, 379 tests, failures 0, errors 0, skipped 7**이다. 변경 문서에 대한 `git diff --check`도 통과했다. 기존 개발 DB·Redis 컨테이너는 실행 전후 모두 중지 상태였다. 이 결과는 선택적 응답 호환성과 로컬 관측 코드에 대한 검증이며, 실제 Riot Match Detail 전체 처리나 Benchmark 비교 성공을 뜻하지 않는다.

## 2026-10-02 atakhan 수정 후 공통 preflight 실데이터 검증 1회

실행 source는 `30d1be8`의 clean working tree이며, 직전 자동 검증(94 suites, 379 tests, failures 0, errors 0, skipped 7)의 수정 소스와 일치했다. 코드 변경이나 전체 test/build 반복 없이 최신 checkout의 Gradle test JVM에서 공통 `BenchmarkPreflightManualSmokeTest`만 `--tests`와 `--rerun-tasks`로 실제 실행했다. 시작은 **2026-10-02 16:28:42 KST**, JUnit XML 갱신은 **16:30:34 KST**였다. Gradle native 종료 코드는 **0**, JUnit은 **1 test, failures 0, errors 0, skipped 0**이다. 프로세스 범위에서 pacing은 `enabled=true`, `min-interval=2s`, `max-wait=10s`였고 수집·Automation·Agent·RAG 진입점은 비활성화했다. OpenAI key는 전달하지 않았다.

기존 개발 PostgreSQL 컨테이너의 mount는 `lol-insight_postgres-data`, 연결 DB는 `localhost:5432/lol_insight`로 확인했다. Flyway 적용 4건과 소스 migration 4개가 일치했다. 실행 전 실제 DB는 전체 `benchmark_sample` 163행, cursor 2행, tracking 1행, Automation execution 1행(`TRIGGERED`), 기존 AnalysisJob 2행(`FAILED` 1, `SUCCEEDED` 1)이었다. EMERALD IV 저장 표본은 전체 17건/고유 플레이어 4명, TOP 0건, cursor page 2였고 GOLD I는 146건/31명, cursor page 3이었다. 이 값은 과거 기록에서 가정한 값이 아니라 이번 실행 전 읽기 전용 조회 결과다.

공통 preflight는 Account → Match ID → Match Detail → rank → Benchmark query → assessment의 `complete` 단계까지 정상 진행했다. 최근 KR Ranked Solo 조회 창은 `start=0, count=20`이고, 관측된 Match Detail HTTP 시도·200 응답은 각각 20건이었다. 현재 Solo rank는 **EMERALD IV**로 예상과 일치하며, 해당 창의 사용자 **TOP 경기 16건**이 집계됐다. 별도의 전체 분석 가능 경기 수 출력은 없으므로 20건 모두가 후속 분석 대상이라고 단정하지 않는다. Benchmark query window는 **2026-09-02T07:30:33.710166100Z 이상, 2026-10-02T07:30:33.710166100Z 미만**이다. EMERALD IV TOP 전체 Benchmark는 **samples 0, unique players 0, NO_DATA**, 본인 제외도 **samples 0, unique players 0, NO_DATA**였다. 대상의 해당 cohort 저장 표본도 0건이다. 실행 결과는 **정상 완료**, 분석 준비 상태는 **NOT_READY**이며 사유는 본인 제외 유효 Benchmark 표본 부재다. 다른 cohort 수집이나 분석 생성은 하지 않았다.

완료 시점의 `benchmark_preflight_riot_observation` 요약은 HTTP 실행 시도 `account_by_riot_id=1`, `match_ids=1`, `match_detail=20`, `league_by_puuid=1`; 수신 상태 `200=23`이었다. `decodeFailuresByEndpoint={}`, upstream 429 `0`, local pacing timeout `0`(분기 없음), cooldown 차단 `0`, admission interrupt `0`이다. transport failure는 기존 요약에 별도 출력되지 않아 계수로 미관측이다. lock 대기 timer는 **count 23 / total 103533.8476 ms**, interval 대기는 **count 22 / total 42892.6196 ms**, 전체 admission 경과는 **count 23 / total 146431.4309 ms**이다. 이 timer는 병렬 admission을 포함한 누적 통계이며 한 요청의 대기 시간이 아니다. `inFlightCompletion=not_verified`는 요약의 고정된 표기다. 정상 완료 경로상 Match Detail batch가 결과를 반환했지만 별도 in-flight 계측으로 worker 종료를 증명한 값은 아니다. HTTP 시도 23회도 Riot 서버 내부 처리 횟수의 증명은 아니다.

이번 조회는 Match Detail 20건의 성공 응답을 관측했지만 각 응답의 `atakhan` 필드 유무는 따로 수집하지 않았다. 과거 실패한 바로 그 경기의 응답이 다시 검증됐는지도 확인할 수 없다. 누락 필드 호환성 자체는 앞 절의 정제 fixture 자동 테스트 근거와 구분한다. 기존 cache는 지우지 않았고 cache 상태 변화는 별도 측정하지 않았다. 실행 전후 `benchmark_sample`, cursor, tracking, Automation execution, AnalysisJob의 각 행 수와 전체 상태 해시가 모두 일치했다. 업무 데이터 변경은 없었다. 시작한 개발 PostgreSQL·Redis 컨테이너는 다시 중지하고 기존 volume을 보존했으며, 이번에 시작한 Docker Desktop도 종료했다. RAG B volume/image와 과거 `.local` 자료는 건드리지 않았다. 이번 **preflight 1회, 수집 tick 0회, 분석 POST 0회, 신규 Job 0건, OpenAI 호출 0회**이다.

## 2026-10-02 EMERALD IV TOP 제한 수집

직전 공통 preflight의 정상 완료를 근거로 수집만 재개했다. 이번에는 preflight, 분석 POST, AnalysisJob 생성, OpenAI, Agent/RAG/Automation, 문서 수집을 실행하지 않았다. 기존 개발 DB lol_insight의 lol-insight_postgres-data mount와 Flyway 적용 4건을 읽기 전용으로 확인했다. 시작 상태는 전체 표본 163행, EMERALD IV 17행/4명, TOP 유효 0행/0명, EMERALD IV cursor 2페이지, GOLD I cursor 3페이지, 기존 AnalysisJob 2행이었다. TOP 조회 범위는 KR / Ranked Solo / EMERALD / IV / POSITION / TOP, gameStartTimestamp 기준 최근 30일이다.

기존 startup run-once 진입점으로 한 프로세스에 한 tick만 실행하고 완료 요약을 확인한 뒤 다음 프로세스를 시작했다. scheduler는 비활성화했다. 프로세스 설정은 cohort EMERALD:IV, tick당 cohort 1개, discovery page 1개, 선수 최대 10명, 선수당 Match 후보 최대 5개, Riot outbound pacing 2초 간격/최대 대기 10초였다. 각 프로세스에는 .env에서 필요한 DB·Redis·Riot 설정만 읽어 전달했고 OpenAI key는 전달하지 않았다. 실행 중인 다른 사용자의 프로세스는 종료하지 않았다.

| tick | 요청 page → 다음 page | 발견/후보/선택 선수 | 신규/중복/무효 표본 | TOP 유효 표본/고유 선수 | TOP 상태 |
| --- | --- | --- | --- | --- | --- |
| 시작 | 2 | - | - | 0 / 0 | NO_DATA |
| 1 | 2 → 3 | 205 / 205 / 10 | 50 / 0 / 0 | 5 / 2 | INSUFFICIENT_SAMPLE |
| 2 | 3 → 4 | 205 / 205 / 10 | 50 / 0 / 0 | 10 / 3 | INSUFFICIENT_SAMPLE |
| 3 | 4 → 5 | 205 / 205 / 10 | 50 / 0 / 0 | 19 / 6 | INSUFFICIENT_SAMPLE |
| 4 | 5 → 6 | 205 / 205 / 10 | 50 / 0 / 0 | 24 / 8 | INSUFFICIENT_SAMPLE |
| 5 | 6 → 7 | 205 / 205 / 10 | 50 / 0 / 0 | 37 / 13 | AVAILABLE |

각 tick에서 선택된 10명은 모두 해당 window의 유효 표본 0건 후보였다. discovery와 collection은 5회 모두 COMPLETED, rateLimitStopped는 false였다. TOP 목표 30건/10명에 5번째 tick에서 도달해 6번째 tick은 실행하지 않았다. 다른 position 표본도 함께 늘었지만 다른 position의 기준 충족을 위해 계속 수집하지 않았다. 이 표는 선수당 최대 5개 후보를 처리한 결과이며, 신규 고유 선수 50명 또는 TOP 유효 표본 250건을 뜻하지 않는다.

각 JVM의 Micrometer HTTP 시도와 200 응답은 각각 61회였다. 5회 합계는 HTTP 시도 305회, 관측된 200 응답 305회다. 401/403/429 응답은 관측되지 않았다. tick 1·3·4·5에서 확보한 Riot pacing 지표는 각 JVM에서 lock wait 61회, interval wait 60회, admission 61회였다. 이들 tick의 누적 interval wait는 약 117.6~118.3초, lock wait는 약 276.0~278.2초, admission elapsed는 약 393.8~396.6초였다. 이는 병렬 요청의 누적 timer 값이며 tick wall-clock 시간이 아니다. tick 2의 HTTP 횟수와 상태는 확인했으나 pacing timer 상세는 프로세스 종료 전에 보존하지 못해 미계측으로 남긴다. decode failure와 local pacing timeout/interrupt/cooldown 차단은 수집 프로세스의 가용 지표에 기록되지 않았고, 완료 요약에는 예외나 rate limit 중단이 없었다. 미계측 지표를 수치 0으로 간주하지 않는다.

종료 시 DB는 전체 표본 413행, EMERALD IV 저장 표본 267행/고유 선수 54명, 그중 TOP 전체 저장 row 56행/20명이었다. 마지막 30일 유효 window(2026-09-02 08:14:48 UTC 이상, 2026-10-02 08:14:48 UTC 미만)의 TOP은 37행/13명으로 AVAILABLE이었다. cursor는 EMERALD IV 7페이지, GOLD I 3페이지였다. GOLD I 표본, Automation tracking/execution, 기존 AnalysisJob의 전체 행 해시가 실행 전후 동일했고 AnalysisJob은 계속 2행이었다. Flyway 4건도 그대로였다. 이번 업무 데이터 변경은 EMERALD IV 신규 표본 250행과 정상 cursor 전진이다. 기존 Redis cache 상태 변화는 따로 측정하지 않았다.

본인 제외 질의에 필요한 PUUID와 이번 표본의 대응을 저장 자료에서 확정하지 못했다. 그러므로 일반 TOP coverage AVAILABLE을 본인 제외 READY로 바꾸어 보고하지 않는다. 실제 분석 직전에 현재 rank·최근 사용 position·본인 제외 comparison 결과를 다시 확인해야 한다. 이번에는 분석을 실행하지 않았다.

시작할 때 중지 상태였던 개발 PostgreSQL·Redis만 기동했고 작업 후 다시 중지했다. 기존 volume, RAG B image/volume 및 .local 자료는 보존했다. Docker Desktop은 공유 자원으로 그대로 두었다. 소스와 실행 jar는 Gradle bootJar 결과를 확인했고 production 코드는 변경하지 않았다. 자동 검증은 직전 94 suites, 379 tests, failures 0, errors 0, skipped 7 기록을 재사용했다. 이번 live 실행에서 전체 test/build/clean은 다시 실행하지 않았다. 문서 변경은 git diff --check로 확인했다.

\r\n
## 2026-10-02 본인 제외 비교 및 비동기 AI 분석 1건

이번 실행의 source는 ba48962였고 시작 시 working tree는 clean이었다. production/test 코드 변경은 없었다. 직전 자동 검증 94 suites, 379 tests, failures 0, errors 0, skipped 7을 재사용했다. 실행 jar는 bootJar만 확인했고 Gradle은 UP-TO-DATE, 종료 코드 0이었다. 개발 PostgreSQL의 연결 DB는 lol_insight, mount는 기존 lol-insight_postgres-data였으며 Flyway 적용 4건이 정상이다. 시작 시 표본 413행, TOP 최근 30일 유효 37건/13명, EMERALD IV cursor 7, GOLD I cursor 3, 기존 Job 2건(FAILED 1, SUCCEEDED 1), Automation tracking/execution 각 1건이었다. 기존 PENDING/RUNNING Job은 없었다.

공통 BenchmarkPreflightManualSmokeTest만 opt-in으로 실제 1회 실행했다. JUnit XML은 tests 1, skipped 0, failures 0, errors 0이며 Gradle 출력은 BUILD SUCCESSFUL이었다. native Start-Process -Wait helper는 빌드 성공 후 Kotlin compile daemon 후손 프로세스를 기다리며 반환하지 않아 helper의 별도 종료 코드 수집은 완료하지 못했다. 이미 기록된 JUnit·Gradle 성공을 확인하고 helper 대기만 중단했으며 preflight는 재실행하지 않았다. preflight의 조회 범위는 KR Ranked Solo start=0, count=20이었다. 현재 Solo rank는 EMERALD IV, TOP 경기 16건, 유효 window는 2026-09-02T09:00:57.485649Z 이상 2026-10-02T09:00:57.485649Z 미만이었다. 전체 TOP Benchmark 37건/13명 AVAILABLE, 본인 제외 37건/13명 AVAILABLE, 대상 본인의 해당 cohort TOP 저장 표본 0건으로 확인되어 READY였다. PUUID는 출력·기록하지 않았다. preflight JVM에서 Riot HTTP 시도는 Account, Match ID, rank 각 1회, 모두 200이었다. Match Detail은 기존 cache 경로를 사용했다. decode failure, 429, local pacing timeout, cooldown 차단, interrupt는 preflight 관측값 0이었고, admission 대기 timer 3회/누적 약 2.573초였다. 이 누적 timer는 전체 wall-clock 시간이 아니다.

preflight가 끝난 뒤 수집·Automation·Agent/RAG를 끈 localhost 앱을 구동하고, 기존 분석 endpoint에 HTTP POST를 정확히 1회 전송했다. 그 전에 PowerShell의 지원하지 않는 옵션이 로컬 인자 바인딩에서 거절된 명령은 HTTP 전송 전 실패해 POST 횟수에 포함하지 않는다. 실제 POST는 202와 새 Job의 Location을 반환했다. Job은 PENDING으로 생성되어 GET polling 3회에서 RUNNING → SUCCEEDED로 확인됐다. 생성 2026-10-02T09:05:46.789643Z, 실행 시작 09:05:46.943323Z, 완료 09:06:07.079464Z였다. 생성부터 완료까지 20.290초, RUNNING 기간은 20.136초다. DB에는 신규 Job 정확히 1건이 SUCCEEDED로 저장되고 result JSONB가 존재하며 failureCode는 없다.

이번 앱 JVM의 analysis.result.cache.requests는 miss 1회였다. ai.generation.requests는 OpenAI 성공 1회, model tag는 gpt-5-mini-2025-08-07, provider timer는 15.490초였다. token usage는 input 1,705, output 1,762, total 3,467, 이 중 reasoning 192였다. 이 값은 실제 Micrometer 측정값이며 모델 설정을 변경하지 않았다. 앱 JVM의 Riot HTTP 시도 3회와 응답 3회는 모두 200(Account, Match ID, rank 각 1회)이고, pacing admission 3회/누적 3.272초였다. Match Detail의 추가 HTTP 요청은 관측되지 않았다. 앱에서 429·decode·pacing 오류 계측은 보이지 않았지만 등록되지 않은 counter를 임의로 수치 0으로 채우지 않는다.

저장된 한국어 feedback은 POSITION(톱) 한 범위만 인용한다. summary는 KDA, 분당 골드, 분당 피해량, 킬 관여, 피해 비중, CS/분이 벤치마크 평균·중앙값보다 높고 시야 점수가 낮다고 설명한다. observation은 userGames 16, 벤치마크 표본 37건/고유 선수 13명을 명시한다. strengths는 KDA 3.905 대 평균 2.735, 분당 골드 457.63 대 394.99, 분당 피해 870.90 대 754.92, 킬 관여율 44.32% 대 30.47%, 팀 피해 비중 26.03% 대 19.92%, CS/분 7.020 대 6.942를 든다. focusArea는 시야 점수/분 0.666 대 0.816이다. 7개 항목의 플레이어 값-평균·중앙값과 differenceFromMean·differenceFromMedian 산술은 모두 일치했다. 결과에는 근거 없는 개인 percentile, 특정 전술 장면, 성적 원인 단정이 없다. caveats는 경기 단위 벤치마크, 혼합 gameVersion 가능성, Backend 집계 재평가를 하지 않는다는 한계를 적는다. 구체적인 개선 행동 제안은 부족하고 관측치 설명 중심이다.

preflight와 저장 feedback의 TOP 경기 수 16, 표본 37건/13명은 일치한다. 다만 worker의 실제 구조화 입력 전체를 별도로 보존한 관측은 없어 두 시점의 입력이 완전히 동일했다고 단정하지 않는다. Job이 성공하고 결과가 저장된 것은 실행 성공이며 feedback 모든 문장의 정확성 보장은 아니다. 수치·단위·비교 방향은 저장 결과 내 근거와 대조했으며 실제 개별 경기 장면은 검증하지 않았다.

종료 시 benchmark_sample 413행 전체 해시, 수집 cursor 2행 전체 해시, Automation tracking/execution 각 1행 전체 해시, 기존 Job 2행 해시가 모두 시작 전과 일치했다. TOP 최근 30일 유효 표본도 37건/13명이었다. 신규 Job 1건과 정상 결과·cache만 이번 산출물로 보존했다. Job terminal 상태 확인 후 이번에 시작한 앱과 개발 PostgreSQL·Redis를 중지했다. 기존 volume, RAG B 자원과 .local 자료는 건드리지 않았고 공유 Docker Desktop은 유지했다. 문서 변경만 git diff --check로 검증했다.

\r\n