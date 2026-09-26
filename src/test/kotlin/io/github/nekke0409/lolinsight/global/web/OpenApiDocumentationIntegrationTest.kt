package io.github.nekke0409.lolinsight.global.web

import io.github.nekke0409.lolinsight.benchmark.persistence.NoDataSourceBenchmarkPersistenceTestConfiguration
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.client.RestClient
import org.springframework.web.context.WebApplicationContext
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@SpringBootTest(
    properties = [
        "riot.api.key=documentation-test-api-key",
        "spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
        "springdoc.api-docs.enabled=true",
        "springdoc.swagger-ui.enabled=true",
        "analysis.automation.enabled=false",
        "analysis.automation.bootstrap.enabled=false",
        "benchmark.replenishment.enabled=false",
        "benchmark.replenishment.run-once=false",
        "agent.enabled=false",
    ],
)
@Import(
    NoDataSourceBenchmarkPersistenceTestConfiguration::class,
    OpenApiDocumentationIntegrationTest.NoExternalCallsConfiguration::class,
)
class OpenApiDocumentationIntegrationTest {
    @Autowired
    private lateinit var webApplicationContext: WebApplicationContext

    @Autowired
    private lateinit var riotApiMockServer: MockRestServiceServer

    private lateinit var mockMvc: MockMvc

    @BeforeEach
    fun setUp() {
        riotApiMockServer.reset()
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build()
    }

    @AfterEach
    fun verifiesNoRiotRequestsWereMade() {
        riotApiMockServer.verify()
    }

    @Test
    fun `serves the OpenAPI document with only client API operations and matching contract metadata`() {
        val response =
            mockMvc
                .perform(get("/v3/api-docs"))
                .andExpect(status().isOk)
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andReturn()
                .response

        val document = JsonMapper.builder().build().readTree(response.contentAsString)
        val paths = document.path("paths")

        assertEquals("LOL Insight API", document.path("info").path("title").asText())
        assertEquals("v1", document.path("info").path("version").asText())
        assertEquals(
            "LOL Insight 클라이언트 API v1 문서입니다.",
            document.path("info").path("description").asText(),
        )

        assertOperation(paths, "/api/v1/players/{gameName}/{tagLine}", "get")
        assertOperation(paths, "/api/v1/players/{gameName}/{tagLine}/matches", "get")
        assertOperation(paths, "/api/v1/players/{gameName}/{tagLine}/stats", "get")
        assertOperation(paths, "/api/v1/players/{gameName}/{tagLine}/analysis", "post")
        assertOperation(paths, "/api/v1/players/{gameName}/{tagLine}/analysis-jobs", "post")
        assertOperation(paths, "/api/v1/players/{gameName}/{tagLine}/agent-questions", "post")
        assertOperation(paths, "/api/v1/matches/{matchId}", "get")
        assertOperation(paths, "/api/v1/analysis-jobs/{jobId}", "get")
        assertFalse(paths.has("/actuator/health"))
        assertFalse(paths.has("/error"))
        assertTrue(paths.propertyNames().all { it.startsWith("/api/v1/") })

        assertResponseSchema(paths.path("/api/v1/players/{gameName}/{tagLine}").path("get"), "200", "PlayerResponse")
        assertResponseSchema(paths.path("/api/v1/players/{gameName}/{tagLine}/matches").path("get"), "200", "RecentMatchesResponse")
        assertResponseSchema(paths.path("/api/v1/players/{gameName}/{tagLine}/stats").path("get"), "200", "PlayerMatchStatisticsResponse")
        assertResponseSchema(paths.path("/api/v1/players/{gameName}/{tagLine}/analysis").path("post"), "200", "PlayerAnalysisResponse")
        assertResponseSchema(paths.path("/api/v1/matches/{matchId}").path("get"), "200", "MatchResponse")
        assertResponseSchema(paths.path("/api/v1/analysis-jobs/{jobId}").path("get"), "200", "AnalysisJobView")
        val agentQuestionOperation = paths.path("/api/v1/players/{gameName}/{tagLine}/agent-questions").path("post")
        assertResponseSchema(agentQuestionOperation, "200", "AgentQuestionResponse")
        assertTrue(agentQuestionOperation.path("responses").has("400"))
        assertTrue(agentQuestionOperation.path("responses").has("404"))
        assertTrue(agentQuestionOperation.path("responses").has("503"))

        val recentMatchesOperation = paths.path("/api/v1/players/{gameName}/{tagLine}/matches").path("get")
        val tagLine = parameter(recentMatchesOperation, "tagLine")
        assertEquals("KR1", tagLine.path("example").asText())
        assertTrue(tagLine.path("description").asText().contains("# 없이"))
        assertParameterContract(recentMatchesOperation, "start", defaultValue = 0, minimum = 0, maximum = null)
        assertParameterContract(recentMatchesOperation, "count", defaultValue = 20, minimum = 1, maximum = 20)
        assertFalse(recentMatchesOperation.path("parameters").any { it.path("name").asText() in setOf("request", "httpRequest") })

        val analysisJobCreation = paths.path("/api/v1/players/{gameName}/{tagLine}/analysis-jobs").path("post")
        val acceptedResponse = analysisJobCreation.path("responses").path("202")
        assertEquals(
            "#/components/schemas/AnalysisJobCreated",
            responseSchema(acceptedResponse).path("\$ref").asText(),
        )
        assertTrue(acceptedResponse.path("headers").has("Location"))
        assertTrue(
            acceptedResponse
                .path("headers")
                .path("Location")
                .path("description")
                .asText()
                .contains("polling"),
        )
        assertParameterContract(analysisJobCreation, "start", defaultValue = 0, minimum = 0, maximum = null)
        assertParameterContract(analysisJobCreation, "count", defaultValue = 20, minimum = 1, maximum = 20)

        val analysisJobSchema =
            document
                .path("components")
                .path("schemas")
                .path("AnalysisJobCreated")
                .path("properties")
        assertTrue(analysisJobSchema.has("jobId"))
        assertTrue(analysisJobSchema.has("status"))
        assertTrue(analysisJobSchema.has("createdAt"))
        assertTrue(analysisJobCreation.path("responses").has("429"))
        assertTrue(analysisJobCreation.path("responses").has("503"))

        val agentRequestSchema = responseSchema(agentQuestionOperation.path("requestBody"))
        assertEquals("#/components/schemas/AgentQuestionRequest", agentRequestSchema.path("\$ref").asText())
        val agentRequestProperties =
            document
                .path("components")
                .path("schemas")
                .path("AgentQuestionRequest")
                .path("properties")
        assertTrue(agentRequestProperties.has("knowledgeScope"))
        val agentScopeProperties =
            document
                .path("components")
                .path("schemas")
                .path("AgentKnowledgeScopeRequest")
                .path("properties")
        assertTrue(agentScopeProperties.has("patchVersion"))
        assertTrue(agentScopeProperties.has("locale"))
        val agentResponseProperties =
            document
                .path("components")
                .path("schemas")
                .path("AgentQuestionResponse")
                .path("properties")
        assertEquals("array", agentResponseProperties.path("citations").path("type").asText())
    }

