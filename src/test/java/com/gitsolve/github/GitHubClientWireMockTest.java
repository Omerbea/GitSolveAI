package com.gitsolve.github;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.gitsolve.github.dto.GitHubIssueDto;
import com.gitsolve.github.dto.GitHubRepoDto;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.Tag;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * WireMock-based unit tests for GitHubClient and RateLimitRetryFilter.
 * No Spring context loaded — GitHubClient is instantiated directly with a test WebClient.
 */
@Tag("unit")
class GitHubClientWireMockTest {

    private WireMockServer wireMock;
    private GitHubClient client;

    @BeforeEach
    void setUp() {
        wireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMock.start();
        WireMock.configureFor("localhost", wireMock.port());

        WebClient testWebClient = WebClient.builder()
                .baseUrl("http://localhost:" + wireMock.port())
                .defaultHeader("Authorization", "Bearer test-token")
                .defaultHeader("Accept", "application/vnd.github+json")
                .filter(new RateLimitRetryFilter())
                .build();

        client = new GitHubClient(testWebClient);
    }

    @AfterEach
    void tearDown() {
        wireMock.stop();
    }

    // ------------------------------------------------------------------ //
    // Search repos                                                         //
    // ------------------------------------------------------------------ //

    @Test
    void searchJavaRepos_returnsTypedDtos() {
        stubFor(get(urlPathEqualTo("/search/repositories"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "total_count": 2,
                                  "items": [
                                    {
                                      "id": 1,
                                      "full_name": "apache/commons-lang",
                                      "clone_url": "https://github.com/apache/commons-lang.git",
                                      "html_url": "https://github.com/apache/commons-lang",
                                      "stargazers_count": 2500,
                                      "forks_count": 800,
                                      "open_issues_count": 45,
                                      "language": "Java"
                                    },
                                    {
                                      "id": 2,
                                      "full_name": "google/guava",
                                      "clone_url": "https://github.com/google/guava.git",
                                      "html_url": "https://github.com/google/guava",
                                      "stargazers_count": 50000,
                                      "forks_count": 11000,
                                      "open_issues_count": 120,
                                      "language": "Java"
                                    }
                                  ]
                                }
                                """)));

        List<GitHubRepoDto> repos = client.searchJavaRepos(1, 2).block();

        assertThat(repos).hasSize(2);
        assertThat(repos.get(0).fullName()).isEqualTo("apache/commons-lang");
        assertThat(repos.get(1).fullName()).isEqualTo("google/guava");
        assertThat(repos.get(0).stargazersCount()).isEqualTo(2500);

        // Verify Authorization header was sent
        verify(getRequestedFor(urlPathEqualTo("/search/repositories"))
                .withHeader("Authorization", equalTo("Bearer test-token")));
    }

    // ------------------------------------------------------------------ //
    // Good first issues                                                    //
    // ------------------------------------------------------------------ //

    @Test
    void getGoodFirstIssues_returnsDtos() {
        stubFor(get(urlPathEqualTo("/repos/apache/commons-lang/issues"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                [
                                  {
                                    "number": 42,
                                    "title": "Fix NPE in StringUtils",
                                    "body": "When passing null it throws.",
                                    "html_url": "https://github.com/apache/commons-lang/issues/42",
                                    "labels": [{"name": "good first issue"}, {"name": "bug"}]
                                  }
                                ]
                                """)));

        List<GitHubIssueDto> issues = client.getGoodFirstIssues("apache/commons-lang", 10).block();

