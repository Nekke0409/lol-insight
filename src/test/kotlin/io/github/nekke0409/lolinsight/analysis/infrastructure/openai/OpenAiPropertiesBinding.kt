package io.github.nekke0409.lolinsight.analysis.infrastructure.openai

import org.springframework.boot.context.properties.bind.Bindable
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.core.env.Environment

internal fun bindOpenAiProperties(environment: Environment): OpenAiProperties =
    Binder
        .get(environment)
        .bind("openai", Bindable.of(OpenAiProperties::class.java))
        .orElseGet(::OpenAiProperties)
