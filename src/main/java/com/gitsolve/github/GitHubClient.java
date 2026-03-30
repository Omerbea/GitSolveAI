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
     * Searches GitHub for Java repositories within a star range, with a random page offset
     * to avoid always returning the same repos.
     * starRange format: "50..2000" (GitHub search syntax).
     */
    public Mono<List<GitHubRepoDto>> searchJavaReposByStarRange(String starRange, int page, int perPage) {
        String query = "language:java+is:public+archived:false+stars:" + starRange;
        return webClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/search/repositories")
                        .queryParam("q", query)
                        .queryParam("sort", "updated")
                        .queryParam("order", "desc")
                        .queryParam("page", page)
                        .queryParam("per_page", perPage)
                        .build())
                .retrieve()
                .bodyToMono(GitHubSearchResponse.class)
                .map(GitHubSearchResponse::items)
                .onErrorResume(e -> {
                    if (e instanceof GitHubRateLimitException) return Mono.error(e);
                    log.error("searchJavaReposByStarRange failed: {}", e.getMessage());
                    return Mono.just(List.of());
                });
    }

    /**
     * Fetches repo metadata for a specific owner/repo.
     * Returns empty Mono if the repo does not exist.
     */
    public Mono<GitHubRepoDto> getRepo(String fullName) {
        return webClient.get()
                .uri("/repos/" + fullName)
                .retrieve()
                .bodyToMono(GitHubRepoDto.class)
                .onErrorResume(WebClientResponseException.NotFound.class, e -> Mono.empty())
                .onErrorResume(e -> {
                    if (e instanceof GitHubRateLimitException) return Mono.error(e);
                    log.error("getRepo({}) failed: {}", fullName, e.getMessage());
                    return Mono.empty();
                });
    }

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
     * Fetches the last N commits from the default branch of a repository.
     * Returns a list of strings in format: "SHA (short) — Author: message"
     */
    @SuppressWarnings("unchecked")
    public Mono<List<String>> getRecentCommits(String fullName, int count) {
        return webClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/repos/" + fullName + "/commits")
                        .queryParam("per_page", Math.min(count, 30))
                        .build())
                .retrieve()
                .bodyToFlux(Map.class)
                .take(count)
                .map(commit -> {
                    String sha = ((String) commit.getOrDefault("sha", "")).substring(0, 7);
                    Map<String, Object> commitObj = (Map<String, Object>) commit.get("commit");
                    if (commitObj == null) return sha + " — (no data)";
                    String message = (String) commitObj.getOrDefault("message", "");
                    // Only first line of commit message
                    message = message.contains("\n") ? message.substring(0, message.indexOf('\n')) : message;
                    Map<String, Object> author = (Map<String, Object>) commitObj.get("author");
                    String authorName = author != null ? (String) author.getOrDefault("name", "?") : "?";
                    return sha + " — " + authorName + ": " + message;
                })
                .collectList()
                .onErrorResume(e -> {
                    if (e instanceof GitHubRateLimitException) return Mono.error(e);
                    log.error("getRecentCommits({}) failed: {}", fullName, e.getMessage());
                    return Mono.just(List.of());
                });
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

    // ------------------------------------------------------------------ //
    // Write operations                                                     //
    // ------------------------------------------------------------------ //

    /**
     * Forks the given upstream repository to the authenticated user's account.
     *
     * <p>GitHub returns 202 Accepted (fork may not yet be ready) with the fork metadata.
     * The returned full_name ("forkOwner/repo") is safe to use immediately for branch
     * creation and push — GitHub's API handles the async completion transparently for
     * reads that follow shortly after.
     *
     * @param upstreamFullName "owner/repo" of the repository to fork
     * @return Mono of the forked repo's full_name (e.g. "gitsolvebot/commons-lang")
     */
    @SuppressWarnings("unchecked")
    public Mono<String> forkRepo(String upstreamFullName) {
        return webClient.post()
                .uri("/repos/" + upstreamFullName + "/forks")
                .bodyValue(Map.of())
                .retrieve()
                .bodyToMono(Map.class)
                .map(body -> (String) body.get("full_name"))
                .doOnNext(fork -> log.info("forkRepo({}) → {}", upstreamFullName, fork))
                .onErrorResume(e -> {
                    if (e instanceof GitHubRateLimitException) return Mono.error(e);
                    log.error("forkRepo({}) failed: {}", upstreamFullName, e.getMessage());
                    return Mono.error(e);
                });
    }

    /**
     * Opens a pull request from a branch on a fork against the upstream default branch.
     *
     * @param upstreamFullName "owner/repo" of the repository to open the PR against
     * @param forkFullName     "forkOwner/repo" — the fork that contains the branch
     * @param branch           branch name on the fork (e.g. "gitsolve/issue-123")
     * @param title            PR title
     * @param body             PR body (markdown); should include "Closes #N"
     * @return Mono of the PR html_url (e.g. "https://github.com/apache/commons-lang/pull/99")
     */
    @SuppressWarnings("unchecked")
    public Mono<String> createGitHubPr(String upstreamFullName, String forkFullName,
                                       String branch, String title, String body) {
        String forkOwner = forkFullName.split("/")[0];
        Map<String, Object> payload = Map.of(
                "title", title,
                "body",  body,
                "head",  forkOwner + ":" + branch,
                "base",  "main"
        );
        return webClient.post()
                .uri("/repos/" + upstreamFullName + "/pulls")
                .bodyValue(payload)
                .retrieve()
                .bodyToMono(Map.class)
                .map(resp -> (String) resp.get("html_url"))
                .doOnNext(url -> log.info("createGitHubPr({}) → {}", upstreamFullName, url))
                .onErrorResume(e -> {
                    if (e instanceof GitHubRateLimitException) return Mono.error(e);
                    log.error("createGitHubPr({}) failed: {}", upstreamFullName, e.getMessage());
                    return Mono.error(e);
                });
    }
}
