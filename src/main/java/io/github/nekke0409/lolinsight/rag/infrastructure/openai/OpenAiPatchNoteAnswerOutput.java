package io.github.nekke0409.lolinsight.rag.infrastructure.openai;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public final class OpenAiPatchNoteAnswerOutput {
    @JsonProperty("status")
    public String status;

    @JsonProperty("statements")
    public List<OpenAiPatchNoteStatementOutput> statements;

    @JsonProperty("limitations")
    public List<String> limitations;

    public OpenAiPatchNoteAnswerOutput() {}
}
