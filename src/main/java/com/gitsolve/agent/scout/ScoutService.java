package com.gitsolve.agent.scout;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gitsolve.github.GitHubClient;
import com.gitsolve.github.dto.GitHubIssueDto;
import com.gitsolve.github.dto.GitHubRepoDto;
import com.gitsolve.model.AppSettings;
import com.gitsolve.model.AppSettings.ScoutMode;
import com.gitsolve.model.GitIssue;
import com.gitsolve.model.GitRepository;
import com.gitsolve.persistence.SettingsStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Orchestrates the Scout Agent's discovery and issue-fetching flow.
 *
 * discoverTargetRepos() reads runtime settings from SettingsStore and uses
 * one of three strategies:
 *
 *   PINNED     — resolve each repo in settings.targetRepos via GitHub API; no LLM.
 *   STAR_RANGE — search GitHub for repos in [starMin..starMax] with a random page offset.
 *   LLM        — original LLM-driven Scout Agent (stars > 500).
 *
 * fetchGoodFirstIssues() calls the GitHub API directly for each discovered repo.
 */
@Service
public class ScoutService {

    private static final Logger log = LoggerFactory.getLogger(ScoutService.class);

    private final ScoutAiService scoutAiService;
    private final GitHubClient   gitHubClient;
    private final ObjectMapper   objectMapper;
    private final SettingsStore  settingsStore;

    public ScoutService(
            ScoutAiService scoutAiService,
            GitHubClient gitHubClient,
            ObjectMapper objectMapper,
            SettingsStore settingsStore) {
        this.scoutAiService = scoutAiService;
        this.gitHubClient   = gitHubClient;
        this.objectMapper   = objectMapper;
        this.settingsStore  = settingsStore;
    }

    /**
     * Discovers target repositories using the strategy configured in app_settings.
     */
    public List<GitRepository> discoverTargetRepos(int maxRepos) {
        AppSettings settings = settingsStore.load();
        ScoutMode   mode     = settings.scoutMode() != null ? settings.scoutMode() : ScoutMode.LLM;

        log.info("Scout: mode={} maxRepos={}", mode, maxRepos);

        return switch (mode) {
            case PINNED     -> discoverPinned(settings, maxRepos);
            case STAR_RANGE -> discoverByStarRange(settings, maxRepos);
            case LLM        -> discoverViaLlm(maxRepos);
        };
    }

    // ------------------------------------------------------------------ //
    // Strategy implementations                                            //
    // ------------------------------------------------------------------ //

    private List<GitRepository> discoverPinned(AppSettings settings, int maxRepos) {
        List<String> targets = settings.targetRepos();
        if (targets == null || targets.isEmpty()) {
            log.warn("Scout: PINNED mode but no target repos configured — falling back to LLM");
            return discoverViaLlm(maxRepos);
        }
        log.info("Scout: resolving {} pinned repos", Math.min(targets.size(), maxRepos));
        return targets.stream()
                .limit(maxRepos)
                .map(fullName -> {
                    GitHubRepoDto dto = gitHubClient.getRepo(fullName).block();
                    if (dto == null) {
                        log.warn("Scout: pinned repo '{}' not found on GitHub — skipping", fullName);
                        return null;
                    }
                    return toGitRepository(dto);
                })
                .filter(r -> r != null)
                .collect(Collectors.toList());
    }

    private List<GitRepository> discoverByStarRange(AppSettings settings, int maxRepos) {
        String starRange  = settings.starRange();
        int    randomPage = 1 + new Random().nextInt(5); // pages 1–5 for variety
        log.info("Scout: star-range search stars:{} page={}", starRange, randomPage);

        List<GitHubRepoDto> dtos = gitHubClient
                .searchJavaReposByStarRange(starRange, randomPage, maxRepos)
                .block();

        if (dtos == null || dtos.isEmpty()) {
            log.warn("Scout: star-range search returned no repos — falling back to LLM");
            return discoverViaLlm(maxRepos);
        }
        log.info("Scout: found {} repos via star-range search", dtos.size());
        return dtos.stream().map(this::toGitRepository).collect(Collectors.toList());
    }

    private List<GitRepository> discoverViaLlm(int maxRepos) {
        String today = LocalDate.now().toString();
        log.info("Scout: LLM discovery top {} repos (date={})", maxRepos, today);

        String raw  = scoutAiService.discoverRepositories(maxRepos, today);
        String json = extractJson(raw);
        try {
            List<GitRepository> repos = objectMapper.readValue(json,
                    new TypeReference<List<GitRepository>>() {});
            log.info("Scout: parsed {} repositories from LLM response", repos.size());
            return repos;
        } catch (Exception e) {
            log.error("Scout: failed to parse LLM response. raw={}, error={}", raw, e.getMessage());
            return List.of();
        }
    }

    private GitRepository toGitRepository(GitHubRepoDto dto) {
        return new GitRepository(dto.fullName(), dto.cloneUrl(), dto.htmlUrl(),
                dto.stargazersCount(), dto.forksCount(), 0, 0.0);
    }

    // ------------------------------------------------------------------ //
    // fetchGoodFirstIssues                                                //
    // ------------------------------------------------------------------ //

    /**
     * Fetches good-first-issue tickets for each discovered repository.
     */
    public List<GitIssue> fetchGoodFirstIssues(List<GitRepository> repos, int maxPerRepo) {
        return repos.stream()
                .flatMap(repo -> {
                    List<GitHubIssueDto> dtos =
                            gitHubClient.getGoodFirstIssues(repo.fullName(), maxPerRepo).block();
                    if (dtos == null) return Stream.empty();
                    return dtos.stream().map(dto -> new GitIssue(
                            repo.fullName(),
                            dto.number(),
                            dto.title(),
                            dto.body(),
                            dto.htmlUrl(),
                            dto.labels() != null
                                    ? dto.labels().stream().map(l -> l.name()).collect(Collectors.toList())
                                    : List.of()
                    ));
                })
                .collect(Collectors.toList());
    }

    // ------------------------------------------------------------------ //
    // JSON parsing helpers (package-private for unit tests)               //
    // ------------------------------------------------------------------ //

    static String extractJson(String raw) {
        if (raw == null) return "[]";
        String trimmed = raw.strip();
        trimmed = stripCodeFences(trimmed);
        if (trimmed.startsWith("[")) return trimmed;
        int start = trimmed.indexOf('[');
        int end   = trimmed.lastIndexOf(']');
        if (start != -1 && end != -1 && end > start) {
            log.warn("Scout: LLM returned prose — extracted JSON array from response");
            return trimmed.substring(start, end + 1);
        }
        log.warn("Scout: LLM returned no JSON array in response. raw={}", raw);
        return "[]";
    }

    static String stripCodeFences(String raw) {
        if (raw == null) return "";
        String trimmed = raw.strip();
        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            if (firstNewline != -1) trimmed = trimmed.substring(firstNewline + 1);
        }
        if (trimmed.endsWith("```")) {
            trimmed = trimmed.substring(0, trimmed.lastIndexOf("```")).strip();
        }
        return trimmed;
    }
}
