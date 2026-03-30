package com.gitsolve.agent.scout;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gitsolve.github.GitHubClient;
import com.gitsolve.github.dto.GitHubIssueDto;
import com.gitsolve.model.AppSettings;
import com.gitsolve.model.AppSettings.ScoutMode;
import com.gitsolve.model.GitIssue;
import com.gitsolve.model.GitRepository;
import com.gitsolve.persistence.SettingsStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for ScoutService using Mockito mocks — no Spring context.
 */
@Tag("unit")
class ScoutServiceTest {

    private ScoutAiService scoutAiService;
    private GitHubClient   gitHubClient;
    private SettingsStore  settingsStore;
    private ScoutService   service;

    private static final String SAMPLE_JSON = """
            [
              {"fullName":"apache/commons-lang","cloneUrl":"https://github.com/apache/commons-lang.git",
               "htmlUrl":"https://github.com/apache/commons-lang","starCount":2500,
               "forkCount":800,"commitCount":42,"velocityScore":78.5},
              {"fullName":"google/guava","cloneUrl":"https://github.com/google/guava.git",
               "htmlUrl":"https://github.com/google/guava","starCount":50000,
               "forkCount":11000,"commitCount":30,"velocityScore":91.2},
              {"fullName":"spring-projects/spring-framework",
               "cloneUrl":"https://github.com/spring-projects/spring-framework.git",
               "htmlUrl":"https://github.com/spring-projects/spring-framework","starCount":55000,
               "forkCount":37000,"commitCount":20,"velocityScore":85.0}
            ]
            """;

    @BeforeEach
    void setUp() {
        scoutAiService = mock(ScoutAiService.class);
        gitHubClient   = mock(GitHubClient.class);
        settingsStore  = mock(SettingsStore.class);
        // Default: LLM mode — tests exercise the LLM path unless overridden
        AppSettings defaults = new AppSettings(ScoutMode.LLM, List.of(), 100, 3000, 5, 5);
        when(settingsStore.load()).thenReturn(defaults);

        service = new ScoutService(scoutAiService, gitHubClient, new ObjectMapper(), settingsStore);
    }

    // ------------------------------------------------------------------ //
    // discoverTargetRepos                                                  //
    // ------------------------------------------------------------------ //

    @Test
    void discoverTargetRepos_returnsRankedRepos() {
        when(scoutAiService.discoverRepositories(anyInt(), anyString()))
                .thenReturn(SAMPLE_JSON);

        List<GitRepository> result = service.discoverTargetRepos(3);

        assertThat(result).hasSize(3);
        assertThat(result).extracting(GitRepository::fullName)
                .containsExactly("apache/commons-lang", "google/guava",
                        "spring-projects/spring-framework");
    }

    @Test
    void discoverTargetRepos_handlesCodeFenceWrappedJson() {
        String fenced = "```json\n" + SAMPLE_JSON + "\n```";
        when(scoutAiService.discoverRepositories(anyInt(), anyString()))
                .thenReturn(fenced);

        List<GitRepository> result = service.discoverTargetRepos(3);

        assertThat(result).hasSize(3);
        assertThat(result.get(0).fullName()).isEqualTo("apache/commons-lang");
    }

    @Test
    void discoverTargetRepos_handlesPlainCodeFence() {
        String fenced = "```\n" + SAMPLE_JSON + "\n```";
        when(scoutAiService.discoverRepositories(anyInt(), anyString()))
                .thenReturn(fenced);

        List<GitRepository> result = service.discoverTargetRepos(3);

        assertThat(result).hasSize(3);
    }

    @Test
    void discoverTargetRepos_returnsEmptyListOnMalformedJson() {
        when(scoutAiService.discoverRepositories(anyInt(), anyString()))
                .thenReturn("{invalid json");

        List<GitRepository> result = service.discoverTargetRepos(3);

        assertThat(result).isEmpty();
    }

    @Test
    void discoverTargetRepos_returnsEmptyListOnNullResponse() {
        when(scoutAiService.discoverRepositories(anyInt(), anyString()))
                .thenReturn(null);

        List<GitRepository> result = service.discoverTargetRepos(3);

        assertThat(result).isEmpty();
    }

    // ------------------------------------------------------------------ //
    // fetchGoodFirstIssues                                                 //
    // ------------------------------------------------------------------ //

