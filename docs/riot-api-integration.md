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
