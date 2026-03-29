package com.gitsolve.github.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Wrapper for the GitHub Search API response envelope.
 * GET /search/repositories returns { "total_count": N, "items": [...] }
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GitHubSearchResponse(
        @JsonProperty("total_count") int totalCount,
        List<GitHubRepoDto> items
) {}
