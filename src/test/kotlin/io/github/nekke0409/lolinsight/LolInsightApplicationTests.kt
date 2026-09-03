package io.github.nekke0409.lolinsight

import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest

@SpringBootTest(properties = ["riot.api.key=test-api-key"])
class LolInsightApplicationTests {

	@Test
	fun contextLoads() {
	}

}
