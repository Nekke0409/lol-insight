package io.github.nekke0409.lolinsight.analysis.infrastructure.cache

import io.github.nekke0409.lolinsight.analysis.application.PlayerAnalysisInput
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.security.MessageDigest

@Component
class PlayerAnalysisInputFingerprint(
    private val objectMapper: ObjectMapper,
) {
    fun create(input: PlayerAnalysisInput): String {
        val canonicalJson = objectMapper.writeValueAsBytes(input)
        val digest = MessageDigest.getInstance("SHA-256").digest(canonicalJson)
        return digest.joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }
}
