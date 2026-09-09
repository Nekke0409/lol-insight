package io.github.nekke0409.lolinsight.player.web

import io.github.nekke0409.lolinsight.player.application.PlayerMatchHistoryService
import io.github.nekke0409.lolinsight.player.application.PlayerResponse
import io.github.nekke0409.lolinsight.player.application.PlayerService
import io.github.nekke0409.lolinsight.player.application.RecentMatchesResponse
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/players")
class PlayerController(
    private val playerService: PlayerService,
    private val playerMatchHistoryService: PlayerMatchHistoryService,
) {
    @GetMapping("/{gameName}/{tagLine}")
    fun findByRiotId(
        @PathVariable @NotBlank gameName: String,
        @PathVariable @NotBlank tagLine: String,
    ): PlayerResponse = playerService.findByRiotId(gameName, tagLine)

    @GetMapping("/{gameName}/{tagLine}/matches")
    fun findRecentMatches(
        @PathVariable @NotBlank gameName: String,
        @PathVariable @NotBlank tagLine: String,
        @RequestParam(defaultValue = "0") @Min(0) start: Int,
        @RequestParam(defaultValue = "20") @Min(1) @Max(20) count: Int,
    ): RecentMatchesResponse = playerMatchHistoryService.findRecentMatches(gameName, tagLine, start, count)
}
