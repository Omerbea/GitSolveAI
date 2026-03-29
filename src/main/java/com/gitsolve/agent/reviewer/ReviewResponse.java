package com.gitsolve.agent.reviewer;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Structured output from the Reviewer Agent's fix validation call.
 * Deserialised from the LLM's JSON response by ReviewerService using a plain ObjectMapper.
 *
 * Uses @JsonProperty on each component so a plain new ObjectMapper()
 * (without Spring's parameter-names module) can deserialise it correctly.
 */
public record ReviewResponse(
        @JsonProperty("approved")   boolean approved,
        @JsonProperty("violations") List<String> violations,
        @JsonProperty("summary")    String summary
) {}
