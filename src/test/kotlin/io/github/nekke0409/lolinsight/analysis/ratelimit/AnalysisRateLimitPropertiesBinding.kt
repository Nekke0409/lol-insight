package io.github.nekke0409.lolinsight.analysis.ratelimit

import org.springframework.boot.context.properties.bind.Bindable
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.core.env.Environment

internal fun bindAnalysisRateLimitProperties(environment: Environment): AnalysisRateLimitProperties =
    Binder
        .get(environment)
        .bind("analysis.rate-limit", Bindable.of(AnalysisRateLimitProperties::class.java))
        .orElseGet(::AnalysisRateLimitProperties)
