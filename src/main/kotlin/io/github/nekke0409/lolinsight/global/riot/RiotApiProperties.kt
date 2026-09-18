package io.github.nekke0409.lolinsight.global.riot

import jakarta.validation.constraints.NotBlank
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated
import java.net.URI
import java.time.Duration

@Validated
@ConfigurationProperties("riot.api")
data class RiotApiProperties(
    @field:NotBlank
    val key: String,
    val platformBaseUrl: URI = URI.create("https://kr.api.riotgames.com"),
    val regionalBaseUrl: URI = URI.create("https://asia.api.riotgames.com"),
    val connectTimeout: Duration = Duration.ofSeconds(2),
    val readTimeout: Duration = Duration.ofSeconds(5),
    val cooldownFallback: Duration = Duration.ofSeconds(60),
) {
    init {
        require(!cooldownFallback.isNegative && !cooldownFallback.isZero) {
            "cooldownFallback must be positive."
        }
    }
}
