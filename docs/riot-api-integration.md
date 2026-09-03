ACCOUNT-V1
GET /riot/account/v1/accounts/by-riot-id/{gameName}/{tagLine}
Routing: regional
KR: asia.api.riotgames.com
Purpose: Riot ID → PUUID

SUMMONER-V4
GET /lol/summoner/v4/summoners/by-puuid/{encryptedPUUID}
Routing: platform
KR: kr.api.riotgames.com
Purpose: profile / level

LEAGUE-V4
GET /lol/league/v4/entries/by-puuid/{encryptedPUUID}
Routing: platform
KR: kr.api.riotgames.com
Purpose: ranked stats

MATCH-V5
GET /lol/match/v5/matches/by-puuid/{puuid}/ids
Routing: regional
KR: asia.api.riotgames.com
Purpose: match IDs

MATCH-V5
GET /lol/match/v5/matches/{matchId}
Routing: regional
KR: asia.api.riotgames.com
Purpose: match detail