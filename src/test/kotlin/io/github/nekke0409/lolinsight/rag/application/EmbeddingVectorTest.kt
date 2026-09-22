package io.github.nekke0409.lolinsight.rag.application

import org.junit.jupiter.api.Test
import kotlin.test.assertFailsWith

class EmbeddingVectorTest {
    @Test
    fun `rejects empty nonfinite and zero vectors before persistence or cosine search`() {
        assertFailsWith<IllegalArgumentException> { EmbeddingVector(emptyList()) }
        assertFailsWith<IllegalArgumentException> { EmbeddingVector(listOf(Float.NaN, 1.0f)) }
        assertFailsWith<IllegalArgumentException> { EmbeddingVector(listOf(Float.POSITIVE_INFINITY, 1.0f)) }
        assertFailsWith<IllegalArgumentException> { EmbeddingVector(listOf(0.0f, 0.0f)) }
    }
}
