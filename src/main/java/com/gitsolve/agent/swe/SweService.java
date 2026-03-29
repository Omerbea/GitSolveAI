package com.gitsolve.agent.swe;

import com.gitsolve.config.GitSolveProperties;
import com.gitsolve.docker.BuildEnvironment;
import com.gitsolve.docker.BuildEnvironmentException;
import com.gitsolve.docker.BuildOutput;
import com.gitsolve.docker.DockerBuildEnvironment;
import com.gitsolve.model.FixAttempt;
import com.gitsolve.model.FixResult;
import com.gitsolve.model.GitIssue;
import dev.langchain4j.service.TokenStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * SWE Agent orchestrator — implements the iterative fix loop.
 *
 * <p>Each call to {@link #fix(GitIssue)} follows this sequence:
 * <ol>
 *   <li>Obtain a fresh {@link DockerBuildEnvironment} via the ApplicationContext.</li>
 *   <li>Clone the repository into the container.</li>
 *   <li>Build source context from all {@code src/**\/*.java} files.</li>
 *   <li>Call the LLM (blocking on stream) to generate a fix JSON.</li>
 *   <li>Write the proposed file into the container.</li>
 *   <li>Run {@code mvn clean test -q} and capture the build output.</li>
 *   <li>If the build passes, return a successful {@link FixResult}.</li>
 *   <li>If the build fails, feed the error back to the LLM and repeat.</li>
 *   <li>On exhaustion, return a failure {@link FixResult}.</li>
 * </ol>
 *
 * <p>Declared {@code prototype} scope so each invocation gets its own instance
 * with fresh state — no shared mutable state between parallel fix jobs.
 */
@Service
@Scope("prototype")
public class SweService {

    private static final Logger log = LoggerFactory.getLogger(SweService.class);

    /** Maximum seconds to await a streaming LLM response before timing out. */
    private static final long STREAM_TIMEOUT_SECONDS = 120L;

    private final SweAiService sweAiService;
    private final ApplicationContext context;
    private final SweFixParser sweFixParser;
    private final GitSolveProperties props;

    @Autowired
    public SweService(
            SweAiService sweAiService,
            ApplicationContext context,
            SweFixParser sweFixParser,
            GitSolveProperties props) {
        this.sweAiService  = sweAiService;
        this.context       = context;
        this.sweFixParser  = sweFixParser;
        this.props         = props;
    }

    // ------------------------------------------------------------------ //
    // Public API                                                           //
    // ------------------------------------------------------------------ //

    /**
     * Runs the full SWE Agent fix loop for the given issue.
     *
     * @param issue the GitHub issue to fix
     * @return a {@link FixResult} describing whether the fix succeeded and all attempts made
     * @throws BuildEnvironmentException if a fatal Docker/container error occurs
     */
    public FixResult fix(GitIssue issue) throws BuildEnvironmentException {
        String issueId = issue.repoFullName() + "#" + issue.issueNumber();
        String cloneUrl = "https://github.com/" + issue.repoFullName() + ".git";
        int maxIterations = props.llm().maxIterationsPerFix();

        List<FixAttempt> attempts = new ArrayList<>();

        try (BuildEnvironment env = context.getBean(DockerBuildEnvironment.class)) {
            // Clone repo
            env.cloneRepository(cloneUrl, null);

            // Build source context once (reused every iteration)
            List<String> javaFiles = env.listJavaFiles("src");
            String sourceContext = new ContextWindowManager(ContextWindowManager.MAX_CONTEXT_CHARS)
                    .buildContext(javaFiles, env);

            String buildError = "";   // empty on first iteration

            for (int i = 1; i <= maxIterations; i++) {
                log.info("[{}] SweService iteration {}/{}", issueId, i, maxIterations);

                // Call LLM (blocking)
                String rawResponse = blockOnStream(
                        sweAiService.generateFix(
                                issue.repoFullName(),
                                issue.title(),
                                issue.body(),
                                sourceContext,
                                buildError));

                // Parse fix response
                SweFixResponse fix;
                try {
                    fix = sweFixParser.parse(rawResponse);
                } catch (SweParseException e) {
                    log.warn("[{}] Iteration {}: failed to parse LLM response — {}",
                            issueId, i, e.getMessage());
                    buildError = "Parse error: " + e.getMessage();
                    attempts.add(new FixAttempt(i, "", "", buildError, false));
                    continue;
                }

                if (fix.filePath() == null || fix.filePath().isBlank()) {
                    throw new SweParseException("LLM returned null or blank filePath");
                }

                log.info("[{}] Iteration {}: writing fix to {}", issueId, i, fix.filePath());

                // Write fixed file into container
                env.writeFile(fix.filePath(), fix.fixedContent());

                // Run build
                BuildOutput buildOutput = env.runBuild("mvn clean test -q");
                String diff = env.getDiff();

                boolean passed = buildOutput.buildPassed();
                log.info("[{}] Iteration {}: build {} (exit {})",
                        issueId, i, passed ? "PASSED" : "FAILED", buildOutput.exitCode());

                attempts.add(new FixAttempt(
                        i,
                        fix.filePath(),
                        diff,
                        buildOutput.stdout() + buildOutput.stderr(),
                        passed));

                if (passed) {
                    log.info("[{}] Fix succeeded on iteration {}", issueId, i);
                    return new FixResult(issueId, true, attempts, diff, null);
                }

                // Feed the build error back for the next iteration
                buildError = buildOutput.extractStackTrace();
            }
        } catch (SweParseException e) {
            // filePath was null — treat as exhaustion with parse failure
            log.error("[{}] Fatal parse error (null filePath): {}", issueId, e.getMessage());
            return new FixResult(issueId, false, attempts, "",
                    "Fatal parse error: " + e.getMessage());
        }

        log.warn("[{}] Exhausted {} iterations without a passing build", issueId, maxIterations);
        return new FixResult(issueId, false, attempts, "",
                "Exhausted " + maxIterations + " iterations without a passing build");
    }

    // ------------------------------------------------------------------ //
    // Private helpers                                                      //
    // ------------------------------------------------------------------ //

    /**
     * Blocks on a {@link TokenStream} until the LLM finishes streaming or the
     * {@link #STREAM_TIMEOUT_SECONDS} deadline expires.
     *
     * @param stream the token stream to consume
     * @return the full accumulated response text
     * @throws RuntimeException if the stream emits an error or the timeout expires
     */
    private String blockOnStream(TokenStream stream) {
        CountDownLatch latch = new CountDownLatch(1);
        StringBuilder sb = new StringBuilder();
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        stream
                .onNext(sb::append)
                .onComplete(r -> latch.countDown())
                .onError(t -> {
                    errorRef.set(t);
                    latch.countDown();
                })
                .start();

        try {
            boolean finished = latch.await(STREAM_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                throw new RuntimeException(
                        "LLM stream timed out after " + STREAM_TIMEOUT_SECONDS + " seconds");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while awaiting LLM stream", e);
        }

        Throwable error = errorRef.get();
        if (error != null) {
            throw new RuntimeException("LLM stream error: " + error.getMessage(), error);
        }

        return sb.toString();
    }
}
