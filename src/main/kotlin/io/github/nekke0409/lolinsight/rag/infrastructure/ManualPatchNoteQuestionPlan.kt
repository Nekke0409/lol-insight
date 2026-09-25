package io.github.nekke0409.lolinsight.rag.infrastructure

import java.io.ByteArrayInputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

/** Read once at manual-evaluation startup; the normal RAG path never constructs this type. */
internal class ManualPatchNoteQuestionPlan private constructor(
    val sha256: String,
    private val questions: Map<String, ManualPatchNoteQuestion>,
) {
    fun question(questionId: String): ManualPatchNoteQuestion? = questions[questionId]

    companion object {
        fun load(settings: ManualPatchNoteAnswerEvaluationSettings): ManualPatchNoteQuestionPlan {
            val bytes =
                try {
                    Files.readAllBytes(settings.planPath)
                } catch (exception: Exception) {
                    throw ManualRagSmokePlanConfigurationException("manual plan cannot be read", exception)
                }
            if (bytes.startsWithUtf8Bom()) {
                throw ManualRagSmokePlanConfigurationException("manual plan must be UTF-8 without BOM")
            }
            val actualHash = sha256(bytes)
            if (actualHash != settings.expectedPlanSha256) {
                throw ManualRagSmokePlanConfigurationException("manual plan SHA-256 does not match the approved value")
            }
            val properties = Properties()
            try {
                InputStreamReader(ByteArrayInputStream(bytes), StandardCharsets.UTF_8).use(properties::load)
            } catch (exception: Exception) {
                throw ManualRagSmokePlanConfigurationException("manual plan is not valid UTF-8 properties", exception)
            }
            val count = properties.requiredInt("question.count")
            if (count != 6) throw ManualRagSmokePlanConfigurationException("manual plan must contain exactly six questions")
            val questions =
                (1..count).associate { index ->
                    val prefix = "question.$index."
                    val id = properties.required(prefix + "id")
                    if (!QUESTION_ID.matches(id)) throw ManualRagSmokePlanConfigurationException("manual plan question id is invalid")
                    val locale = properties.getProperty(prefix + "locale")?.takeIf(String::isNotBlank) ?: settings.locale
                    val question =
                        ManualPatchNoteQuestion(
                            id = id,
                            kind = properties.requiredKind(prefix + "kind"),
                            patchVersion = properties.required(prefix + "patchVersion"),
                            locale = locale,
                            question = properties.required(prefix + "query"),
                        )
                    id to question
                }
            if (questions.size != count) throw ManualRagSmokePlanConfigurationException("manual plan question ids must be unique")
            if (questions.values.count { it.kind == ManualPatchNoteQuestionKind.GROUNDED } != 4 ||
                questions.values.count { it.kind == ManualPatchNoteQuestionKind.INSUFFICIENT } != 1 ||
                questions.values.count { it.kind == ManualPatchNoteQuestionKind.UNINDEXED } != 1
            ) {
                throw ManualRagSmokePlanConfigurationException("manual plan question kinds do not match the approved evaluation")
            }
            return ManualPatchNoteQuestionPlan(actualHash, questions)
        }

        private val QUESTION_ID = Regex("[a-z0-9-]{1,80}")
    }
}

internal data class ManualPatchNoteQuestion(
    val id: String,
    val kind: ManualPatchNoteQuestionKind,
    val patchVersion: String,
    val locale: String,
    val question: String,
) {
    val fingerprint = ManualPatchNoteInputFingerprint.from(question)
}

internal enum class ManualPatchNoteQuestionKind {
    GROUNDED,
    INSUFFICIENT,
    UNINDEXED,
}

internal data class ManualPatchNoteAnswerEvaluationSettings(
    val planPath: Path,
    val expectedPlanSha256: String,
    val locale: String,
    val evaluationRunId: String,
    val captureDirectory: Path,
) {
    companion object {
        fun from(properties: RagAnswerProperties): ManualPatchNoteAnswerEvaluationSettings {
            val planPath =
                properties.manualPlanPath?.takeIf(String::isNotBlank)?.let(Path::of)
                    ?: throw ManualRagSmokePlanConfigurationException("rag.answer.manual-plan-path must be configured")
            val hash =
                properties.manualPlanSha256?.lowercase()?.takeIf(SHA256::matches)
                    ?: throw ManualRagSmokePlanConfigurationException("rag.answer.manual-plan-sha256 must be a SHA-256 hex value")
            val locale =
                properties.manualPlanLocale?.takeIf(String::isNotBlank)
                    ?: throw ManualRagSmokePlanConfigurationException("rag.answer.manual-plan-locale must be configured")
            val runId =
                properties.manualEvaluationRunId?.takeIf(RUN_ID::matches)
                    ?: throw ManualRagSmokePlanConfigurationException("rag.answer.manual-evaluation-run-id must be configured")
            return ManualPatchNoteAnswerEvaluationSettings(planPath, hash, locale, runId, properties.manualCaptureDirectory.resolve(runId))
        }

        private val SHA256 = Regex("[a-f0-9]{64}")
        private val RUN_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,79}")
    }
}

internal class ManualRagSmokePlanConfigurationException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

private fun Properties.required(key: String): String =
    getProperty(key)?.takeIf(String::isNotBlank) ?: throw ManualRagSmokePlanConfigurationException("manual plan property $key is required")

private fun Properties.requiredInt(key: String): Int =
    required(key).toIntOrNull()?.takeIf { it > 0 }
        ?: throw ManualRagSmokePlanConfigurationException("manual plan property $key must be a positive integer")

private fun Properties.requiredKind(key: String): ManualPatchNoteQuestionKind =
    try {
        ManualPatchNoteQuestionKind.valueOf(required(key))
    } catch (exception: IllegalArgumentException) {
        throw ManualRagSmokePlanConfigurationException("manual plan property $key is invalid", exception)
    }

private fun ByteArray.startsWithUtf8Bom(): Boolean =
    size >= 3 && this[0] == 0xef.toByte() && this[1] == 0xbb.toByte() && this[2] == 0xbf.toByte()
