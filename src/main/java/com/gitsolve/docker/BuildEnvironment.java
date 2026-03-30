package com.gitsolve.docker;

import java.util.List;
import java.util.Optional;

/**
 * Contract for an isolated build environment.
 * Implementations are expected to be AutoCloseable — always use try-with-resources.
 *
 * Implementations must:
 *   - Validate that relativePath arguments do not contain '..' or leading '/'
 *   - Run builds with a timeout matching GitSolveProperties.docker().buildTimeoutSeconds()
 *   - Ensure the container has networkMode="none" (no internet access)
 */
public interface BuildEnvironment extends AutoCloseable {

    /**
     * Clones the repository into the container workspace.
     * Uses --depth 1 for shallow clone.
     *
     * @param cloneUrl  HTTPS clone URL
     * @param branch    branch/tag to checkout, or null for default branch
     * @throws BuildEnvironmentException if the git clone fails
     */
    void cloneRepository(String cloneUrl, String branch) throws BuildEnvironmentException;

    /**
     * Mounts a host-side git repository directory as a read-only volume and clones from it
     * into the container workspace using a local file:// clone.
     *
     * <p>This avoids any outbound network calls for the clone step — the container only
     * needs the host path to be accessible.  The container must be (re)created with the
     * bind-mount active, so calling this method will stop/remove any existing container
     * and start a fresh one with the additional volume.
     *
     * @param hostRepoPath absolute path on the host to the cloned repo directory
     * @param branch       branch to checkout, or null for the default branch
     * @throws BuildEnvironmentException if the bind-mount or clone fails
     */
    void mountAndClone(java.nio.file.Path hostRepoPath, String branch) throws BuildEnvironmentException;

    /**
     * Reads a file from the container workspace.
     * Returns empty if the file does not exist.
     * Files larger than 10,000 lines are truncated.
     *
     * @param relativePath path relative to workspace root (must not contain '..')
     */
    Optional<String> readFile(String relativePath) throws BuildEnvironmentException;

    /**
     * Lists all .java files under the given directory path (relative to workspace root).
     *
     * @param directoryPath relative path to search under
     * @return list of relative file paths
     */
    List<String> listJavaFiles(String directoryPath) throws BuildEnvironmentException;

    /**
     * Writes content to a file in the container workspace, creating parent directories as needed.
     *
     * @param relativePath path relative to workspace root (must not contain '..' or start with '/')
     * @param content      file content to write
     */
    void writeFile(String relativePath, String content) throws BuildEnvironmentException;

    /**
     * Runs a shell command in the workspace directory with the configured timeout.
     *
     * @param command the command to run (passed to sh -c)
     * @return BuildOutput with exit code, stdout, stderr, and duration
     */
    BuildOutput runBuild(String command) throws BuildEnvironmentException;

    /**
     * Returns the unified diff of all changes against the original clone.
     */
    String getDiff() throws BuildEnvironmentException;

    /**
     * Stops and removes the container. Idempotent — safe to call multiple times.
     */
    @Override
    void close();
}
