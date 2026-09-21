# 수동 수집용 outbound pacing v0.1

`RIOT_OUTBOUND_PACING_ENABLED=false`가 기본값이다. 활성화할 때만 같은 JVM의 모든 Riot HTTP 요청 시작을 하나의 보수적 간격으로 조절한다.

```text
RIOT_OUTBOUND_PACING_ENABLED=true
RIOT_OUTBOUND_MIN_INTERVAL=2s
RIOT_OUTBOUND_MAX_WAIT=10s
```

이는 Riot quota의 정확한 모델이나 분산 rate limiter가 아니다. 다른 프로세스, 다른 도구, 같은 key의 외부 사용과는 공유하지 않는다. cache hit는 HTTP 실행 경계에 도달하지 않으므로 pacing 대기나 HTTP 시도 카운트를 만들지 않는다. pacing 대기 시간은 connect/read timeout과 별개다.

용어를 구분한다.

- 후보 선택은 어떤 benchmark player를 수집 예산에 쓸지 결정한다.
- pacing은 첫 HTTP 요청을 보내기 전의 간격을 조절한다.
- cooldown은 upstream 429 이후의 요청을 차단한다.
- retry는 실패한 요청을 다시 보내는 동작이며, 이 구현은 자동 retry를 하지 않는다.

ACCOUNT-V1
GET /riot/account/v1/accounts/by-riot-id/{gameName}/{tagLine}
라우팅: 지역
KR: asia.api.riotgames.com
용도: Riot ID → PUUID

SUMMONER-V4
GET /lol/summoner/v4/summoners/by-puuid/{encryptedPUUID}
라우팅: 플랫폼
KR: kr.api.riotgames.com
용도: 프로필 / 레벨

LEAGUE-V4
GET /lol/league/v4/entries/{queue}/{tier}/{division}?page={page}
라우팅: 플랫폼
KR: kr.api.riotgames.com
용도: 랭크 플레이어 탐색(League entry PUUID)

LEAGUE-V4
GET /lol/league/v4/entries/by-puuid/{encryptedPUUID}
라우팅: 플랫폼
KR: kr.api.riotgames.com
용도: 랭크 통계

MATCH-V5
GET /lol/match/v5/matches/by-puuid/{puuid}/ids
라우팅: 지역
KR: asia.api.riotgames.com
용도: 경기 ID 목록

MATCH-V5
GET /lol/match/v5/matches/{matchId}
라우팅: 지역
KR: asia.api.riotgames.com
용도: 경기 상세
