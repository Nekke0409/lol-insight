package io.github.nekke0409.lolinsight.benchmark.persistence

import io.github.nekke0409.lolinsight.benchmark.domain.BenchmarkSample

internal fun BenchmarkSample.toEntity(): BenchmarkSampleEntity =
    BenchmarkSampleEntity(
        id = id,
        matchId = matchId,
        puuid = puuid,
        region = region,
        queueId = queueId,
        tier = tier,
        division = division,
        rankCapturedAt = rankCapturedAt,
        championId = championId,
        position = position,
        gameVersion = gameVersion,
        gameStartTimestamp = gameStartTimestamp,
        kills = kills,
        deaths = deaths,
        assists = assists,
        kda = kda,
        csPerMinute = csPerMinute,
        goldPerMinute = goldPerMinute,
        damagePerMinute = damagePerMinute,
        visionPerMinute = visionPerMinute,
        killParticipation = killParticipation,
        damageShare = damageShare,
        collectedAt = collectedAt,
    )

internal fun BenchmarkSampleEntity.toDomain(): BenchmarkSample =
    BenchmarkSample(
        id = requireNotNull(id),
        matchId = matchId,
        puuid = puuid,
        region = region,
        queueId = queueId,
        tier = tier,
        division = division,
        rankCapturedAt = rankCapturedAt,
        championId = championId,
        position = position,
        gameVersion = gameVersion,
        gameStartTimestamp = gameStartTimestamp,
        kills = kills,
        deaths = deaths,
        assists = assists,
        kda = kda,
        csPerMinute = csPerMinute,
        goldPerMinute = goldPerMinute,
        damagePerMinute = damagePerMinute,
        visionPerMinute = visionPerMinute,
        killParticipation = killParticipation,
        damageShare = damageShare,
        collectedAt = collectedAt,
    )
