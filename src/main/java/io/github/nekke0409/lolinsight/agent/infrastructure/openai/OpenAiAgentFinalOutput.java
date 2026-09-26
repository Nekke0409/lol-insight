package io.github.nekke0409.lolinsight.agent.infrastructure.openai;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public final class OpenAiAgentFinalOutput {
    @JsonProperty("statements")
    public List<OpenAiAgentStatementOutput> statements;

    @JsonProperty("limitations")
    public List<String> limitations;

    public OpenAiAgentFinalOutput() {}
}
