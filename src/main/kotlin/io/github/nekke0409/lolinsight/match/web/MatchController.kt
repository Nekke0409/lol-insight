package io.github.nekke0409.lolinsight.match.web

import io.github.nekke0409.lolinsight.match.application.MatchResponse
import io.github.nekke0409.lolinsight.match.application.MatchService
import jakarta.validation.constraints.NotBlank
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@Validated
@RestController
@RequestMapping("/api/v1/matches")
class MatchController(
    private val matchService: MatchService,
) {
    @GetMapping("/{matchId}")
    fun findByMatchId(
        @PathVariable @NotBlank matchId: String,
    ): MatchResponse = matchService.findByMatchId(matchId)
}
