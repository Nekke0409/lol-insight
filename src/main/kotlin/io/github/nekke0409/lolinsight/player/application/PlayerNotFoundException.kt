package io.github.nekke0409.lolinsight.player.application

class PlayerNotFoundException(
    cause: Throwable? = null,
) : RuntimeException("Player not found.", cause)
