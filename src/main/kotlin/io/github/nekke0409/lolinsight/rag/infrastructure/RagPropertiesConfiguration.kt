package io.github.nekke0409.lolinsight.rag.infrastructure

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RagProperties::class)
class RagPropertiesConfiguration
