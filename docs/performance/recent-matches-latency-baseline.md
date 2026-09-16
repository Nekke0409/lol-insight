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

## Redis Match Detail Cache 스모크 테스트

Backend를 시작하기 전에 로컬 Redis 서비스를 시작한다.

```powershell
docker compose up -d redis
```

기존 `RIOT_API_KEY` 설정을 사용하고 Redis가 다른 곳에서 실행 중인 경우가 아니라면 `REDIS_HOST=localhost`와
`REDIS_PORT=6379`은 로컬 기본값으로 둔다. 동일한 단일 Match 요청을 두 번 보내거나, 같은 플레이어 및 페이지네이션
값으로 동일한 최근 Match 요청을 두 번 보낸다.

- 첫 번째 요청에서는 이전에 캐시되지 않은 각 Match Detail이 cache miss가 되어 Riot을 호출한다.
- 두 번째 요청에서는 Match-V5 Detail HTTP 요청 없이 캐시된 Match Detail 값을 Redis에서 반환한다. Account 조회와
  Match ID 목록 조회는 의도적으로 계속 캐시하지 않는다.

애플리케이션 DEBUG 로그를 추가하지 않고도 필요할 때 Redis 컨테이너에서 키를 확인할 수 있다.

```powershell
docker compose exec redis redis-cli --scan --pattern "match:detail:*"
```

예상 키 형식은 `match:detail:{matchId}`다. 이는 로컬 스모크 테스트일 뿐이며, 콜드 캐시 트래픽이 Riot 429 응답을
피한다는 것을 증명하지는 않는다.

## Redis Cache 실험 템플릿

이 문서에는 아직 Redis cache latency 값을 기록하지 않았다. 추정값을 넣지 않는다. 이후 비교할 때는 동일한 대상,
count, 반복 횟수, 로컬 backend, JDK, shard, API key 범위로 별도 실험을 실행한다.

- **콜드 캐시:** 대상 Match Detail 키가 아직 없는 Redis 인스턴스를 사용한 뒤 benchmark를 한 번 실행한다.
- **웜 캐시:** 대상 Match Detail 키가 유효한 동안 동일한 benchmark를 반복한다.

아래 템플릿에 결과 CSV 경로와 관련 Redis 상태를 기록한다. 전체 endpoint latency에는 캐시하지 않은 Account-V1과
Match-ID 목록 호출도 포함되므로, latency와 함께 HTTP 상태 및 성공 건수를 보고한다.

| 캐시 상태 | 측정 시각 | 커밋 | Count | 반복 횟수 | 결과 파일 | 비고 |
| --- | --- | --- | ---: | ---: | --- | --- |
| Cold |  |  |  |  |  |  |
| Warm |  |  |  |  |  |  |

## 실행

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
- `RetryAfterSeconds`: HTTP 429 응답에 정수 초 단위 `Retry-After` header가 있을 때만 기록하며, 그 외에는 비어 있다.

summary의 평균·최소·최대 latency는 성공한 HTTP 2xx 요청만 사용한다. 실패 요청도 개별 결과와
status 집계에 남기므로, 429·5xx·transport failure를 정상 응답 시간에 섞지 않는다.

반복 횟수는 필요할 때만 늘린다. 실제 Riot API 호출 수는 `count`에 비례하므로, 개발 API key의
rate limit을 피하기 위해 기본값은 2회다. 더 많은 표본이 필요하면 다음과 같이 명시한다.

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\benchmark-recent-matches.ps1 -Iterations 5
```

특정 count만 독립적으로 실행하려면 `-Count`를 지정한다. 지정하지 않으면 기존과 같이
`count=1, 5, 10, 20` 전체를 순서대로 실행한다.

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\benchmark-recent-matches.ps1 -Count 5 -Iterations 1
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\benchmark-recent-matches.ps1 -Count 10 -Iterations 1
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\benchmark-recent-matches.ps1 -Count 20 -Iterations 1
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

## Bounded Concurrency 적용 전 순차 기준선

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

개별 attempt 원본값은 이 repository에 보존되어 있지 않다. 따라서 이 baseline의 summary 값과
문서에 이미 기록된 attempt 값만 비교 기준으로 사용하며, 이를 바탕으로 raw CSV를 추정 생성하지 않는다.

## Bounded Concurrency=4 결과

ADR-004의 bounded concurrency=4 적용 후 실제로 수행한 benchmark 결과는
[`results/recent-matches-concurrency-4.csv`](results/recent-matches-concurrency-4.csv)에 보존한다.
이 결과는 `count=1` 2회, `count=5` 2회, `count=10` 2회, `count=20` 2회를 **이 순서대로 연속 실행**한
측정이다. 순차 baseline의 raw attempt CSV는 없으므로 `results/recent-matches-sequential.csv`는 만들지 않았다.

| Count | Attempts | Successful | Failed | HTTP status distribution | Average latency (ms) | Minimum latency (ms) | Maximum latency (ms) |
| ---: | ---: | ---: | ---: | --- | ---: | ---: | ---: |
| 1 | 2 | 2 | 0 | 200x2 | 686.5 | 313.3 | 1,059.6 |
| 5 | 2 | 2 | 0 | 200x2 | 424.7 | 411.2 | 438.1 |
| 10 | 2 | 1 | 1 | 200x1, 429x1 | 560.9 | 560.9 | 560.9 |
| 20 | 2 | 0 | 2 | 429x2 | N/A | N/A | N/A |

- count=10에서 HTTP 429가 1회 발생했다.
- count=20에서 HTTP 429가 2회 발생했다.
- count=20에는 성공 HTTP 2xx 응답이 없으므로 성공 응답 latency 및 latency 개선율을 계산할 수 없다.
- CSV의 `RetryAfterSeconds`는 당시 제공된 측정값에 포함되어 있지 않아 비어 있다. 이후 실행에서는 script가
  429의 정수 초 단위 `Retry-After` header를 기록한다.

이 연속 benchmark만으로 429의 원인을 concurrency=4 자체로 확정할 수 없다. 앞선 count의 Riot API 호출이
누적 quota에 영향을 주었을 수 있고, count가 클수록 짧은 시간에 Detail 요청이 집중되는 fan-out burst도 함께
영향을 줄 수 있다. ADR-004의 동시성 4는 in-flight Detail 호출 상한이지 request-per-second 제한이나
process-wide cooldown이 아니다. 따라서 위 성공 응답 latency는 관측값으로만 보존하며, 순차 baseline과의
개선율 또는 429의 단일 원인으로 해석하지 않는다.

### 다음 격리 실험

각 experiment는 rate-limit 영향이 없는 상태에서 별도 PowerShell process로 한 번만 실행한다.
고정 sleep이나 Riot rate-limit window의 시간값을 script에 추가하지 않는다.

```powershell
# 실험 A
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\benchmark-recent-matches.ps1 -Count 5 -Iterations 1

