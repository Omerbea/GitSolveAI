package com.gitsolve.agent.execution;

import com.gitsolve.config.GitSolveProperties;
import com.gitsolve.docker.BuildEnvironment;
import com.gitsolve.docker.BuildEnvironmentException;
import com.gitsolve.docker.BuildOutput;
import com.gitsolve.docker.DockerBuildEnvironment;
import com.gitsolve.github.GitHubClient;
import com.gitsolve.model.ExecutionResult;
import com.gitsolve.model.GitIssue;
import com.gitsolve.repocache.RepoCache;
import com.gitsolve.repocache.RepoCacheException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Execution Agent orchestrator.
 *
 * <p>Takes pre-generated fix instructions and a target issue, then:
 * <ol>
 *   <li>Forks the upstream repo (or reuses an existing fork) via the GitHub API.</li>
 *   <li>Ensures a local clone exists in the RepoCache.</li>
 *   <li>Opens a fresh Docker container and clones from the local cache (no network).</li>
 *   <li>Creates a branch {@code gitsolve/issue-{number}}.</li>
 *   <li>Calls the LLM to produce a multi-file fix JSON.</li>
 *   <li>Applies all file changes, runs {@code mvn clean test -q}.</li>
 *   <li>On pass: commits, pushes to the fork, opens a GitHub PR.</li>
 *   <li>On fail: feeds the build error back to the LLM and retries (up to maxIterations).</li>
 * </ol>
 *
 * <p>Declared {@code @Scope("prototype")} so each invocation gets a fresh LLM chat memory.
 *
 * <p><b>Token safety:</b> the GitHub token is injected into the git remote URL at runtime
 * inside the container. It is never passed to any log statement. All commands that contain
 * the token are sanitised via {@link #redactToken} before logging.
 *
 * <p><b>Progress reporting:</b> set a {@link ExecutionProgressReporter} via
 * {@link #setProgressReporter(long, ExecutionProgressReporter)} before calling
 * {@link #execute} to receive live step events. Defaults to {@link NoopProgressReporter}.
 */
@Service
@Scope("prototype")
public class ExecutionService {

    private static final Logger log = LoggerFactory.getLogger(ExecutionService.class);

    /** Redacts the token from a git remote URL before logging. */
    private static final Pattern TOKEN_PATTERN =
            Pattern.compile("https://x-access-token:[^@]+@");

    private final ExecutionAiService   aiService;
    private final ApplicationContext   context;
    private final ExecutionFixParser   parser;
    private final GitHubClient         gitHubClient;
    private final GitSolveProperties   props;
    private final RepoCache            repoCache;
    private final FileSelectorAiService fileSelectorAiService;
    private final FileSelectorParser    fileSelectorParser;
    private final TargetedContextBuilder contextBuilder;

    /** Set before execute() via setProgressReporter(). */
    private ExecutionProgressReporter reporter  = NoopProgressReporter.INSTANCE;
    private long                       recordId  = 0L;

    @Autowired
    public ExecutionService(
            ExecutionAiService aiService,
            ApplicationContext context,
            ExecutionFixParser parser,
            GitHubClient gitHubClient,
            GitSolveProperties props,
            RepoCache repoCache,
            FileSelectorAiService fileSelectorAiService,
            FileSelectorParser fileSelectorParser) {
        this.aiService             = aiService;
        this.context               = context;
        this.parser                = parser;
        this.gitHubClient          = gitHubClient;
        this.props                 = props;
        this.repoCache             = repoCache;
        this.fileSelectorAiService = fileSelectorAiService;
        this.fileSelectorParser    = fileSelectorParser;
        this.contextBuilder        = new TargetedContextBuilder();
    }

    /**
     * Configures live progress reporting for this execution run.
     * Must be called before {@link #execute} to take effect.
     */
    public void setProgressReporter(long recordId, ExecutionProgressReporter reporter) {
        this.recordId = recordId;
        this.reporter = reporter != null ? reporter : NoopProgressReporter.INSTANCE;
    }

    // ------------------------------------------------------------------ //
    // Public API                                                           //
    // ------------------------------------------------------------------ //

    /**
     * Executes the fix for the given issue using the provided instructions.
     * Never throws — returns a failure {@link ExecutionResult} on any error.
     *
     * @param issue           the GitHub issue to fix
     * @param fixInstructions the pre-generated fix instructions from {@code FixInstructionsService}
     * @param affectedFiles   analysis-identified file hints passed to the file selector on every iteration
     * @return an {@link ExecutionResult} with success/failure, PR URL (on success), and diagnostics
     */
    public ExecutionResult execute(GitIssue issue, String fixInstructions, List<String> affectedFiles) {
        String issueId = issue.repoFullName() + "#" + issue.issueNumber();
        String branch  = "gitsolve/issue-" + issue.issueNumber();
        int    maxIter = props.llm().maxIterationsPerFix();
        String token   = props.github().appToken();

        log.info("[Execution] Starting for {} branch={}", issueId, branch);

        // --- Fork repo ---
        String forkFullName;
        try {
            reporter.step(recordId, ExecutionStep.FORK, StepStatus.RUNNING, issue.repoFullName());
            forkFullName = gitHubClient.forkRepo(issue.repoFullName()).block();
            if (forkFullName == null) {
                reporter.step(recordId, ExecutionStep.FORK, StepStatus.FAILED, "forkRepo returned null");
                return ExecutionResult.failure("forkRepo returned null", "", 0);
            }
            reporter.step(recordId, ExecutionStep.FORK, StepStatus.DONE, forkFullName);
            log.info("[Execution] Fork: {} → {}", issue.repoFullName(), forkFullName);
        } catch (Exception e) {
            reporter.step(recordId, ExecutionStep.FORK, StepStatus.FAILED, e.getMessage());
            log.error("[Execution] {} — fork failed: {}", issueId, e.getMessage());
            return ExecutionResult.failure("Fork failed: " + e.getMessage(), "", 0);
        }

        // --- Ensure local clone ---
        Path hostPath;
        try {
            reporter.step(recordId, ExecutionStep.CLONE, StepStatus.RUNNING, issue.repoFullName());
            hostPath = repoCache.ensureFork(issue.repoFullName());
            reporter.step(recordId, ExecutionStep.CLONE, StepStatus.DONE, hostPath.toString());
            log.info("[Execution] Local clone ready at {}", hostPath);
        } catch (RepoCacheException e) {
            reporter.step(recordId, ExecutionStep.CLONE, StepStatus.FAILED, e.getMessage());
            log.error("[Execution] {} — repo cache failed: {}", issueId, e.getMessage());
            return ExecutionResult.failure("RepoCache failed: " + e.getMessage(), "", 0);
        }

        // --- Docker environment ---
        try (BuildEnvironment env = context.getBean(DockerBuildEnvironment.class)) {

            // Clone from host bind-mount (no network needed)
            env.mountAndClone(hostPath, null);
            reporter.step(recordId, ExecutionStep.DOCKER_SETUP, StepStatus.DONE, null);

            // Create working branch
            BuildOutput branchOut = env.runBuild(
                    "git -C " + "/workspace" + " checkout -b " + branch);
            if (branchOut.buildFailed()) {
                log.warn("[Execution] {} — branch creation failed (exit={}), trying to reuse existing branch",
                        issueId, branchOut.exitCode());
                // Branch may already exist from a previous attempt — try checkout instead
                env.runBuild("git -C /workspace checkout " + branch);
            }
            reporter.step(recordId, ExecutionStep.BRANCH, StepStatus.DONE, branch);

            String buildError = "";
            String lastDiff   = "";
            String analysisHintsStr = affectedFiles != null ? String.join("\n", affectedFiles) : "";

            for (int i = 1; i <= maxIter; i++) {
                log.info("[Execution] {} iteration {}/{}", issueId, i, maxIter);
                String iterLabel = "Iteration " + i + "/" + maxIter;

                // Re-list files each iteration so files written in previous iterations
                // appear in the available set for the selector (avoids false hallucination drops).
                List<String> javaFiles  = env.listJavaFiles("src");
                String       fileListStr = String.join("\n", javaFiles);

                // ── Step 1: File selection ────────────────────────────────
                // Ask the LLM which files it needs — input is paths only, no content.
                List<String> selectedPaths;
                try {
                    String selectorRaw = fileSelectorAiService.selectFiles(fixInstructions, fileListStr, buildError, analysisHintsStr);
                    selectedPaths = fileSelectorParser.parse(selectorRaw, javaFiles);
                } catch (Exception e) {
                    log.warn("[Execution] {} iter {}: file selection failed ({}), using fallback",
                            issueId, i, e.getMessage());
                    selectedPaths = List.of();
                }

                // Fallback: if selection is empty or failed, rank files by keyword overlap with issue
                if (selectedPaths.isEmpty()) {
                    log.info("[Execution] {} iter {}: using keyword fallback file selection", issueId, i);
                    selectedPaths = keywordFallback(javaFiles, issue);
                }

                // ── Step 2: Build targeted context from selected files ────
                String sourceContext = contextBuilder.build(selectedPaths, env);
                log.info("[Execution] {} iter {}: context built from {} file(s): {}",
                        issueId, i, selectedPaths.size(), selectedPaths);

                // LLM call — iteration 1 sends full context; iterations 2+ use the
                // follow-up method to avoid re-sending sourceContext into the memory window.
                reporter.step(recordId, ExecutionStep.LLM_CALL, StepStatus.RUNNING, iterLabel, i);
                String raw;
                try {
                    if (i == 1) {
                        raw = aiService.execute(
                                issue.repoFullName(),
                                issue.issueNumber(),
                                issue.title(),
                                fixInstructions,
                                sourceContext,
                                buildError);
                    } else {
                        raw = aiService.executeFollowUp(
                                issue.repoFullName(),
                                issue.issueNumber(),
                                issue.title(),
                                buildError);
                    }
                    reporter.step(recordId, ExecutionStep.LLM_CALL, StepStatus.DONE, iterLabel, i);
                } catch (Exception e) {
                    reporter.step(recordId, ExecutionStep.LLM_CALL, StepStatus.FAILED, e.getMessage(), i);
                    log.warn("[Execution] {} iteration {}: LLM call failed — {}", issueId, i, e.getMessage());
                    buildError = "LLM error: " + e.getMessage();
                    continue;
                }

                // Parse
                ExecutionFixResponse fix;
                try {
                    fix = parser.parse(raw);
                } catch (ExecutionParseException e) {
                    reporter.step(recordId, ExecutionStep.APPLY_FILES, StepStatus.FAILED, "Parse error: " + e.getMessage(), i);
                    log.warn("[Execution] {} iteration {}: parse failed — {}", issueId, i, e.getMessage());
                    buildError = "Parse error: " + e.getMessage();
                    continue;
                }

                log.info("[Execution] {} iteration {}: applying {} file(s)",
                        issueId, i, fix.files().size());

                // Write all files
                reporter.step(recordId, ExecutionStep.APPLY_FILES, StepStatus.RUNNING,
                        fix.files().size() + " file(s)", i);
                for (FileChange fc : fix.files()) {
                    log.debug("[Execution] writing {}", fc.path());
                    env.writeFile(fc.path(), fc.content());
                }
                reporter.step(recordId, ExecutionStep.APPLY_FILES, StepStatus.DONE,
                        fix.files().size() + " file(s)", i);

                // Run build — find the narrowest sub-module containing all changed files
                // to avoid building the entire monorepo.
                String buildDir = findBuildDir(fix.files());
                BuildOutput wrapperCheck = env.runBuild("test -f /workspace/mvnw && echo yes || echo no");
                String mvnCmd = (wrapperCheck.stdout() != null && wrapperCheck.stdout().strip().equals("yes"))
                        ? "chmod +x /workspace/mvnw && /workspace/mvnw"
                        : "mvn";
                String pomPath = buildDir.isEmpty() ? "/workspace/pom.xml" : "/workspace/" + buildDir + "/pom.xml";
                // Verify the pom exists at that path, fall back to root if not
                BuildOutput pomCheck = env.runBuild("test -f " + pomPath + " && echo yes || echo no");
                if (pomCheck.stdout() == null || !pomCheck.stdout().strip().equals("yes")) {
                    pomPath = "/workspace/pom.xml";
                }
                log.info("[Execution] {} iteration {}: building pom={}", issueId, i, pomPath);
                reporter.step(recordId, ExecutionStep.BUILD, StepStatus.RUNNING, iterLabel, i);
                BuildOutput buildOut = env.runBuild(mvnCmd + " -f " + pomPath + " clean package --no-transfer-progress -DskipTests=false");
                lastDiff = env.getDiff();
                log.info("[Execution] {} iteration {}: build {} (exit={})",
                        issueId, i, buildOut.buildPassed() ? "PASSED" : "FAILED", buildOut.exitCode());

                if (buildOut.buildPassed()) {
                    reporter.step(recordId, ExecutionStep.BUILD, StepStatus.DONE, iterLabel, i);
                    // Commit — write message to a temp file to avoid shell injection
                    env.runBuild("git -C /workspace config user.email 'gitsolve-bot@gitsolve.ai'");
                    env.runBuild("git -C /workspace config user.name 'GitSolve Bot'");
                    env.writeFile(".gitsolve_commit_msg", fix.commitMessage());
                    BuildOutput commitOut = env.runBuild(
                            "git -C /workspace add -A && git -C /workspace commit -F /workspace/.gitsolve_commit_msg");
                    env.runBuild("rm -f /workspace/.gitsolve_commit_msg");
                    if (commitOut.buildFailed()) {
                        log.warn("[Execution] {} commit failed (exit={}): {}",
                                issueId, commitOut.exitCode(), commitOut.stderr());
                        reporter.step(recordId, ExecutionStep.PUSH, StepStatus.FAILED, "Commit failed");
                        return ExecutionResult.failure(
                                "Commit failed: " + commitOut.stderr(), lastDiff, i);
                    }

                    // Push — inject token into remote URL (NEVER log the raw command)
                    reporter.step(recordId, ExecutionStep.PUSH, StepStatus.RUNNING, forkFullName);
                    String pushRemoteCmd = "git -C /workspace remote set-url origin https://x-access-token:"
                            + token + "@github.com/" + forkFullName + ".git";
                    env.runBuild(pushRemoteCmd);
                    log.debug("[Execution] {} set remote URL: {}", issueId,
                            redactToken("https://x-access-token:" + token + "@github.com/" + forkFullName));

                    BuildOutput pushOut = env.runBuild(
                            "git -C /workspace push origin " + branch + " --force");
                    if (pushOut.buildFailed()) {
                        reporter.step(recordId, ExecutionStep.PUSH, StepStatus.FAILED, pushOut.stderr());
                        log.warn("[Execution] {} push failed (exit={}): {}",
                                issueId, pushOut.exitCode(), pushOut.stderr());
                        return ExecutionResult.failure(
                                "Push failed: " + pushOut.stderr(), lastDiff, i);
                    }
                    reporter.step(recordId, ExecutionStep.PUSH, StepStatus.DONE, forkFullName);
                    log.info("[Execution] {} pushed branch {} to fork {}", issueId, branch, forkFullName);

                    // Open PR
                    reporter.step(recordId, ExecutionStep.PR_OPEN, StepStatus.RUNNING, null);
                    String prBody = buildPrBody(fix.prBody(), issue.issueNumber());
                    String prUrl;
                    try {
                        prUrl = gitHubClient.createGitHubPr(
                                issue.repoFullName(),
                                forkFullName,
                                branch,
                                fix.prTitle() != null ? fix.prTitle() : fix.commitMessage(),
                                prBody
                        ).block();
                    } catch (Exception e) {
                        reporter.step(recordId, ExecutionStep.PR_OPEN, StepStatus.FAILED, e.getMessage());
                        log.error("[Execution] {} PR creation failed: {}", issueId, e.getMessage());
                        return ExecutionResult.failure(
                                "PR creation failed: " + e.getMessage(), lastDiff, i);
                    }

                    reporter.step(recordId, ExecutionStep.PR_OPEN, StepStatus.DONE, prUrl);
                    log.info("[Execution] {} PR submitted: {}", issueId, prUrl);
                    return ExecutionResult.success(prUrl, lastDiff, i);
                }

                reporter.step(recordId, ExecutionStep.BUILD, StepStatus.FAILED,
                        "exit=" + buildOut.exitCode() + " (iteration " + i + ")", i);
                buildError = buildOut.extractStackTrace();
                log.warn("[Execution] {} iter {}: build error fed to LLM ({}chars): {}",
                        issueId, i, buildError.length(),
                        buildError.length() > 500 ? buildError.substring(buildError.length() - 500) : buildError);
            }

            log.warn("[Execution] {} exhausted {} iterations without passing build", issueId, maxIter);
            reporter.step(recordId, ExecutionStep.BUILD, StepStatus.FAILED,
                    "Exhausted " + maxIter + " iterations");
            return ExecutionResult.failure(
                    "Exhausted " + maxIter + " iterations without a passing build", lastDiff, maxIter);

        } catch (BuildEnvironmentException e) {
            reporter.step(recordId, ExecutionStep.DOCKER_SETUP, StepStatus.FAILED, e.getMessage());
            log.error("[Execution] {} Docker error: {}", issueId, e.getMessage(), e);
            return ExecutionResult.failure("Docker error: " + e.getMessage(), "", 0);
        }
    }

    // ------------------------------------------------------------------ //
    // Helpers                                                              //
    // ------------------------------------------------------------------ //

    /**
     * Scores each path by the number of keyword tokens (from the issue title+body) that
     * appear as substrings in the lower-cased path, then returns the top
     * {@link FileSelectorParser#MAX_PATHS} paths by descending score.
     *
     * <p>Tokens shorter than 3 characters are ignored to avoid noise from
     * stop-words like "in", "at", "to", etc.
     *
     * @param paths list of candidate paths (may be empty or null)
     * @param issue the issue providing title+body keywords
     * @return ranked sublist of up to MAX_PATHS paths
     */
    static List<String> keywordFallback(List<String> paths, GitIssue issue) {
        if (paths == null || paths.isEmpty()) return List.of();

        // Tokenize issue title + body: split on non-word chars, lowercase, min length 3
        String text  = (issue.title() != null ? issue.title() : "")
                + " " + (issue.body() != null ? issue.body() : "");
        String[] tokens = text.toLowerCase().split("\\W+");

        List<String> filtered = new ArrayList<>();
        for (String t : tokens) {
            if (t.length() >= 3) filtered.add(t);
        }

        // Score each path
        List<String> ranked = new ArrayList<>(paths);
        ranked.sort((a, b) -> {
            int scoreA = score(a.toLowerCase(), filtered);
            int scoreB = score(b.toLowerCase(), filtered);
            return Integer.compare(scoreB, scoreA); // descending
        });

        return ranked.subList(0, Math.min(FileSelectorParser.MAX_PATHS, ranked.size()));
    }

    /** Counts how many tokens from {@code tokens} appear as substrings of {@code lowerPath}. */
    private static int score(String lowerPath, List<String> tokens) {
        int count = 0;
        for (String token : tokens) {
            if (lowerPath.contains(token)) count++;
        }
        return count;
    }

    /**
     * Finds the deepest common parent directory of all changed files.
     * e.g. files under "dubbo-samples/dubbo-samples-helloworld/..." → "dubbo-samples/dubbo-samples-helloworld"
     * Returns empty string if files span the root.
     */
    static String findBuildDir(List<FileChange> files) {
        if (files == null || files.isEmpty()) return "";
        // Split each path into segments and find common prefix
        String[] firstParts = files.get(0).path().split("/");
        int commonDepth = firstParts.length - 1; // exclude filename
        for (FileChange fc : files) {
            String[] parts = fc.path().split("/");
            int depth = Math.min(commonDepth, parts.length - 1);
            for (int i = 0; i < depth; i++) {
                if (!firstParts[i].equals(parts[i])) {
                    commonDepth = i;
                    break;
                }
            }
        }
        if (commonDepth <= 0) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < commonDepth; i++) {
            if (sb.length() > 0) sb.append('/');
            sb.append(firstParts[i]);
        }
        return sb.toString();
    }

    /**
     * Ensures the PR body contains a "Closes #N" reference.
     * If the LLM-generated body already contains it, returns as-is.
     * Otherwise appends it. Package-private for testing.
     */
    static String buildPrBody(String llmBody, int issueNumber) {
        String closesRef = "Closes #" + issueNumber;
        if (llmBody != null && llmBody.contains(closesRef)) {
            return llmBody;
        }
        String base = (llmBody != null && !llmBody.isBlank()) ? llmBody.strip() : "";
        return base.isEmpty() ? closesRef : base + "\n\n" + closesRef;
    }

    /**
     * Replaces the token in a git remote URL with [REDACTED] for safe logging.
     * Matches pattern: https://x-access-token:{token}@
     */
    static String redactToken(String input) {
        if (input == null) return null;
        return TOKEN_PATTERN.matcher(input).replaceAll("https://x-access-token:[REDACTED]@");
    }
}
