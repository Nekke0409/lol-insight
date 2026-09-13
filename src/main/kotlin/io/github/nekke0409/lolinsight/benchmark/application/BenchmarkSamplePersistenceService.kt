package io.github.nekke0409.lolinsight.benchmark.application

import io.github.nekke0409.lolinsight.benchmark.domain.BenchmarkSample
import io.github.nekke0409.lolinsight.benchmark.persistence.BenchmarkSampleJpaRepository
import io.github.nekke0409.lolinsight.benchmark.persistence.toEntity
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class BenchmarkSamplePersistenceService(
    private val benchmarkSampleJpaRepository: BenchmarkSampleJpaRepository,
) {
    @Transactional
    fun saveIfAbsent(sample: BenchmarkSample): BenchmarkSampleSaveResult =
        if (benchmarkSampleJpaRepository.insertIfAbsent(sample.toEntity()) == 1) {
            BenchmarkSampleSaveResult.INSERTED
        } else {
            BenchmarkSampleSaveResult.ALREADY_EXISTS
        }
}

enum class BenchmarkSampleSaveResult {
    INSERTED,
    ALREADY_EXISTS,
}
