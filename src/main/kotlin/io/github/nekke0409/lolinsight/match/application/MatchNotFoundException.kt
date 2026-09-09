package io.github.nekke0409.lolinsight.match.application

class MatchNotFoundException(
    cause: Throwable? = null,
) : RuntimeException("Match not found.", cause)
