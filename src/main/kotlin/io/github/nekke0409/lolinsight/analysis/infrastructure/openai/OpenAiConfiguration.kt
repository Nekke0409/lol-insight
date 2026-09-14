package io.github.nekke0409.lolinsight.analysis.infrastructure.openai

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration

@Configuration
@EnableConfigurationProperties(OpenAiProperties::class)
class OpenAiConfiguration
