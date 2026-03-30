package com.gitsolve.repocache;

import com.gitsolve.config.GitSolveProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Local repository cache — maintains a directory of cloned repos on the host filesystem.
 *
 * <p>Layout:
 * <pre>
 *   {baseDir}/
 *     apache__commons-lang/   ← owner__repo (double underscore avoids collision with hyphens)
 *     javaparser__javaparser/
 * </pre>
 *
 * <p>On a cache miss: {@code git clone --no-single-branch} into {baseDir}/{slug}.
 * On a cache hit: {@code git fetch --all --prune} then {@code git pull --ff-only}.
 * If fetch/pull fails the cached clone is still usable; a WARN is logged and we continue.
 *
 * <p>Concurrency: a per-slug {@link ReentrantLock} ensures at most one git operation runs
 * per repo at a time — concurrent callers for the same slug wait on the lock.
 */
@Component
public class RepoCache {

    private static final Logger log = LoggerFactory.getLogger(RepoCache.class);

    /** Injected in tests to capture or replace git commands. */
    @FunctionalInterface
    public interface GitRunner {
        /**
         * Run a git command.
         *
         * @param args     the full argument list (e.g. ["git", "clone", ...])
         * @param workDir  working directory for the process; may be null for no specific dir
         * @throws RepoCacheException if the command exits non-zero or the process fails to start
         */
        void run(List<String> args, Path workDir) throws RepoCacheException;
    }

    private final Path    baseDir;
    private final boolean updateExisting;
    private final GitRunner gitRunner;

    /** Per-slug locks — created lazily. Never removed (slug set is bounded by repos scanned). */
    private final ConcurrentHashMap<String, ReentrantLock> slugLocks = new ConcurrentHashMap<>();

    // ------------------------------------------------------------------ //
    // Constructors                                                         //
    // ------------------------------------------------------------------ //

    /** Production constructor — Spring injects GitSolveProperties. */
    @org.springframework.beans.factory.annotation.Autowired
    public RepoCache(GitSolveProperties props) {
        this(
            Paths.get(props.repoCache() != null
                ? props.repoCache().effectiveBaseDir()
                : System.getProperty("user.home") + "/.gitsolve-repos"),
            props.repoCache() == null || props.repoCache().updateExisting(),
            RepoCache::defaultGitRunner
        );
    }

    /** Test-friendly constructor — caller supplies baseDir, updateExisting flag, and runner. */
    RepoCache(Path baseDir, boolean updateExisting, GitRunner gitRunner) {
        this.baseDir        = baseDir;
        this.updateExisting = updateExisting;
        this.gitRunner      = gitRunner;
    }

    // ------------------------------------------------------------------ //
    // Public API                                                           //
    // ------------------------------------------------------------------ //

    /**
     * Returns the local path to a ready-to-use clone of {@code repoFullName}.
     *
     * <p>Slug: {@code "apache/commons-lang"} → {@code "apache__commons-lang"}.
     * Acquires a per-slug lock before any filesystem or git operation.
     *
     * @param repoFullName "owner/repo" format
     * @return absolute {@link Path} to the cloned working directory
     * @throws RepoCacheException if a git operation fails unrecoverably
     */
    public Path ensureFork(String repoFullName) throws RepoCacheException {
        String slug    = toSlug(repoFullName);
        Path   repoDir = baseDir.resolve(slug);

        ReentrantLock lock = slugLocks.computeIfAbsent(slug, k -> new ReentrantLock());
        lock.lock();
        try {
            return ensureForked(repoFullName, slug, repoDir);
        } finally {
            lock.unlock();
        }
    }

    // ------------------------------------------------------------------ //
    // Internal logic                                                       //
    // ------------------------------------------------------------------ //

    private Path ensureForked(String repoFullName, String slug, Path repoDir)
            throws RepoCacheException {

        boolean hasDotGit = Files.isDirectory(repoDir.resolve(".git"));

        if (hasDotGit) {
            if (updateExisting) {
                updateExisting(repoFullName, slug, repoDir);
            } else {
                log.info("[RepoCache] hit (update disabled) slug={}", slug);
            }
        } else {
            cloneFresh(repoFullName, slug, repoDir);
        }

        return repoDir;
    }

    private void cloneFresh(String repoFullName, String slug, Path repoDir)
            throws RepoCacheException {

        log.info("[RepoCache] miss — cloning slug={}", slug);
        long start = epochMs();
        try {
            Files.createDirectories(repoDir);
        } catch (IOException e) {
            throw new RepoCacheException("Failed to create cache dir: " + repoDir, e);
        }

        String cloneUrl = "https://github.com/" + repoFullName;
        gitRunner.run(
            List.of("git", "clone", "--no-single-branch", cloneUrl, repoDir.toString()),
            baseDir
        );
        log.info("[RepoCache] cloned slug={} in {}ms", slug, epochMs() - start);
    }

    private void updateExisting(String repoFullName, String slug, Path repoDir) {
        log.info("[RepoCache] hit — updating slug={}", slug);
        long start = epochMs();
        try {
            gitRunner.run(List.of("git", "-C", repoDir.toString(), "fetch", "--all", "--prune"), null);
            gitRunner.run(List.of("git", "-C", repoDir.toString(), "pull", "--ff-only"), null);
            log.info("[RepoCache] updated slug={} in {}ms", slug, epochMs() - start);
        } catch (RepoCacheException e) {
            // Update failure is non-fatal — the cached clone is still usable for reading.
            // Log WARN but do not rethrow.
            log.warn("[RepoCache] fetch/pull failed for slug={} ({}), continuing with stale clone",
                    slug, e.getMessage());
        }
    }

    // ------------------------------------------------------------------ //
    // Helpers                                                              //
    // ------------------------------------------------------------------ //

    /** Converts "owner/repo" to "owner__repo" (double underscore avoids collisions). */
    static String toSlug(String repoFullName) {
        return repoFullName.replace("/", "__");
    }

    private static long epochMs() {
        return Instant.now().toEpochMilli();
    }

    /**
     * Default git runner — uses {@link ProcessBuilder} to execute git on the host.
     * Stdout and stderr are merged and logged at TRACE level; only exit code is checked.
     */
    private static void defaultGitRunner(List<String> args, Path workDir)
            throws RepoCacheException {
        try {
            ProcessBuilder pb = new ProcessBuilder(args)
                    .redirectErrorStream(true);
            if (workDir != null) pb.directory(workDir.toFile());

            Process process = pb.start();
            // Drain stdout to prevent buffer deadlock
            byte[] out = process.getInputStream().readAllBytes();
            int exit = process.waitFor();

            if (exit != 0) {
                String output = new String(out).strip();
                throw new RepoCacheException(
                    "git command failed (exit=" + exit + "): " +
                    String.join(" ", args) +
                    (output.isEmpty() ? "" : "\n" + output));
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new RepoCacheException("Failed to run git: " + String.join(" ", args), e);
        }
    }
}