    @Test
    fun `serves Swagger UI redirect HTML and its OpenAPI loading configuration`() {
        mockMvc
            .perform(get("/swagger-ui.html"))
            .andExpect(status().is3xxRedirection)
            .andExpect(redirectedUrl("/swagger-ui/index.html"))

        mockMvc
            .perform(get("/swagger-ui/index.html"))
            .andExpect(status().isOk)
            .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))

        mockMvc
            .perform(get("/v3/api-docs/swagger-config"))
            .andExpect(status().isOk)
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
    }

    private fun assertOperation(
        paths: JsonNode,
        path: String,
        method: String,
    ) {
        assertTrue(paths.has(path), "OpenAPI path must include $path")
        assertTrue(paths.path(path).has(method), "OpenAPI path $path must include $method")
    }

    private fun parameter(
        operation: JsonNode,
        name: String,
    ): JsonNode =
        operation.path("parameters").firstOrNull { it.path("name").asText() == name }
            ?: error("OpenAPI operation must include parameter $name")

    private fun responseSchema(response: JsonNode): JsonNode =
        response
            .path("content")
            .properties()
            .single()
            .value
            .path("schema")

    private fun assertResponseSchema(
        operation: JsonNode,
        responseCode: String,
        schemaName: String,
    ) {
        assertEquals(
            "#/components/schemas/$schemaName",
            responseSchema(operation.path("responses").path(responseCode)).path("\$ref").asText(),
        )
    }

    private fun assertParameterContract(
        operation: JsonNode,
        name: String,
        defaultValue: Int,
        minimum: Int,
        maximum: Int?,
    ) {
        val schema = parameter(operation, name).path("schema")
        assertEquals(defaultValue, schema.path("default").asInt())
        assertEquals(minimum, schema.path("minimum").asInt())
        if (maximum == null) {
            assertFalse(schema.has("maximum"))
        } else {
            assertEquals(maximum, schema.path("maximum").asInt())
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    class NoExternalCallsConfiguration {
        @Bean
        fun documentationRiotApiMockTransport(): DocumentationRiotApiMockTransport = DocumentationRiotApiMockTransport()

        @Bean
        fun riotApiMockServer(transport: DocumentationRiotApiMockTransport): MockRestServiceServer = transport.server

        @Bean
        @Primary
        fun documentationRiotApiRestClient(transport: DocumentationRiotApiMockTransport): RestClient = transport.restClientBuilder.build()
    }

    class DocumentationRiotApiMockTransport {
        val restClientBuilder: RestClient.Builder = RestClient.builder()
        val server: MockRestServiceServer = MockRestServiceServer.bindTo(restClientBuilder).build()
    }
}
