package io.github.nekke0409.lolinsight.global.web

import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration(proxyBeanMethods = false)
class OpenApiConfiguration {
    @Bean
    fun lolInsightOpenApi(): OpenAPI =
        OpenAPI().info(
            Info()
                .title("LOL Insight API")
                .description("LOL Insight 클라이언트 API v1 문서입니다.")
                .version("v1"),
        )
}
