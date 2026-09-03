package io.github.nekke0409.lolinsight

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class LolInsightApplication

fun main(args: Array<String>) {
	runApplication<LolInsightApplication>(*args)
}
