# Recent Matches Latency Baseline

이 문서는 최근 경기 endpoint의 Match Detail fan-out 구현 전후 latency를 같은 조건으로
측정하고 기록하는 방법을 정의한다. 이 측정은 성능 테스트 프레임워크가 아니라, 실제 Riot API를
사용하는 local development 환경에서 같은 HTTP 요청을 반복하는 간단한 재현 절차다.

## Scope

측정 대상 endpoint는 다음과 같다.

```text
GET /api/v1/players/{gameName}/{tagLine}/matches?start=0&count={count}
```

각 요청은 다음 순서로 실행된다.

```text
HTTP endpoint
    -> Account-V1 Riot ID lookup (PUUID)
    -> Match-V5 match ID list lookup (start, count)
    -> Match-V5 Match Detail lookup, at most four active calls
    -> target participant summary response
```

Account-V1과 Match ID 목록 조회는 순차 호출이다. Match Detail만 application-managed executor의
sliding window로 처리한다. Detail 완료 순서와 관계없이 응답은 Match ID 목록 순서를 유지한다.

Match Detail 404는 해당 경기를 제외한 partial 200 response가 될 수 있다. 그 외 Riot 4xx/5xx와
transport failure는 endpoint 오류 응답으로 전파될 수 있다. 따라서 response body의 `page` 정보와
HTTP status를 함께 확인한다.

이 baseline은 local backend부터 Riot API까지의 전체 endpoint 응답 시간을 측정한다. Riot API의
네트워크 상태와 대상 계정의 최근 경기 수에 영향을 받으므로, 다른 환경이나 다른 시점의 절대값을
비교하는 용도는 아니다.

## Prerequisites

1. Windows에 `curl.exe`가 있어야 한다. Windows 10/11 기본 설치 환경에서 일반적으로 사용할 수 있다.
2. backend를 실제 Riot API key를 가진 local development 설정으로 실행한다. application process에는
   `RIOT_API_KEY`가 필요하다. `.env` 파일은 이 프로젝트에서 자동으로 읽히지 않는다.
3. benchmark를 실행할 terminal에는 대상 Riot ID를 환경변수 또는 script argument로 전달한다. 실제
   PUUID나 Riot ID, API key를 repository에 기록하지 않는다.

예를 들어 backend를 별도 PowerShell terminal에서 시작한다.

```powershell
$env:RIOT_API_KEY = "your-riot-api-key"
.\gradlew.bat --no-daemon bootRun
```

이미 IDE에서 backend를 실행한다면 해당 run configuration에 `RIOT_API_KEY`를 설정한다. 기본 benchmark
target URL은 `http://localhost:8080`이며, 다른 local port를 사용하면 `BENCHMARK_BASE_URL` 또는
`-BaseUrl`로 변경한다.

## Run

서버가 기동된 뒤, 별도 PowerShell terminal에서 다음처럼 실행한다.

```powershell
$env:BENCHMARK_GAME_NAME = "your-game-name"
$env:BENCHMARK_TAG_LINE = "your-tag-line"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\benchmark-recent-matches.ps1
```

script는 `count=1`, `5`, `10`, `20`을 차례로, 기본 2회씩 직렬 호출한다. 각 호출에 대해 다음을
출력한다.

- `Count`, `Attempt`
- `LatencyMs`: curl의 `time_total`을 millisecond로 변환한 전체 HTTP 요청 시간
- `HttpStatus`
- `Success`: `curl.exe`가 성공했고 HTTP 2xx인 경우에만 `True`
- `CurlExitCode`, `FailureReason`

summary의 평균·최소·최대 latency는 성공한 HTTP 2xx 요청만 사용한다. 실패 요청도 개별 결과와
status 집계에 남기므로, 429·5xx·transport failure를 정상 응답 시간에 섞지 않는다.

반복 횟수는 필요할 때만 늘린다. 실제 Riot API 호출 수는 `count`에 비례하므로, 개발 API key의
rate limit을 피하기 위해 기본값은 2회다. 더 많은 표본이 필요하면 다음과 같이 명시한다.

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\benchmark-recent-matches.ps1 -Iterations 5
```

개별 결과를 CSV로 남기려면 명시적으로 output path를 전달한다. 지정한 파일은 덮어쓴다.

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\benchmark-recent-matches.ps1 -Iterations 2 -OutputPath .\benchmark-results\recent-matches-baseline.csv
```

