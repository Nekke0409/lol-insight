package io.github.nekke0409.lolinsight.analysis.job.persistence

import com.fasterxml.jackson.databind.JsonNode
import io.github.nekke0409.lolinsight.analysis.job.application.AnalysisJobFailureCode
import io.github.nekke0409.lolinsight.analysis.job.application.AnalysisJobStatus
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "analysis_job")
class AnalysisJobEntity(
    @Id
    var id: UUID = UUID.randomUUID(),
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: AnalysisJobStatus = AnalysisJobStatus.PENDING,
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "result", columnDefinition = "jsonb")
    var result: JsonNode? = null,
    @Enumerated(EnumType.STRING)
    @Column(name = "failure_code", length = 50)
    var failureCode: AnalysisJobFailureCode? = null,
    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.EPOCH,
    @Column(name = "started_at")
    var startedAt: Instant? = null,
    @Column(name = "completed_at")
    var completedAt: Instant? = null,
)
