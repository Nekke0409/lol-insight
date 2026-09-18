package io.github.nekke0409.lolinsight.analysis.job.application

import io.github.nekke0409.lolinsight.analysis.ratelimit.AnalysisRateLimitKey
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Same-process identity for an in-flight async analysis request.
 *
 * The digest deliberately keeps the client identity and Riot ID values out of cache keys, logs, and diagnostics.
 */
class AnalysisJobDedupeKey private constructor(
    private val digest: String,
) {
    override fun equals(other: Any?): Boolean = other is AnalysisJobDedupeKey && digest == other.digest

    override fun hashCode(): Int = digest.hashCode()

    companion object {
        private const val ANALYSIS_CONTRACT_VERSION = "analysis-v0.2"

        fun of(
            clientIdentity: AnalysisRateLimitKey,
            gameName: String,
            tagLine: String,
            start: Int,
            count: Int,
        ): AnalysisJobDedupeKey {
            val messageDigest = MessageDigest.getInstance("SHA-256")
            listOf(
                ANALYSIS_CONTRACT_VERSION,
                clientIdentity.value,
                gameName,
                tagLine,
                start.toString(),
                count.toString(),
            ).forEach { component ->
                val bytes = component.toByteArray(StandardCharsets.UTF_8)
                messageDigest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
                messageDigest.update(bytes)
            }

            return AnalysisJobDedupeKey(messageDigest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) })
        }
    }
}
