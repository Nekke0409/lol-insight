package io.github.nekke0409.lolinsight.rag.infrastructure

import io.github.nekke0409.lolinsight.rag.application.NoOpPatchNoteAnswerEvidenceObserver
import io.github.nekke0409.lolinsight.rag.application.NoOpPatchNoteQuestionExecutionBudget
import io.github.nekke0409.lolinsight.rag.application.PatchNoteAnswerEvidenceObserver
import io.github.nekke0409.lolinsight.rag.application.PatchNoteQuestionExecutionBudget
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RagProperties::class)
class RagPropertiesConfiguration

@Configuration(proxyBeanMethods = false)
class PatchNoteAnswerEvidenceObserverConfiguration {
    @Bean
    @ConditionalOnProperty(prefix = "rag.answer", name = ["manual-capture-enabled"], havingValue = "false", matchIfMissing = true)
    fun noOpPatchNoteAnswerEvidenceObserver(): PatchNoteAnswerEvidenceObserver = NoOpPatchNoteAnswerEvidenceObserver

    @Bean
    @ConditionalOnProperty(prefix = "rag.answer", name = ["manual-capture-enabled"], havingValue = "false", matchIfMissing = true)
    fun noOpPatchNoteQuestionExecutionBudget(): PatchNoteQuestionExecutionBudget = NoOpPatchNoteQuestionExecutionBudget
}
