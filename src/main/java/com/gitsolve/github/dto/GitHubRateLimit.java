package com.gitsolve.github.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * GitHub rate limit information.
 * Populated from GET /rate_limit response (rate.search sub-object).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GitHubRateLimit(
        int remaining,
        long reset,   // epoch seconds at which the limit resets
        int limit
) {}