# 실험 B
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\benchmark-recent-matches.ps1 -Count 10 -Iterations 1

# 실험 C
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\benchmark-recent-matches.ps1 -Count 20 -Iterations 1
```

가능하면 동일 count에서 sequential 구현과 concurrency=4 구현을 각각 fresh 조건으로 독립 실행해 비교한다.
순차 구현 확인이 필요하면 git history에서 ADR-004 적용 직전 commit을 확인하거나, 별도 git worktree에서
그 revision을 실행한다. 현재 branch의 checkout/reset이나 git history를 변경하지 않는다.

다음 결과를 기준으로 후속 결정을 내린다.

- isolated concurrency=4는 성공하지만 연속 benchmark에서만 429가 나면, 누적 quota 영향의 증거다.
- 같은 fresh 조건에서 sequential은 성공하지만 concurrency=4가 반복적으로 429이면, Detail request burst 영향의 증거다.
- 같은 count의 isolated run에서 두 구현 모두 429이면, 이 recent-matches use case의 upstream 호출량이 현재 Riot key 제한과 충돌할 가능성이 있다.
- 어느 조건도 반복적으로 재현되지 않으면, 현재 데이터만으로 하나의 원인을 확정할 수 없다고 기록한다.

## 기록 템플릿

측정 직전에 commit SHA, backend 실행 방식/JDK, base URL, 대상 shard, 반복 횟수와 측정 시각을 함께
기록한다. 개인 Riot ID, PUUID, API key는 기록하지 않는다.

| 측정 시각 | 커밋 | 환경 비고 | 반복 횟수 | 결과 파일 |
| --- | --- | --- | ---: | --- |
|  |  |  |  |  |

| Count | 시도 | 성공 | 실패 | HTTP 상태 분포 | 평균 latency(ms) | 최소 latency(ms) | 최대 latency(ms) | 비고 |
| ---: | ---: | ---: | ---: | --- | ---: | ---: | ---: | --- |
| 1 |  |  |  |  |  |  |  |  |
| 5 |  |  |  |  |  |  |  |  |
| 10 |  |  |  |  |  |  |  |  |
| 20 |  |  |  |  |  |  |  |  |

실제 결과는 환경 의존적이다. 위 sequential baseline과 이후 bounded-concurrency 결과에는
민감정보를 제외한 CSV 경로 또는 결과 요약을 함께 기록한다.

## Bounded Concurrency 변경 비교

bounded concurrency를 적용한 뒤에도 아래 조건을 고정한다.

- 같은 local backend base URL, JDK, Spring profile, Riot API key 권한과 shard
- 같은 anonymized benchmark target, `start=0`, count 값과 반복 횟수
- 같은 warm-up 여부와 단일 benchmark process 실행 방식
- 동일하거나 최대한 가까운 시점의 Riot API 상태; 429나 partial response는 성공 latency와 분리해 기록

변경 전후의 각 count에서 성공/실패 수와 HTTP status 분포를 먼저 비교한 뒤, 성공한 응답의
평균·최소·최대 latency를 비교한다. count가 커질수록 sequential baseline의 누적 latency가 보이는지와,
bounded concurrency 적용 후 그 기울기가 낮아지는지를 확인한다. baseline보다 실패율이나 429가
늘어난다면 latency 개선만으로 성능 개선으로 판단하지 않는다.
