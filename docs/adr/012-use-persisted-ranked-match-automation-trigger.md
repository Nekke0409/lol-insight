# ADR-012: Persisted Ranked Solo Match Automation Trigger 사용

상태: Accepted

## 배경

기존 `AnalysisJob`은 검증된 rolling player analysis와 bounded executor, completed-result cache, provider failure lifecycle을
제공한다. 새 Ranked Solo 경기를 감지해 이를 자동 시작하려면 application restart 뒤에도 유지되는 tracking state와,
중복 poll에도 같은 match를 한 번만 trigger하는 경계가 필요하다. HTTP rate limit 및 in-flight dedupe는 client IP를 전제로
하므로 scheduler에 적용할 수 없다.

## 결정

PostgreSQL `tracked_player_automation`에 PUUID와 cursor를 저장한다. Riot ID는 변경 가능하고 현재 Account lookup의 입력일
뿐이므로 persistence identity로 저장하지 않는다. player별 routing은 current adapter가 요구하지 않아 추가하지 않는다.

등록 시 `queue=420` recent Match ID의 head를 baseline으로 저장하고 과거 경기를 trigger하지 않는다. enabled automation은
Spring `@Scheduled` adapter가 설정된 interval마다 bounded batch로 순차 polling한다. 최근 목록의 head가 cursor와 다르면
신규 match가 하나 이상 존재한 것으로 판단하되, poll당 rolling `AnalysisJob` 하나만 생성한다.

`automation_execution`의 `(automation_id, detected_match_id)` unique constraint를 automation idempotency boundary로
사용한다. execution claim 뒤 기존 `AnalysisJobService`를 직접 호출하고 job이 생성된 뒤에만 conditional cursor update를
수행한다. HTTP self-call, 가상 client IP, HTTP in-flight registry 재사용은 하지 않는다. executor rejection이나 job creation
실패에는 cursor를 전진시키지 않는다.

## 결과

자동화는 기존 statistics, benchmark, comparison, `PlayerAnalysisService`, result cache와 worker를 복제하지 않는다.
Riot/OpenAI 비용은 enabled flag 기본 false, poll batch/interval, 기존 executor backpressure 및 trigger idempotency로 제어한다.

이 결정은 single-instance MVP다. 실행 중 process crash, multi-instance scheduler, persistent dispatch queue, distributed
claim/lease, automation quota, public subscription ownership, notification은 해결하지 않는다. 해당 운영 요구가 확인되면
이 trigger/application boundary를 유지한 채 별도 ADR로 확장한다.
