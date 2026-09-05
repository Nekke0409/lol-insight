package io.github.nekke0409.lolinsight.global.riot

import java.net.URI

enum class RiotApiRouting {
    PLATFORM,
    REGIONAL,
    ;

    fun baseUrl(properties: RiotApiProperties): URI =
        when (this) {
            PLATFORM -> properties.platformBaseUrl
            REGIONAL -> properties.regionalBaseUrl
        }
}
