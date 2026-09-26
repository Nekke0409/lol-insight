package io.github.nekke0409.lolinsight.agent.infrastructure.openai;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public final class OpenAiAgentStatementOutput {
    @JsonProperty("text")
    public String text;

    @JsonProperty("basis")
    public String basis;

    @JsonProperty("evidenceIds")
    public List<String> evidenceIds;

    @JsonProperty("toolName")
    public String toolName;

    public OpenAiAgentStatementOutput() {}
}
