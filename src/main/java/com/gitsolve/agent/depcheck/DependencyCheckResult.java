package com.gitsolve.agent.depcheck;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Structured result from the dependency pre-check agent.
 *
 * <p>Uses {@code @JsonProperty} on every component so a plain {@code new ObjectMapper()}
 * (without Spring's parameter-names module) can deserialise it correctly.
 *
 * <p>Uses {@code @JsonIgnoreProperties(ignoreUnknown = true)} so extra LLM fields
 * (reasoning, etc.) are silently dropped rather than causing parse failures.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DependencyCheckResult(
        @JsonProperty("hasSuspiciousDeps") boolean hasSuspiciousDeps,
        @JsonProperty("findings")          List<String> findings,
        @JsonProperty("notes")             String notes
) {

    /**
     * Safe fallback returned when no build file is found, the LLM call fails,
     * or the response cannot be parsed.
     */
    public static DependencyCheckResult noIssues() {
        return new DependencyCheckResult(false, List.of(), "");
    }
}
