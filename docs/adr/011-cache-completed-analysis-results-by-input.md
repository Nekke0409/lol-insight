# ADR-011: 구조화된 분석 입력 기반 Redis completed-result cache 사용

상태: Accepted

## 배경

`PlayerAnalysisService`는 Riot 데이터 정규화, 통계, Peer Benchmark 및 comparison 계산 뒤
`PlayerAnalysisInput`을 만들고 OpenAI generation을 호출한다. 같은 effective input에 대해 provider 호출을 반복하면
비용과 latency가 증가한다. HTTP URL이나 Riot ID는 실제 provider input이 아니며 개인정보를 Redis key에 노출할 수 있으므로
결과 cache identity로 적합하지 않다.

기존 async in-flight dedupe는 같은 client의 동일 HTTP request가 `PENDING` 또는 `RUNNING`인 짧은 기간만 하나의
`AnalysisJob`으로 합친다. 완료된 결과를 재사용하지 않고 sync endpoint도 대상이 아니므로, 완료된 generation 중복을 줄이지 못한다.

## 결정

`PlayerAnalysisService`의 `PlayerAnalysisInput -> PlayerAnalysisGenerator` 경계에 provider-independent completed-result
cache를 둔다. sync endpoint와 async worker는 같은 service를 호출하므로 하나의 cache 경로를 공유한다.

- `PlayerAnalysisInput`의 결정적 comparison/metric/limitation 순서와 Spring Boot `ObjectMapper` JSON을 SHA-256으로
  digest해 `analysis:result:{version}:{fingerprint}` Redis key를 만든다. raw input, Riot ID, PUUID, match ID를 key에 넣지 않는다.
- 기본 version은 `analysis-result-v1`, 기본 TTL은 30분이다. prompt, output schema, result contract, generation policy 등
  output semantics가 바뀌면 version을 명시적으로 bump한다.
- 성공한 `PlayerAnalysisResult`만 typed JSON snapshot으로 저장한다. 실패·timeout·rate limit·malformed response에는
  negative cache를 두지 않는다.
- Redis read/write, corrupted value cleanup, cache metric failure는 fail-open이다. read failure는 miss로, write failure는
  저장 생략으로 처리한다. corrupted value는 가능하면 삭제하지만 raw content를 기록하지 않는다.
- low-cardinality metric `analysis.result.cache.requests{outcome=hit|miss}`만 기록한다. hit은 provider 호출 및
  `ai.generation.*` metric 증가를 만들지 않는다.

cache hit이어도 HTTP generation rate limit의 기존 소비 순서는 바꾸지 않는다. async POST는 항상 job lifecycle을 유지하며,
worker가 cache hit으로 `SUCCEEDED`가 되어도 terminal job ID를 재사용하지 않는다.

## 대안

### URL 또는 Riot ID 기반 HTTP response cache

HTTP parameter는 최종 structured input과 일대일로 대응하지 않는다. rank, benchmark, match/statistics 변화가 result에 영향을
주어도 URL만으로는 그 차이를 표현할 수 없고, raw identifier를 Redis key에 노출한다. 선택하지 않는다.

### OpenAI adapter 내부 cache

provider adapter가 Redis와 cache policy를 소유하면 application의 shared generation boundary가 흐려지고, provider 교체 시
cache behavior도 함께 바뀐다. 선택하지 않는다.

### Caffeine-only completed cache

restart 시 사라지고 다중 instance가 결과를 공유하지 못한다. 이미 Redis infrastructure가 있으며 TTL 기반 ephemeral result에
적합하므로 선택하지 않는다.

### distributed single-flight 또는 cache lock

동시 miss stampede는 줄일 수 있지만 atomic lock, lease, cleanup과 장애 정책을 추가한다. 현재 bounded executor, rate limit,
async in-flight dedupe의 관측 결과로 실제 필요성이 확인될 때까지 보류한다.

## 결과

동일 effective input은 최신 Riot/benchmark 계산을 마친 뒤에만 result를 재사용하므로, raw source 변화가 final input을 바꾸면
자연스럽게 miss가 된다. 반대로 final input이 같으면 raw source의 무관한 변화와 관계없이 같은 LLM 결과를 재사용한다.

Redis는 source of truth가 아닌 비용·latency 최적화다. Redis 장애가 분석 기능을 중단시키지 않지만, cache miss가 동시에 여러
instance에서 발생할 때 중복 generation을 막지는 않는다. cache version을 잊고 output semantics를 바꾸면 stale result를
재사용할 위험이 있으므로 release checklist에 version 검토를 포함한다.
