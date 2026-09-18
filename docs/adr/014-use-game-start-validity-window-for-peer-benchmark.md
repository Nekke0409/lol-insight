# ADR-014: Peer Benchmark에 경기 시작 시각 기반 유효 표본 기간 사용

상태: Accepted

## 배경

`BenchmarkSample`은 append-only에 가까운 participant-level 관측치다. 수집 시각이 최근이어도 실제 경기는 오래됐을 수
있고, 반대로 오래 전에 수집한 최근 경기는 여전히 비교에 적합할 수 있다. 전체 저장 corpus를 계속 집계하면 오래된 patch의
경기가 현재 Peer Benchmark와 coverage를 지배할 수 있다.

`collectedAt`은 우리 시스템의 저장 시각이고 `rankCapturedAt`은 sampled player rank를 확인한 시각이다. 어느 값도 경기
성과가 기록된 시각을 대체하지 않는다. 물리 retention, patch-aware cohort, rank history는 아직 결정하지 않았다.

## 결정

Peer Benchmark aggregate와 coverage는 `gameStartTimestamp`로 계산한 query-time rolling validity window만 사용한다.

- 설정은 `benchmark.sample.max-age`, 환경 변수는 `BENCHMARK_SAMPLE_MAX_AGE`이며 기본값은 `30d`다.
- `Clock`에서 요청별 `asOf`를 한 번만 만들고 `fromInclusive = asOf - maxSampleAge`, `toExclusive = asOf`를 사용한다.
- 유효 범위는 `[fromInclusive, toExclusive)`다. 하한은 포함하고 상한과 미래 시각은 제외한다.
- `POSITION`, `CHAMPION_POSITION`, target self-exclusion aggregate, coverage 모두 PostgreSQL의 동일한
  `game_start_timestamp` predicate를 사용한다. 한 player comparison의 모든 cohort는 같은 window를 공유한다.
- repository는 독립적으로 현재 시각을 계산하거나 SQL `now()`를 사용하지 않는다.

이 기간은 UTC 기준의 정확한 duration이며 달력 날짜 경계가 아니다. sample row는 삭제·수정하지 않고, `expiresAt` column,
aggregate cache/table, collector 재수집, `collectedAt` 갱신도 추가하지 않는다.

`PlayerAnalysisInput`에는 LLM 설명과 cache identity에 필요한 `maxSampleAge` 정책만 넣는다. request별 query 시각과 window
boundary는 Backend query context이므로 넣지 않는다. 이 입력 의미 변경에 맞춰 completed-result cache version은 v3로 올린다.

## 결과

availability는 전체 저장기간의 count가 아니라 유효 window와 self-exclusion 뒤의 count/unique player count로 다시
평가한다. 유효 row가 없으면 `NO_DATA`다. coverage의 `GROUP BY`는 0건 cohort를 반환하지 않으므로, 알려진 cohort를 화면에
표시해야 하는 caller는 없는 row를 0건 `NO_DATA`로 해석한다.

사용자의 분석 표본은 최근 Ranked Solo 최대 20경기이며 peer의 30일 유효기간과 별도의 정책이다. 유효기간을 도입해도
patch-aware aggregation은 아니므로 여러 `gameVersion`이 섞일 수 있고, `rankCapturedAt`은 경기 당시 rank를 복원하지 않는다.

## 대안

### `collectedAt` 또는 `rankCapturedAt` 기준 필터

수집·rank 확인 시각은 경기 성과가 발생한 시각이 아니므로 과거 경기를 최근 표본으로 잘못 포함할 수 있다.

### 물리 삭제 또는 expiry column

저장 retention과 분석 적합성은 다른 문제다. 현재는 historical data를 보존하면서 query correctness를 먼저 검증한다.

### SQL `now()` 또는 repository별 현재 시각 계산

한 comparison 안의 cohort와 coverage query 사이에 미세한 시간 차이가 생기고 fixed Clock test가 어려워진다.

### patch별 cohort

더 강한 patch 정합성을 제공하지만 cohort sparsity, collection/coverage 정책을 새로 결정해야 한다. 실제 필요가 확인될 때
별도 ADR로 다룬다.
