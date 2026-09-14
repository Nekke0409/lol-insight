package io.github.nekke0409.lolinsight.analysis.infrastructure.openai;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public final class OpenAiPlayerAnalysisOutput {
    @JsonProperty("summary")
    public String summary;

    @JsonProperty("observations")
    public List<OpenAiAnalysisInsightOutput> observations;

    @JsonProperty("strengths")
    public List<OpenAiAnalysisInsightOutput> strengths;

    @JsonProperty("focusAreas")
    public List<OpenAiAnalysisInsightOutput> focusAreas;

    @JsonProperty("caveats")
    public List<String> caveats;

    public OpenAiPlayerAnalysisOutput() {}
}
