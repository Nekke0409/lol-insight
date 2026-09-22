package io.github.nekke0409.lolinsight.rag.infrastructure.openai;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public final class OpenAiPatchNoteStatementOutput {
    @JsonProperty("text")
    public String text;

    @JsonProperty("evidenceIds")
    public List<String> evidenceIds;

    public OpenAiPatchNoteStatementOutput() {}
}
