package io.github.nekke0409.lolinsight.automation.scheduling

import org.springframework.boot.context.properties.bind.Bindable
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.core.env.Environment

internal fun bindAnalysisAutomationProperties(environment: Environment): AnalysisAutomationProperties =
    Binder
        .get(environment)
        .bind("analysis.automation", Bindable.of(AnalysisAutomationProperties::class.java))
        .orElseGet(::AnalysisAutomationProperties)
