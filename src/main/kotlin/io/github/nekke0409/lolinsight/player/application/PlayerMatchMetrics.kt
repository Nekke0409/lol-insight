package io.github.nekke0409.lolinsight.player.application

import io.github.nekke0409.lolinsight.match.domain.MatchParticipant

internal fun MatchParticipant.kda(): Double = (kills.toDouble() + assists) / maxOf(1, deaths)