환경변수 대신 argument도 사용할 수 있다.

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\benchmark-recent-matches.ps1 -GameName "your-game-name" -TagLine "your-tag-line" -BaseUrl "http://localhost:8081"
```

JVM startup, class loading, local DNS/TLS 초기화 같은 cold-start 영향을 제외하려면, 측정 전에 같은
local backend에 `count=1` 요청을 한 번 수동으로 수행하고 그 결과는 기록하지 않는다. benchmark
script는 동시 endpoint 요청을 만들지 않으며, 각 endpoint 요청 내부의 구현을 포함한 latency를 한 요청씩
측정한다.

## Sequential Baseline Before Bounded Concurrency

bounded concurrency 적용 전 실제 Riot API 환경에서 기본 설정(각 count당 2회)으로 다음 결과를
측정했다. 모든 요청은 HTTP 200으로 성공했고 benchmark 실패는 없었다. 표본이 작으므로 절대적인 성능
수치가 아니라 동일 script·count·iterations 조건의 before/after 비교 기준으로만 사용한다.

| Count | Attempts | Successful | Failed | Average latency (ms) | Minimum latency (ms) | Maximum latency (ms) |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 1 | 2 | 2 | 0 | 351.9 | 253.7 | 450.1 |
| 5 | 2 | 2 | 0 | 1,050.8 | 1,047.2 | 1,054.4 |
| 10 | 2 | 2 | 0 | 1,531.3 | 1,432.0 | 1,630.6 |
| 20 | 2 | 2 | 0 | 3,358.4 | 2,944.4 | 3,772.3 |

- count=1: 450.1 ms, 253.7 ms
- count=5: 1,054.4 ms, 1,047.2 ms
- count=10: 1,630.6 ms, 1,432.0 ms
- count=20: 3,772.3 ms, 2,944.4 ms

## Record Template

측정 직전에 commit SHA, backend 실행 방식/JDK, base URL, 대상 shard, 반복 횟수와 측정 시각을 함께
기록한다. 개인 Riot ID, PUUID, API key는 기록하지 않는다.

| Measured at | Commit | Environment notes | Iterations | Result file |
| --- | --- | --- | ---: | --- |
|  |  |  |  |  |

| Count | Attempts | Successful | Failed | HTTP status distribution | Average latency (ms) | Minimum latency (ms) | Maximum latency (ms) | Notes |
| ---: | ---: | ---: | ---: | --- | ---: | ---: | ---: | --- |
| 1 |  |  |  |  |  |  |  |  |
| 5 |  |  |  |  |  |  |  |  |
| 10 |  |  |  |  |  |  |  |  |
| 20 |  |  |  |  |  |  |  |  |

실제 결과는 환경 의존적이다. 위 sequential baseline과 이후 bounded-concurrency 결과에는
민감정보를 제외한 CSV 경로 또는 결과 요약을 함께 기록한다.

## Comparing the Bounded-Concurrency Change

bounded concurrency를 적용한 뒤에도 아래 조건을 고정한다.

- 같은 local backend base URL, JDK, Spring profile, Riot API key 권한과 shard
- 같은 anonymized benchmark target, `start=0`, count 값과 반복 횟수
- 같은 warm-up 여부와 단일 benchmark process 실행 방식
- 동일하거나 최대한 가까운 시점의 Riot API 상태; 429나 partial response는 성공 latency와 분리해 기록

변경 전후의 각 count에서 성공/실패 수와 HTTP status 분포를 먼저 비교한 뒤, 성공한 응답의
평균·최소·최대 latency를 비교한다. count가 커질수록 sequential baseline의 누적 latency가 보이는지와,
bounded concurrency 적용 후 그 기울기가 낮아지는지를 확인한다. baseline보다 실패율이나 429가
늘어난다면 latency 개선만으로 성능 개선으로 판단하지 않는다.
