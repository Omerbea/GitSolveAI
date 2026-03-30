package com.gitsolve.repocache;

import com.gitsolve.repocache.RepoCache.GitRunner;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for RepoCache.
 * No real git operations — commands are captured via the testable GitRunner constructor.
 */
@Tag("unit")
class RepoCacheTest {

    @TempDir
    Path tempDir;

    // ------------------------------------------------------------------ //
    // Slug format                                                          //
    // ------------------------------------------------------------------ //

    @Test
    void slugFormat_slashReplacedWithDoubleUnderscore() {
        assertThat(RepoCache.toSlug("apache/commons-lang")).isEqualTo("apache__commons-lang");
        assertThat(RepoCache.toSlug("javaparser/javaparser")).isEqualTo("javaparser__javaparser");
    }

    // ------------------------------------------------------------------ //
    // Cache miss → clone                                                   //
    // ------------------------------------------------------------------ //

    @Test
    void ensureFork_missingDir_runsGitClone() throws Exception {
        List<List<String>> captured = Collections.synchronizedList(new ArrayList<>());
        GitRunner recorder = (args, workDir) -> {
            captured.add(new ArrayList<>(args));
            // Simulate clone by creating the .git dir so the next call sees a hit
            if (args.contains("clone")) {
                Path target = Path.of(args.get(args.size() - 1));
                try {
                    Files.createDirectories(target.resolve(".git"));
                } catch (IOException e) {
                    throw new RepoCacheException("mkdir failed in test", e);
                }
            }
        };

        RepoCache cache = new RepoCache(tempDir, true, recorder);
        Path result = cache.ensureFork("apache/commons-lang");

        assertThat(result).isEqualTo(tempDir.resolve("apache__commons-lang"));

        // Exactly one command issued: git clone
        assertThat(captured).hasSize(1);
        assertThat(captured.get(0)).contains("clone");
        assertThat(captured.get(0)).anyMatch(arg -> arg.contains("apache/commons-lang"));
    }

    // ------------------------------------------------------------------ //
    // Cache hit → fetch + pull                                             //
    // ------------------------------------------------------------------ //

    @Test
    void ensureFork_existingGitDir_runsFetchAndPull() throws Exception {
        // Pre-create the .git dir to simulate an existing clone
        Path repoDir = tempDir.resolve("apache__commons-lang");
        Files.createDirectories(repoDir.resolve(".git"));

        List<List<String>> captured = Collections.synchronizedList(new ArrayList<>());
        GitRunner recorder = (args, workDir) -> captured.add(new ArrayList<>(args));

        RepoCache cache = new RepoCache(tempDir, true, recorder);
        Path result = cache.ensureFork("apache/commons-lang");

        assertThat(result).isEqualTo(repoDir);
        // Two commands: fetch, then pull
        assertThat(captured).hasSize(2);
        assertThat(captured.get(0)).contains("fetch");
        assertThat(captured.get(1)).contains("pull");
        // No clone issued
        assertThat(captured).noneMatch(cmd -> cmd.contains("clone"));
    }

    // ------------------------------------------------------------------ //
    // Cache hit with updateExisting=false → no git commands               //
    // ------------------------------------------------------------------ //

    @Test
    void ensureFork_existingGitDir_updateDisabled_noCommands() throws Exception {
        Path repoDir = tempDir.resolve("javaparser__javaparser");
        Files.createDirectories(repoDir.resolve(".git"));

        List<List<String>> captured = Collections.synchronizedList(new ArrayList<>());
        GitRunner recorder = (args, workDir) -> captured.add(new ArrayList<>(args));

        RepoCache cache = new RepoCache(tempDir, false, recorder);
        cache.ensureFork("javaparser/javaparser");

        assertThat(captured).isEmpty();
    }

    // ------------------------------------------------------------------ //
    // Fetch/pull fails → WARN logged, path still returned                 //
    // ------------------------------------------------------------------ //

    @Test
    void ensureFork_fetchFails_returnsPathWithoutThrowing() throws Exception {
        Path repoDir = tempDir.resolve("diffplug__spotless");
        Files.createDirectories(repoDir.resolve(".git"));

        // Fail on fetch; pull would also fail but we don't even check
        GitRunner failing = (args, workDir) -> {
            if (args.contains("fetch")) throw new RepoCacheException("network error");
        };

        RepoCache cache = new RepoCache(tempDir, true, failing);
        // Must NOT throw — update failures are non-fatal
        Path result = cache.ensureFork("diffplug/spotless");
        assertThat(result).isEqualTo(repoDir);
    }

    // ------------------------------------------------------------------ //
    // Clone fails → throws RepoCacheException                             //
    // ------------------------------------------------------------------ //

    @Test
    void ensureFork_cloneFails_throwsRepoCacheException() {
        GitRunner failing = (args, workDir) -> {
            throw new RepoCacheException("clone failed: repository not found");
        };

        RepoCache cache = new RepoCache(tempDir, true, failing);
        assertThatThrownBy(() -> cache.ensureFork("nonexistent/repo"))
                .isInstanceOf(RepoCacheException.class)
                .hasMessageContaining("clone failed");
    }

    // ------------------------------------------------------------------ //
    // Concurrent calls for same slug → only one clone                     //
    // ------------------------------------------------------------------ //

    @Test
    void ensureFork_concurrentCallsSameSlug_onlyOneClone() throws Exception {
        AtomicInteger cloneCount = new AtomicInteger(0);
        CyclicBarrier barrier   = new CyclicBarrier(2);

        GitRunner recorder = (args, workDir) -> {
            if (args.contains("clone")) {
                cloneCount.incrementAndGet();
                try {
                    // Brief sleep so the second thread is sure to arrive before clone "finishes"
                    Thread.sleep(20);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                // Create .git so the next ensureFork call (in the same thread) sees a hit
                Path target = Path.of(args.get(args.size() - 1));
                try {
                    Files.createDirectories(target.resolve(".git"));
                } catch (IOException ex) {
                    throw new RepoCacheException("mkdir failed", ex);
                }
            }
            // fetch/pull: no-op
        };

        RepoCache cache = new RepoCache(tempDir, true, recorder);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            var f1 = pool.submit(() -> {
                barrier.await();
                return cache.ensureFork("google/guava");
            });
            var f2 = pool.submit(() -> {
                barrier.await();
                return cache.ensureFork("google/guava");
            });

            f1.get(10, TimeUnit.SECONDS);
            f2.get(10, TimeUnit.SECONDS);
        } finally {
            pool.shutdown();
        }

        // Lock serialises the two calls — clone runs exactly once
        assertThat(cloneCount.get()).isEqualTo(1);
    }
}
