package io.github.nekke0409.lolinsight.global.web

import io.github.nekke0409.lolinsight.benchmark.persistence.NoDataSourceBenchmarkPersistenceTestConfiguration
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext

@SpringBootTest(
    properties = [
        "riot.api.key=documentation-test-api-key",
        "spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
        "springdoc.api-docs.enabled=false",
        "springdoc.swagger-ui.enabled=false",
        "analysis.automation.enabled=false",
        "analysis.automation.bootstrap.enabled=false",
        "benchmark.replenishment.enabled=false",
        "benchmark.replenishment.run-once=false",
        "agent.enabled=false",
    ],
)
@Import(NoDataSourceBenchmarkPersistenceTestConfiguration::class)
class OpenApiDocumentationDisabledIntegrationTest {
    @Autowired
    private lateinit var webApplicationContext: WebApplicationContext

    private lateinit var mockMvc: MockMvc

    @BeforeEach
    fun setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build()
    }

    @Test
    fun `does not expose OpenAPI or Swagger UI when documentation is disabled`() {
        mockMvc.perform(get("/v3/api-docs")).andExpect(status().isNotFound)
        mockMvc.perform(get("/v3/api-docs/swagger-config")).andExpect(status().isNotFound)
        mockMvc.perform(get("/swagger-ui.html")).andExpect(status().isNotFound)
        mockMvc.perform(get("/swagger-ui/index.html")).andExpect(status().isNotFound)
    }
}
