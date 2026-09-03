package io.github.nekke0409.lolinsight.global.riot

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.web.client.RestClient

@Configuration
@EnableConfigurationProperties(RiotApiProperties::class)
class RiotApiConfiguration {

    @Bean
    fun riotApiRestClient(
        properties: RiotApiProperties,
    ): RestClient = RestClient.builder()
        .requestFactory(
            SimpleClientHttpRequestFactory().apply {
                setConnectTimeout(properties.connectTimeout)
                setReadTimeout(properties.readTimeout)
            },
        )
        .defaultHeader(RIOT_TOKEN_HEADER, properties.key)
        .build()

    private companion object {
        const val RIOT_TOKEN_HEADER = "X-Riot-Token"
    }
}
