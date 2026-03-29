package com.gitsolve.github;

import com.gitsolve.github.dto.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Typed GitHub API client for the Scout Agent and other consumers.
 *
 * All methods are reactive (Mono). Do NOT call .block() from agent code.
 *
 * Path variables containing '/' (e.g. "apache/commons-lang") must NOT be
 * passed to UriBuilder.build() as a single variable — Spring's URI template
 * encoding would encode '/' as '%2F'. Use string interpolation in the path instead.
 */
@Component
public class GitHubClient {

    private static final Logger log = LoggerFactory.getLogger(GitHubClient.class);

    private final WebClient webClient;

    /** Primary constructor — Spring injects the configured gitHubWebClient bean. */
    public GitHubClient(@Qualifier("gitHubWebClient") WebClient webClient) {
        this.webClient = webClient;
    }

    // ------------------------------------------------------------------ //
    // Scout endpoints                                                      //
    // ------------------------------------------------------------------ //

    /**
     * Searches GitHub for active Java repositories.
     * GitHubRateLimitException propagates to the caller — not swallowed.
     */
    public Mono<List<GitHubRepoDto>> searchJavaRepos(int page, int perPage) {
        return webClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/search/repositories")
                        .queryParam("q", "language:java+is:public+archived:false")
                        .queryParam("sort", "stars")
                        .queryParam("order", "desc")
                        .queryParam("page", page)
                        .queryParam("per_page", perPage)
                        .build())
                .retrieve()
                .bodyToMono(GitHubSearchResponse.class)
                .map(GitHubSearchResponse::items)
                .onErrorResume(e -> {
                    // Propagate rate limit exceptions — do not swallow them
                    if (e instanceof GitHubRateLimitException) return Mono.error(e);
                    log.error("searchJavaRepos failed: {}", e.getMessage());
                    return Mono.just(List.of());
                });
    }

    /**
     * Fetches open "good first issue" tickets for the given repository.
     * NOTE: fullName ("owner/repo") is interpolated directly into the path string
     * to avoid URI template encoding of '/'.
     */
    public Mono<List<GitHubIssueDto>> getGoodFirstIssues(String fullName, int perPage) {
        return webClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/repos/" + fullName + "/issues")
                        .queryParam("state", "open")
                        .queryParam("labels", "good first issue")
                        .queryParam("per_page", perPage)
                        .build())
                .retrieve()
                .bodyToFlux(GitHubIssueDto.class)
                .collectList()
                .onErrorResume(e -> {
                    if (e instanceof GitHubRateLimitException) return Mono.error(e);
                    log.error("getGoodFirstIssues({}) failed: {}", fullName, e.getMessage());
                    return Mono.just(List.of());
                });
    }

    /**
     * Fetches and decodes a file from a repository.
     * GitHub returns the content as base64 in a JSON envelope.
     * Returns empty Mono if the file does not exist (404).
     * fullName is interpolated directly into the path to avoid '%2F' encoding.
     */
    @SuppressWarnings("unchecked")
    public Mono<String> getFileContent(String fullName, String path) {
        return webClient.get()
                .uri("/repos/" + fullName + "/contents/" + path)
                .retrieve()
                .bodyToMono(Map.class)
                .map(body -> {
                    Object content = body.get("content");
                    if (content == null) return "";
                    // GitHub base64-encodes with newlines — strip whitespace before decoding
                    String encoded = content.toString().replaceAll("\\s", "");
                    return new String(Base64.getDecoder().decode(encoded));
                })
                .onErrorResume(WebClientResponseException.NotFound.class, e -> Mono.empty())
                .onErrorResume(e -> {
                    if (e instanceof WebClientResponseException.NotFound) return Mono.empty();
                    if (e instanceof GitHubRateLimitException) return Mono.error(e);
                    log.error("getFileContent({}, {}) failed: {}", fullName, path, e.getMessage());
                    return Mono.empty();
                });
    }

    /**
     * Returns the current rate limit status for the authenticated user.
     */
    @SuppressWarnings("unchecked")
    public Mono<GitHubRateLimit> getRateLimit() {
        return webClient.get()
                .uri("/rate_limit")
                .retrieve()
                .bodyToMono(Map.class)
                .map(body -> {
                    Map<String, Object> resources = (Map<String, Object>) body.get("resources");
                    Map<String, Object> search = resources != null
                            ? (Map<String, Object>) resources.get("search")
                            : null;
                    if (search == null) return new GitHubRateLimit(0, 0L, 0);
                    return new GitHubRateLimit(
                            ((Number) search.getOrDefault("remaining", 0)).intValue(),
                            ((Number) search.getOrDefault("reset", 0L)).longValue(),
                            ((Number) search.getOrDefault("limit", 0)).intValue()
                    );
                })
                .doOnError(e -> log.error("getRateLimit failed: {}", e.getMessage()));
    }

    /**
     * Counts commits in the last 90 days.
     * Capped at 100 (GitHub's per_page max) — use 100 as "≥100 commits".
     */
    public Mono<Integer> getRecentCommitCount(String fullName) {
        String since = Instant.now().minus(90, ChronoUnit.DAYS).toString();
        return webClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/repos/" + fullName + "/commits")
                        .queryParam("since", since)
                        .queryParam("per_page", 100)
                        .build())
                .retrieve()
                .bodyToFlux(Map.class)
                .count()
                .map(Long::intValue)
                .onErrorResume(e -> {
                    if (e instanceof GitHubRateLimitException) return Mono.error(e);
                    log.error("getRecentCommitCount({}) failed: {}", fullName, e.getMessage());
                    return Mono.just(0);
                });
    }
}
