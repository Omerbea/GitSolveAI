package com.gitsolve.agent.buildclassifier;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Structured representation of a classified build failure.
 *
 * <p>Produced by {@link BuildFailureClassifier} after the LLM analyses raw build output.
 *
 * <p>Uses {@code @JsonProperty} on every component so a plain {@code new ObjectMapper()}
 * (without Spring's parameter-names module) can deserialise it correctly.
 * {@code @JsonIgnoreProperties(ignoreUnknown = true)} tolerates extra LLM-emitted fields.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BuildFailure(
        @JsonProperty("type")         BuildFailureType type,
        @JsonProperty("location")     String location,
        @JsonProperty("excerpt")      String excerpt,
        @JsonProperty("suggestedFix") String suggestedFix
) {

    /**
     * Safe fallback returned when classification is absent, fails, or cannot be parsed.
     */
    public static BuildFailure unknown() {
        return new BuildFailure(BuildFailureType.LLM_HALLUCINATION, "", "", "");
    }
}