        assertThat(issues).hasSize(1);
        assertThat(issues.get(0).number()).isEqualTo(42);
        assertThat(issues.get(0).title()).isEqualTo("Fix NPE in StringUtils");
        assertThat(issues.get(0).labels()).extracting(GitHubIssueDto.LabelDto::name)
                .contains("good first issue");
    }

    // ------------------------------------------------------------------ //
    // File content                                                         //
    // ------------------------------------------------------------------ //

    @Test
    void getFileContent_returnsDecodedContent() {
        // "Hello World" base64-encoded
        stubFor(get(urlPathEqualTo("/repos/owner/repo/contents/CONTRIBUTING.md"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"content": "SGVsbG8gV29ybGQ=", "encoding": "base64"}
                                """)));

        String content = client.getFileContent("owner/repo", "CONTRIBUTING.md").block();

        assertThat(content).isEqualTo("Hello World");
    }

    @Test
    void getFileContent_returnsEmptyMonoOnNotFound() {
        stubFor(get(urlPathEqualTo("/repos/owner/missing/contents/CONTRIBUTING.md"))
                .willReturn(aResponse().withStatus(404)));

        String content = client.getFileContent("owner/missing", "CONTRIBUTING.md").block();

        assertThat(content).isNull(); // empty Mono.block() returns null
    }

    // ------------------------------------------------------------------ //
    // Rate limit filter                                                    //
    // ------------------------------------------------------------------ //

    @Test
    void rateLimitFilter_retriesOn429ThenSucceeds() {
        // First two requests return 429 with Retry-After: 0 (instant retry for test speed)
        // Third request returns 200
        stubFor(get(urlPathEqualTo("/search/repositories"))
                .inScenario("rate-limit")
                .whenScenarioStateIs("Started")
                .willReturn(aResponse()
                        .withStatus(429)
                        .withHeader("Retry-After", "0"))
                .willSetStateTo("first-retry"));

        stubFor(get(urlPathEqualTo("/search/repositories"))
                .inScenario("rate-limit")
                .whenScenarioStateIs("first-retry")
                .willReturn(aResponse()
                        .withStatus(429)
                        .withHeader("Retry-After", "0"))
                .willSetStateTo("second-retry"));

        stubFor(get(urlPathEqualTo("/search/repositories"))
                .inScenario("rate-limit")
                .whenScenarioStateIs("second-retry")
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"total_count\":0,\"items\":[]}")));

        List<GitHubRepoDto> repos = client.searchJavaRepos(1, 10).block();

        assertThat(repos).isEmpty();
        // Verify 3 total requests were made (initial + 2 retries)
        verify(3, getRequestedFor(urlPathEqualTo("/search/repositories")));
    }

    @Test
    void rateLimitFilter_throwsAfterThreeConsecutive429s() {
        stubFor(get(urlPathEqualTo("/search/repositories"))
                .willReturn(aResponse()
                        .withStatus(429)
                        .withHeader("Retry-After", "0")));

        assertThatThrownBy(() -> client.searchJavaRepos(1, 10).block())
                .isInstanceOf(GitHubRateLimitException.class)
                .hasMessageContaining("rate limit exceeded");

        // Initial request + MAX_RETRIES = 4 total attempts
        verify(RateLimitRetryFilter.MAX_RETRIES + 1,
                getRequestedFor(urlPathEqualTo("/search/repositories")));
    }

    // ------------------------------------------------------------------ //
    // forkRepo                                                             //
    // ------------------------------------------------------------------ //

    @Test
    void forkRepo_createsAndReturnsForkFullName() {
        stubFor(post(urlPathEqualTo("/repos/apache/commons-lang/forks"))
                .willReturn(aResponse()
                        .withStatus(202)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "id": 999,
                                  "full_name": "gitsolvebot/commons-lang",
                                  "clone_url": "https://github.com/gitsolvebot/commons-lang.git"
                                }
                                """)));

        String result = client.forkRepo("apache/commons-lang").block();

        assertThat(result).isEqualTo("gitsolvebot/commons-lang");
        verify(postRequestedFor(urlPathEqualTo("/repos/apache/commons-lang/forks"))
                .withHeader("Authorization", equalTo("Bearer test-token")));
    }

    // ------------------------------------------------------------------ //
    // createGitHubPr                                                       //
    // ------------------------------------------------------------------ //

    @Test
    void createGitHubPr_returnsHtmlUrl() {
        stubFor(post(urlPathEqualTo("/repos/apache/commons-lang/pulls"))
                .willReturn(aResponse()
                        .withStatus(201)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "number": 99,
                                  "html_url": "https://github.com/apache/commons-lang/pull/99",
                                  "state": "open"
                                }
                                """)));

        String prUrl = client.createGitHubPr(
                "apache/commons-lang",
                "gitsolvebot/commons-lang",
                "gitsolve/issue-42",
                "fix: resolve NPE in StringUtils",
                "Closes #42\n\nThis PR fixes the NPE."
        ).block();

        assertThat(prUrl).isEqualTo("https://github.com/apache/commons-lang/pull/99");

        // Verify the head field uses forkOwner:branch format
        verify(postRequestedFor(urlPathEqualTo("/repos/apache/commons-lang/pulls"))
                .withRequestBody(matchingJsonPath("$.head", equalTo("gitsolvebot:gitsolve/issue-42")))
                .withRequestBody(matchingJsonPath("$.base", equalTo("main"))));
    }

    @Test
    void createGitHubPr_propagatesErrorOnNon2xx() {
        stubFor(post(urlPathEqualTo("/repos/apache/commons-lang/pulls"))
                .willReturn(aResponse()
                        .withStatus(422)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"message": "Validation Failed", "errors": []}
                                """)));

        assertThatThrownBy(() -> client.createGitHubPr(
                "apache/commons-lang",
                "gitsolvebot/commons-lang",
                "gitsolve/issue-42",
                "fix: test",
                "Closes #42"
        ).block())
                .isInstanceOf(Exception.class);
    }
}
