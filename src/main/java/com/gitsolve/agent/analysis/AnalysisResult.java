package com.gitsolve.agent.analysis;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.gitsolve.model.PhaseStats;

import java.util.List;

/**
 * Structured output from the Senior Engineer Analysis Agent.
 * Produced for every accepted issue — no build, no code writing.
 *
 * <p>The {@code phaseStats} field is not part of the LLM's JSON response.
 * It is injected after parsing via {@link #withPhaseStats(PhaseStats)} so that
 * the orchestrator can propagate telemetry without a separate wrapper type.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AnalysisResult(
        @JsonProperty("rootCauseAnalysis")   String rootCauseAnalysis,
        @JsonProperty("affectedFiles")        List<String> affectedFiles,
        @JsonProperty("suggestedApproach")    String suggestedApproach,
        @JsonProperty("estimatedComplexity")  String estimatedComplexity,
        @JsonProperty("relevantPatterns")     String relevantPatterns,
        @JsonIgnore                           PhaseStats phaseStats        // not from LLM JSON
) {
    /**
     * Returns a copy of this result with the given phaseStats attached.
     * Used by {@code AnalysisService} after parsing the LLM JSON response.
     */
    public AnalysisResult withPhaseStats(PhaseStats stats) {
        return new AnalysisResult(
                rootCauseAnalysis,
                affectedFiles,
                suggestedApproach,
                estimatedComplexity,
                relevantPatterns,
                stats
        );
    }

    /** Safe fallback when parsing fails. */
    public static AnalysisResult error(String reason) {
        return new AnalysisResult(
                "Analysis failed: " + reason,
                List.of(),
                "Unable to generate approach.",
                "UNKNOWN",
                "",
                null
        );
    }
}
