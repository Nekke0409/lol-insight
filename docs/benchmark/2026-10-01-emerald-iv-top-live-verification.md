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
