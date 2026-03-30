package com.gitsolve.agent.execution;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Response from {@link FileSelectorAiService} — the LLM's list of
 * file paths it needs to implement the fix.
 */
public record FileSelectorResponse(
        @JsonProperty("paths") List<String> paths
) {}
