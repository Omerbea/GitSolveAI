package com.gitsolve.agent.execution;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Structured response from the ExecutionAiService.
 * Represents a complete multi-file fix ready to be applied to the repository.
 */
public record ExecutionFixResponse(
        @JsonProperty("files")         List<FileChange> files,
        @JsonProperty("commitMessage") String commitMessage,
        @JsonProperty("prTitle")       String prTitle,
        @JsonProperty("prBody")        String prBody
) {}