    @Test
    void fetchGoodFirstIssues_mapsGitHubIssueDtoToGitIssue() {
        GitRepository repo = new GitRepository(
                "apache/commons-lang",
                "https://github.com/apache/commons-lang.git",
                "https://github.com/apache/commons-lang",
                2500, 800, 42, 78.5);

        List<GitHubIssueDto> dtos = List.of(
                new GitHubIssueDto(101, "Fix NPE in StringUtils",
                        "Null pointer when calling isBlank(null)",
                        "https://github.com/apache/commons-lang/issues/101",
                        List.of(new GitHubIssueDto.LabelDto("good first issue"),
                                new GitHubIssueDto.LabelDto("bug"))),
                new GitHubIssueDto(102, "Add test coverage for CharUtils",
                        "Coverage gap in CharUtils.toIntValue",
                        "https://github.com/apache/commons-lang/issues/102",
                        List.of(new GitHubIssueDto.LabelDto("good first issue")))
        );

        when(gitHubClient.getGoodFirstIssues(eq("apache/commons-lang"), anyInt()))
                .thenReturn(Mono.just(dtos));

        List<GitIssue> issues = service.fetchGoodFirstIssues(List.of(repo), 5);

        assertThat(issues).hasSize(2);
        assertThat(issues.get(0).issueNumber()).isEqualTo(101);
        assertThat(issues.get(0).title()).isEqualTo("Fix NPE in StringUtils");
        assertThat(issues.get(0).repoFullName()).isEqualTo("apache/commons-lang");
        assertThat(issues.get(0).labels()).contains("good first issue", "bug");
        assertThat(issues.get(1).issueNumber()).isEqualTo(102);
    }

    @Test
    void fetchGoodFirstIssues_handlesEmptyIssueList() {
        GitRepository repo = new GitRepository("owner/repo", "clone", "html", 100, 10, 5, 50.0);
        when(gitHubClient.getGoodFirstIssues(anyString(), anyInt()))
                .thenReturn(Mono.just(List.of()));

        List<GitIssue> issues = service.fetchGoodFirstIssues(List.of(repo), 5);

        assertThat(issues).isEmpty();
    }

    // ------------------------------------------------------------------ //
    // discoverTargetRepos — prose response fallback                       //
    // ------------------------------------------------------------------ //

    @Test
    void discoverTargetRepos_extractsArrayFromProseResponse() {
        String proseWithArray =
                "Based on my search, here are the repositories I found:\n" +
                "[{\"fullName\":\"apache/commons-lang\",\"cloneUrl\":\"https://github.com/apache/commons-lang.git\"," +
                "\"htmlUrl\":\"https://github.com/apache/commons-lang\",\"starCount\":2100," +
                "\"forkCount\":800,\"commitCount\":42,\"velocityScore\":8.5}]\n" +
                "These are the most active repos with good-first-issues.";

        when(scoutAiService.discoverRepositories(anyInt(), anyString()))
                .thenReturn(proseWithArray);

        List<GitRepository> result = service.discoverTargetRepos(3);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).fullName()).isEqualTo("apache/commons-lang");
    }

    // ------------------------------------------------------------------ //
    // extractJson (static helper)                                          //
    // ------------------------------------------------------------------ //

    @Test
    void extractJson_cleanArray_returnedAsIs() {
        String input = "[{\"fullName\":\"owner/repo\"}]";
        assertThat(ScoutService.extractJson(input)).isEqualTo(input);
    }

    @Test
    void extractJson_codeFencedArray_stripped() {
        assertThat(ScoutService.extractJson("```json\n[]\n```")).isEqualTo("[]");
    }

    @Test
    void extractJson_proseWrapped_arrayExtracted() {
        String input = "Here are the results: [{\"fullName\":\"owner/repo\"}] Hope this helps!";
        assertThat(ScoutService.extractJson(input)).isEqualTo("[{\"fullName\":\"owner/repo\"}]");
    }

    @Test
    void extractJson_noArrayPresent_returnsEmptyArray() {
        assertThat(ScoutService.extractJson("No repositories found.")).isEqualTo("[]");
    }

    @Test
    void extractJson_null_returnsEmptyArray() {
        assertThat(ScoutService.extractJson(null)).isEqualTo("[]");
    }

    // ------------------------------------------------------------------ //
    // stripCodeFences (static helper)                                      //
    // ------------------------------------------------------------------ //

    @Test
    void stripCodeFences_bareJson_unchanged() {
        assertThat(ScoutService.stripCodeFences("[{\"a\":1}]")).isEqualTo("[{\"a\":1}]");
    }

    @Test
    void stripCodeFences_jsonFence_stripped() {
        assertThat(ScoutService.stripCodeFences("```json\n[]\n```")).isEqualTo("[]");
    }

    @Test
    void stripCodeFences_plainFence_stripped() {
        assertThat(ScoutService.stripCodeFences("```\n[]\n```")).isEqualTo("[]");
    }

    @Test
    void stripCodeFences_null_returnsEmpty() {
        assertThat(ScoutService.stripCodeFences(null)).isEqualTo("");
    }
}
