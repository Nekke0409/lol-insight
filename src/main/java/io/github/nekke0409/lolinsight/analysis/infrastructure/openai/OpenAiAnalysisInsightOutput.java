package io.github.nekke0409.lolinsight.analysis.infrastructure.openai;

import com.fasterxml.jackson.annotation.JsonProperty;

public final class OpenAiAnalysisInsightOutput {
    @JsonProperty("title")
    public String title;

    @JsonProperty("explanation")
    public String explanation;

    @JsonProperty("evidence")
    public String evidence;

    public OpenAiAnalysisInsightOutput() {}
}
