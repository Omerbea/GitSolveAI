package com.gitsolve.agent.swe;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Structured output from the SWE Agent's fix generation call.
 * Deserialised from the LLM's JSON response by SweFixParser.
 *
 * Pure Java record — uses @JsonProperty so a plain new ObjectMapper()
 * (not a Spring-configured one) can deserialise it without additional modules.
 */
public record SweFixResponse(
        @JsonProperty("filePath")      String filePath,
        @JsonProperty("fixedContent")  String fixedContent,
        @JsonProperty("explanation")   String explanation
) {}
