package com.gitsolve.agent.analysis;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Structured output from the Senior Engineer Analysis Agent.
 * Produced for every accepted issue — no build, no code writing.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AnalysisResult(
        @JsonProperty("rootCauseAnalysis")   String rootCauseAnalysis,
        @JsonProperty("affectedFiles")        List<String> affectedFiles,
        @JsonProperty("suggestedApproach")    String suggestedApproach,
        @JsonProperty("estimatedComplexity")  String estimatedComplexity,
        @JsonProperty("relevantPatterns")     String relevantPatterns
) {
    /** Safe fallback when parsing fails. */
    public static AnalysisResult error(String reason) {
        return new AnalysisResult(
                "Analysis failed: " + reason,
                List.of(),
                "Unable to generate approach.",
                "UNKNOWN",
                ""
        );
    }
}
