package io.github.nekke0409.lolinsight.benchmark.persistence

import io.github.nekke0409.lolinsight.benchmark.domain.BenchmarkQueryWindow
import org.junit.jupiter.api.Test
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.AbstractDataSource
import java.sql.Connection
import java.sql.SQLTransientConnectionException
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BenchmarkSampleValidityRepositoryTest {
    private val dataSource = FailingDataSource()
    private val repository = BenchmarkSampleValidityRepository(NamedParameterJdbcTemplate(dataSource))

    @Test
    fun `does not query the database for empty candidates`() {
        val result = repository.findValidSampleCounts(emptySet(), "KR", 420, "GOLD", "I", WINDOW)

        assertEquals(emptyMap(), result)
        assertEquals(0, dataSource.connectionAttempts)
    }

    @Test
    fun `propagates database failures instead of treating every candidate as zero`() {
        assertFailsWith<DataAccessException> {
            repository.findValidSampleCounts(setOf("player-a"), "KR", 420, "GOLD", "I", WINDOW)
        }
        assertEquals(1, dataSource.connectionAttempts)
    }

    private class FailingDataSource : AbstractDataSource() {
        var connectionAttempts: Int = 0

        override fun getConnection(): Connection {
            connectionAttempts += 1
            throw SQLTransientConnectionException("database unavailable")
        }

        override fun getConnection(
            username: String,
            password: String,
        ): Connection = getConnection()
    }

    private companion object {
        val WINDOW = BenchmarkQueryWindow(Instant.parse("2026-09-01T00:00:00Z"), Instant.parse("2026-10-01T00:00:00Z"))
    }
}
