package com.gitsolve.agent.execution;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.gitsolve.config.GitSolveProperties;
import com.gitsolve.docker.BuildEnvironmentException;
import com.gitsolve.docker.BuildOutput;
import com.gitsolve.docker.DockerBuildEnvironment;
import com.gitsolve.github.GitHubClient;
import com.gitsolve.model.ExecutionResult;
import com.gitsolve.model.GitIssue;
import com.gitsolve.repocache.RepoCache;
import com.gitsolve.repocache.RepoCacheException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import reactor.core.publisher.Mono;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for ExecutionService — all external dependencies mocked.
 */
@Tag("unit")
@ExtendWith(MockitoExtension.class)
class ExecutionServiceTest {

    @Mock ApplicationContext       ctx;
    @Mock ExecutionAiService       aiService;
    @Mock ExecutionFixParser       parserMock;
    @Mock GitHubClient             gitHubClient;
    @Mock RepoCache                repoCache;
    @Mock DockerBuildEnvironment   env;
    @Mock FileSelectorAiService    fileSelectorAiService;
    @Mock FileSelectorParser       fileSelectorParser;

    @TempDir Path tempDir;

    private GitSolveProperties props;
    private ExecutionService   service;

    /** Valid LLM JSON response with one file. */
    private static final String VALID_JSON = """
            {
              "files": [{"path": "src/main/java/Foo.java", "content": "public class Foo {}"}],
              "commitMessage": "fix(core): resolve issue",
              "prTitle": "Fix the issue",
              "prBody": "Closes #42"
            }
            """;

    private static final ExecutionFixResponse VALID_RESPONSE = new ExecutionFixResponse(
            List.of(new FileChange("src/main/java/Foo.java", "public class Foo {}")),
            "fix(core): resolve issue",
            "Fix the issue",
            "Closes #42"
    );

