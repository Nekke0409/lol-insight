package io.github.nekke0409.lolinsight

import io.github.nekke0409.lolinsight.benchmark.persistence.NoDataSourceBenchmarkPersistenceTestConfiguration
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import

@SpringBootTest(
    properties = [
        "riot.api.key=test-api-key",
        "spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
    ],
)
@Import(NoDataSourceBenchmarkPersistenceTestConfiguration::class)
class LolInsightApplicationTests {
    @Test
    fun contextLoads() {
    }
}
