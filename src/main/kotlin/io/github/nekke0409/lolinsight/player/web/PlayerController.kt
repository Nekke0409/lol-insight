package io.github.nekke0409.lolinsight.player.web

import io.github.nekke0409.lolinsight.player.application.PlayerResponse
import io.github.nekke0409.lolinsight.player.application.PlayerService
import jakarta.validation.constraints.NotBlank
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@Validated
@RestController
@RequestMapping("/api/v1/players")
class PlayerController(
    private val playerService: PlayerService,
) {
    @GetMapping("/{gameName}/{tagLine}")
    fun findByRiotId(
        @PathVariable @NotBlank gameName: String,
        @PathVariable @NotBlank tagLine: String,
    ): PlayerResponse = playerService.findByRiotId(gameName, tagLine)
}