    @BeforeEach
    void setUp() throws Exception {
        props = new GitSolveProperties(
                new GitSolveProperties.GitHub("test-token", 2, 5, List.of(), null),
                new GitSolveProperties.Llm("anthropic", "claude-test", 500_000, 3),
                new GitSolveProperties.Docker("eclipse-temurin:21-jdk", 1024, 50_000, 300),
                new GitSolveProperties.Schedule("0 0 0 * * *"),
                new GitSolveProperties.RepoCache(tempDir.toString(), false)
        );

        service = new ExecutionService(aiService, ctx, parserMock, gitHubClient, props, repoCache,
                fileSelectorAiService, fileSelectorParser);

        // Common stubs
        when(ctx.getBean(DockerBuildEnvironment.class)).thenReturn(env);
        when(gitHubClient.forkRepo(anyString())).thenReturn(Mono.just("bot/commons-lang"));
        when(repoCache.ensureFork(anyString())).thenReturn(tempDir);
        when(aiService.execute(anyString(), anyInt(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(VALID_JSON);
        lenient().when(aiService.executeFollowUp(anyString(), anyInt(), anyString(), anyString()))
                .thenReturn(VALID_JSON);
        when(parserMock.parse(anyString())).thenReturn(VALID_RESPONSE);
        when(env.listJavaFiles(anyString())).thenReturn(List.of("src/main/java/Foo.java"));
        when(env.getDiff()).thenReturn("--- a/Foo.java\n+++ b/Foo.java\n@@ -1 +1 @@");
        // File selector stubs — return empty so fallback path is exercised
        when(fileSelectorAiService.selectFiles(anyString(), anyString(), anyString(), anyString())).thenReturn("{\"paths\":[\"src/main/java/Foo.java\"]}");
        when(fileSelectorParser.parse(anyString(), anyList())).thenReturn(List.of("src/main/java/Foo.java"));
        when(env.readFile(anyString())).thenReturn(java.util.Optional.of("// stub content"));
    }

    // ------------------------------------------------------------------ //
    // Build passes on first iteration                                      //
    // ------------------------------------------------------------------ //

    @Test
    void execute_buildPassesOnFirstIteration_returnsPrUrl()
            throws BuildEnvironmentException, RepoCacheException {
        // All git/build commands pass
        BuildOutput passed = new BuildOutput("", "", 0, false, Duration.ofSeconds(1));
        when(env.runBuild(anyString())).thenReturn(passed);
        when(gitHubClient.createGitHubPr(anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(Mono.just("https://github.com/apache/commons-lang/pull/99"));

        GitIssue issue = new GitIssue("apache/commons-lang", 42, "Fix NPE", "body", null, List.of());
        ExecutionResult result = service.execute(issue, "Fix the NPE by doing X", List.of());

        assertThat(result.success()).isTrue();
        assertThat(result.prUrl()).isEqualTo("https://github.com/apache/commons-lang/pull/99");
        assertThat(result.iterations()).isEqualTo(1);
        assertThat(result.failureReason()).isNull();
    }

    // ------------------------------------------------------------------ //
    // All iterations fail — returns failure result                         //
    // ------------------------------------------------------------------ //

    @Test
    void execute_allIterationsFailBuild_returnsFailure()
            throws BuildEnvironmentException, RepoCacheException {
        // branch creation passes, everything else fails on runBuild
        BuildOutput gitPassed = new BuildOutput("", "", 0, false, Duration.ofMillis(10));
        BuildOutput buildFailed = new BuildOutput("", "[ERROR] BUILD FAILURE", 1, false, Duration.ofSeconds(5));

        // First two runBuild calls are git operations (checkout -b, config), rest are builds
        when(env.runBuild(anyString()))
                .thenReturn(gitPassed)   // checkout -b
                .thenReturn(buildFailed); // all subsequent (mvn clean test)

        GitIssue issue = new GitIssue("apache/commons-lang", 42, "Fix NPE", "body", null, List.of());
        ExecutionResult result = service.execute(issue, "Fix the NPE by doing X", List.of());

        assertThat(result.success()).isFalse();
        assertThat(result.failureReason()).containsIgnoringCase("Exhausted");
        assertThat(result.prUrl()).isNull();
    }

    // ------------------------------------------------------------------ //
    // Keyword fallback — selects by overlap with issue title/body         //
    // ------------------------------------------------------------------ //

    @Test
    void execute_keywordFallback_selectsMatchingPath()
            throws BuildEnvironmentException, RepoCacheException {
        // Override: file selector returns empty → keyword fallback kicks in
        when(fileSelectorParser.parse(anyString(), anyList())).thenReturn(List.of());
        // Two candidates — StringUtils scores higher against "Fix NPE in StringUtils"
        when(env.listJavaFiles(anyString())).thenReturn(
                List.of("src/main/java/AbstractXxx.java", "src/main/java/StringUtils.java"));

        // All git/build commands pass
        BuildOutput passed = new BuildOutput("", "", 0, false, Duration.ofSeconds(1));
        when(env.runBuild(anyString())).thenReturn(passed);
        when(gitHubClient.createGitHubPr(anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(Mono.just("https://github.com/apache/commons-lang/pull/99"));

        GitIssue issue = new GitIssue(
                "apache/commons-lang", 42, "Fix NPE in StringUtils", "StringUtils throws NPE",
                null, List.of());
        ExecutionResult result = service.execute(issue, "Fix the NPE by doing X", List.of());

        // Keyword fallback ran successfully — no NPE and execution completed
        assertThat(result.success()).isTrue();
        // Commit message file was written → confirms the build-pass path was reached
        verify(env).writeFile(eq(".gitsolve_commit_msg"), anyString());
        // readFile was called for the selected files (contextBuilder.build uses it)
        verify(env, atLeastOnce()).readFile(anyString());
    }

    // ------------------------------------------------------------------ //
    // Token safety — asserts token never appears in logs                  //
    // ------------------------------------------------------------------ //

    @Test
    void execute_tokenNeverAppearsInLogs()
            throws BuildEnvironmentException, RepoCacheException {

        // Capture log output from ExecutionService logger
        Logger execLogger = (Logger) LoggerFactory.getLogger(ExecutionService.class);
        ListAppender<ILoggingEvent> listAppender = new ListAppender<>();
        listAppender.start();
        execLogger.addAppender(listAppender);

        try {
            // Successful run stubs
            BuildOutput passed = new BuildOutput("", "", 0, false, Duration.ofSeconds(1));
            when(env.runBuild(anyString())).thenReturn(passed);
            when(gitHubClient.createGitHubPr(anyString(), anyString(), anyString(), anyString(), anyString()))
                    .thenReturn(Mono.just("https://github.com/apache/commons-lang/pull/99"));

            GitIssue issue = new GitIssue(
                    "apache/commons-lang", 42, "Fix NPE", "body", null, List.of());
            service.execute(issue, "Fix the NPE by changing Foo.java", List.of());

            // Assert that the actual token string ('test-token') never appears in any log message
            String token = "test-token"; // matches props.github().appToken() set in setUp()
            for (ILoggingEvent event : listAppender.list) {
                String msg = event.getFormattedMessage();
                assertThat(msg)
                        .as("Log message should not contain raw token: %s", msg)
                        .doesNotContain(token);
            }
        } finally {
            execLogger.detachAppender(listAppender);
        }
    }

    // ------------------------------------------------------------------ //
    // Token redaction — static helper, tested in                         //
    // ExecutionServiceRedactTokenTest to avoid UnnecessaryStubbing       //
    //                                                                    //
    // buildPrBody — also tested in ExecutionServiceRedactTokenTest       //
    // ------------------------------------------------------------------ //

    // ------------------------------------------------------------------ //
    // Shell injection — commit message written via -F flag, not -m       //
    // ------------------------------------------------------------------ //

    @Test
    void execute_commitMessageWithInjectionChars_usesFileFlag()
            throws BuildEnvironmentException, RepoCacheException {
        // Arrange: fix response with a commit message containing shell metacharacters
        String injectionMsg = "fix(core): close issue $(id) `whoami`";
        ExecutionFixResponse injectionResponse = new ExecutionFixResponse(
                List.of(new FileChange("src/main/java/Foo.java", "public class Foo {}")),
                injectionMsg,
                "Fix the issue",
                "Closes #42"
        );
        when(parserMock.parse(anyString())).thenReturn(injectionResponse);

        BuildOutput passed = new BuildOutput("", "", 0, false, Duration.ofSeconds(1));
        when(env.runBuild(anyString())).thenReturn(passed);
        when(gitHubClient.createGitHubPr(anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(Mono.just("https://github.com/apache/commons-lang/pull/99"));

        GitIssue issue = new GitIssue("apache/commons-lang", 42, "Fix NPE", "body", null, List.of());

        // Act
        ExecutionResult result = service.execute(issue, "Fix the NPE by doing X", List.of());

        // Assert: writeFile called with the injection-containing message
        verify(env).writeFile(eq(".gitsolve_commit_msg"), contains("$(id)"));

        // Assert: no git commit -m invocation (must use -F flag instead)
        verify(env, never()).runBuild(argThat(cmd -> cmd.contains("commit -m ")));

        // Assert: git commit -F was invoked with the temp file path
        verify(env).runBuild(argThat(cmd ->
                cmd.contains("commit -F /workspace/.gitsolve_commit_msg")));

        assertThat(result.success()).isTrue();
    }

    // ------------------------------------------------------------------ //
    // Second iteration uses executeFollowUp, not execute                  //
    // ------------------------------------------------------------------ //

    @Test
    void execute_secondIterationUsesFollowUp()
            throws BuildEnvironmentException, RepoCacheException {
        // Local props with maxIterationsPerFix=2 to keep the test self-contained
        GitSolveProperties localProps = new GitSolveProperties(
                new GitSolveProperties.GitHub("test-token", 2, 5, List.of(), null),
                new GitSolveProperties.Llm("anthropic", "claude-test", 500_000, 2),
                new GitSolveProperties.Docker("eclipse-temurin:21-jdk", 1024, 50_000, 300),
                new GitSolveProperties.Schedule("0 0 0 * * *"),
                new GitSolveProperties.RepoCache(tempDir.toString(), false)
        );
        ExecutionService localService = new ExecutionService(
                aiService, ctx, parserMock, gitHubClient, localProps, repoCache,
                fileSelectorAiService, fileSelectorParser);

        // Iteration 1 — build fails
        BuildOutput gitPass   = new BuildOutput("", "", 0, false, Duration.ofMillis(10));
        BuildOutput buildFail = new BuildOutput("", "[ERROR] BUILD FAILURE", 1, false, Duration.ofSeconds(5));
        // Iteration 2 — build passes, followed by all git/push/pr commands
        BuildOutput buildPass = new BuildOutput("", "", 0, false, Duration.ofSeconds(2));

        when(env.runBuild(anyString()))
                .thenReturn(gitPass)    // git checkout -b
                // iteration 1
                .thenReturn(gitPass)    // mvnw check
                .thenReturn(gitPass)    // pom check
                .thenReturn(buildFail)  // mvn clean package (iteration 1 — FAILS)
                // iteration 2
                .thenReturn(gitPass)    // mvnw check
                .thenReturn(gitPass)    // pom check
                .thenReturn(buildPass)  // mvn clean package (iteration 2 — PASSES)
                .thenReturn(buildPass)  // git config user.email
                .thenReturn(buildPass)  // git config user.name
                .thenReturn(buildPass)  // git commit
                .thenReturn(buildPass)  // rm commit msg
                .thenReturn(buildPass)  // git remote set-url
                .thenReturn(buildPass); // git push

        when(aiService.executeFollowUp(anyString(), anyInt(), anyString(), anyString()))
                .thenReturn(VALID_JSON);
        when(gitHubClient.createGitHubPr(anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(Mono.just("https://github.com/apache/commons-lang/pull/99"));

        GitIssue issue = new GitIssue("apache/commons-lang", 42, "Fix NPE", "body", null, List.of());
        ExecutionResult result = localService.execute(issue, "Fix the NPE by doing X", List.of());

        assertThat(result.success()).isTrue();
        assertThat(result.iterations()).isEqualTo(2);

        // execute() must be called exactly once (iteration 1 only)
        verify(aiService, times(1)).execute(
                anyString(), anyInt(), anyString(), anyString(), anyString(), anyString());

        // executeFollowUp() must be called exactly once (iteration 2 only)
        verify(aiService, times(1)).executeFollowUp(
                anyString(), anyInt(), anyString(), anyString());
    }
}
