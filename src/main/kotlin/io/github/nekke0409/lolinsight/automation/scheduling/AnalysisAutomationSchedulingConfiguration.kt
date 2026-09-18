package io.github.nekke0409.lolinsight.automation.scheduling

import io.github.nekke0409.lolinsight.automation.application.NewRankedMatchAnalysisPollingService
import io.github.nekke0409.lolinsight.automation.application.TrackedPlayerAutomationRegistrationService
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AnalysisAutomationProperties::class, AnalysisAutomationBootstrapProperties::class)
class AnalysisAutomationConfiguration

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(prefix = "analysis.automation", name = ["enabled"], havingValue = "true")
class AnalysisAutomationSchedulingConfiguration

@Component
@ConditionalOnProperty(prefix = "analysis.automation", name = ["enabled"], havingValue = "true")
class NewRankedMatchAnalysisScheduler(
    private val pollingService: NewRankedMatchAnalysisPollingService,
) {
    @Scheduled(fixedDelayString = "\${analysis.automation.poll-interval}")
    fun pollDueAutomations() {
        pollingService.pollDue()
    }
}

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "analysis.automation.bootstrap", name = ["enabled"], havingValue = "true")
class AnalysisAutomationBootstrapConfiguration {
    @Bean
    fun automationBootstrapRunner(
        properties: AnalysisAutomationBootstrapProperties,
        registrationService: TrackedPlayerAutomationRegistrationService,
    ): ApplicationRunner =
        ApplicationRunner {
            properties.players
                .filter(String::isNotBlank)
                .map(::parseRiotId)
                .forEach { (gameName, tagLine) -> registrationService.register(gameName, tagLine) }
        }

    private fun parseRiotId(value: String): Pair<String, String> {
        val separator = value.lastIndexOf('#')
        require(separator in 1 until value.lastIndex) {
            "ANALYSIS_AUTOMATION_BOOTSTRAP_PLAYERS entries must be gameName#tagLine."
        }
        return value.substring(0, separator) to value.substring(separator + 1)
    }
}
