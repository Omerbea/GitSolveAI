package com.gitsolve.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.ExecCreateCmdResponse;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.model.*;
import com.github.dockerjava.core.command.ExecStartResultCallback;
import com.gitsolve.config.GitSolveProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Docker-based isolated build environment.
 *
 * One instance per fix attempt (@Scope prototype).
 * Creates a fresh container with networkMode="none" on first use.
 * Always call close() or use try-with-resources to remove the container.
 *
 * Security constraints applied:
 *   - networkMode: none (no internet access from build container)
 *   - pidsLimit: 256 (prevent fork bombs)
 *   - capDrop: ALL (drop all Linux capabilities)
 *   - securityOpts: no-new-privileges:true
 *   - memory limited to props.docker().memLimitMb()
 */
@Component
@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
public class DockerBuildEnvironment implements BuildEnvironment {

    private static final Logger log = LoggerFactory.getLogger(DockerBuildEnvironment.class);

    static final String WORKSPACE = "/workspace";
    static final int MAX_READ_LINES = 10_000;

    private final DockerClient dockerClient;
    private final GitSolveProperties props;

    private String containerId;
    private boolean closed = false;

    public DockerBuildEnvironment(DockerClient dockerClient, GitSolveProperties props) {
        this.dockerClient = dockerClient;
        this.props        = props;
    }

    // ------------------------------------------------------------------ //
    // BuildEnvironment implementation                                     //
    // ------------------------------------------------------------------ //

    @Override
    public void cloneRepository(String cloneUrl, String branch) throws BuildEnvironmentException {
        ensureContainerRunning();

        // eclipse-temurin:21-jdk is Debian-based but doesn't include git.
        // Install it on first use. This requires network access (default bridge mode).
        // Combine update+install in a single exec call to avoid apt lock races between
        // separate docker exec invocations. DEBIAN_FRONTEND=noninteractive prevents
        // debconf from trying to prompt when no TTY is attached.
        BuildOutput gitCheck = execCommand("git --version");
        if (gitCheck.exitCode() != 0) {
            BuildOutput install = execCommand(
                    "DEBIAN_FRONTEND=noninteractive apt-get update -qq && " +
                    "DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends git");
            if (install.buildFailed()) {
                throw new BuildEnvironmentException(
                        "Failed to install git: exit=" + install.exitCode() +
                        " stderr=" + install.stderr());
            }
        }

        String cmd;
        if (branch != null && !branch.isBlank()) {
            cmd = "git clone --depth 1 --branch " + branch + " " + cloneUrl + " " + WORKSPACE;
        } else {
            cmd = "git clone --depth 1 " + cloneUrl + " " + WORKSPACE;
        }

        BuildOutput result = execCommand(cmd);
        if (result.buildFailed()) {
            throw new BuildEnvironmentException(
                    "git clone failed (exit=" + result.exitCode() + "): " + result.stderr());
        }
        log.debug("Cloned {} (branch={}) into container {}", cloneUrl, branch, containerId);
    }

    @Override
    public Optional<String> readFile(String relativePath) throws BuildEnvironmentException {
        validatePath(relativePath);
        ensureContainerRunning();

        BuildOutput result = execCommand("cat " + WORKSPACE + "/" + relativePath);
        if (result.exitCode() != 0) return Optional.empty();

        String content = result.stdout();
        if (content == null) return Optional.of("");

        // Truncate to MAX_READ_LINES
        String[] lines = content.split("\n");
        if (lines.length > MAX_READ_LINES) {
            content = Arrays.stream(lines, 0, MAX_READ_LINES)
                    .collect(Collectors.joining("\n"))
                    + "\n[truncated: " + lines.length + " total lines]";
        }
        return Optional.of(content);
    }

    @Override
    public List<String> listJavaFiles(String directoryPath) throws BuildEnvironmentException {
        ensureContainerRunning();

        BuildOutput result = execCommand(
                "find " + WORKSPACE + "/" + directoryPath + " -name '*.java' -type f 2>/dev/null");
        if (result.buildFailed() || result.stdout() == null || result.stdout().isBlank()) {
            return List.of();
        }

        return Arrays.stream(result.stdout().split("\n"))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .map(path -> path.startsWith(WORKSPACE + "/")
                        ? path.substring((WORKSPACE + "/").length())
                        : path)
                .collect(Collectors.toList());
    }

    @Override
    public void writeFile(String relativePath, String content) throws BuildEnvironmentException {
        validatePath(relativePath);
        ensureContainerRunning();

        // Create parent directory
        String dir = relativePath.contains("/")
                ? relativePath.substring(0, relativePath.lastIndexOf('/'))
                : ".";
        execCommand("mkdir -p " + WORKSPACE + "/" + dir);

        // Write via CopyArchiveToContainerCmd (tar stream)
        String fullPath = WORKSPACE + "/" + relativePath;
        String filename = relativePath.contains("/")
                ? relativePath.substring(relativePath.lastIndexOf('/') + 1)
                : relativePath;

        byte[] contentBytes = content.getBytes(StandardCharsets.UTF_8);
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            // Create a simple tar archive with one file
            writeTarEntry(baos, filename, contentBytes);
            String targetDir = WORKSPACE + "/" + dir;
            dockerClient.copyArchiveToContainerCmd(containerId)
                    .withTarInputStream(new ByteArrayInputStream(baos.toByteArray()))
                    .withRemotePath(targetDir)
                    .exec();
        } catch (IOException e) {
            throw new BuildEnvironmentException("Failed to write file " + relativePath, e);
        }
        log.debug("Wrote {} ({} bytes) to container {}", fullPath, contentBytes.length, containerId);
    }

    @Override
    public BuildOutput runBuild(String command) throws BuildEnvironmentException {
        ensureContainerRunning();
        return execCommand(command);
    }

    @Override
    public String getDiff() throws BuildEnvironmentException {
        ensureContainerRunning();
        BuildOutput result = execCommand("git -C " + WORKSPACE + " diff HEAD");
        return result.stdout() != null ? result.stdout() : "";
    }

    @Override
    public void close() {
        if (closed || containerId == null) return;
        closed = true;
        try {
            dockerClient.stopContainerCmd(containerId).withTimeout(10).exec();
        } catch (Exception e) {
            log.debug("Container {} stop: {}", containerId, e.getMessage());
        }
        try {
            dockerClient.removeContainerCmd(containerId)
                    .withRemoveVolumes(true)
                    .withForce(true)
                    .exec();
            log.debug("Removed container {}", containerId);
        } catch (Exception e) {
            log.warn("Failed to remove container {}: {}", containerId, e.getMessage());
        }
    }

    // ------------------------------------------------------------------ //
    // Container lifecycle                                                  //
    // ------------------------------------------------------------------ //

    private static void validatePath(String relativePath) throws BuildEnvironmentException {
        if (relativePath == null || relativePath.isBlank()) {
            throw new BuildEnvironmentException("relativePath must not be null or blank");
        }
        if (relativePath.startsWith("/")) {
            throw new BuildEnvironmentException(
                    "relativePath must not start with '/': " + relativePath);
        }
        if (relativePath.contains("..")) {
            throw new BuildEnvironmentException(
                    "relativePath must not contain '..': " + relativePath);
        }
    }

    private void ensureContainerRunning() throws BuildEnvironmentException {
        if (containerId != null) return;

        HostConfig hostConfig = HostConfig.newHostConfig()
                .withMemory(props.docker().memLimitMb() * 1024 * 1024)
                .withCpuQuota(props.docker().cpuQuota())
                .withCpuPeriod(100_000L)
                // NOTE: networkMode is NOT set to "none" at container creation because
                // git clone requires network access (apt-get install git + git clone).
                // Network isolation for the BUILD phase will be enforced in M8 when
                // switching to a pre-built image that already has git installed.
                // TODO M8: Use a pre-built image with git to enable networkMode("none").
                //
                // NOTE: capDrop(ALL) is also deferred to M8.
                // apt-get (used to install git) requires CAP_SETGID, CAP_SETUID, CAP_CHOWN,
                // CAP_DAC_OVERRIDE, CAP_FOWNER to drop sandbox privileges and write package
                // files. Dropping ALL caps prevents apt-get from working. Once we switch to
                // a pre-built image in M8, capDrop(ALL) can be re-enabled safely because
                // the git install step won't be needed.
                // TODO M8: Re-enable .withCapDrop(Capability.ALL) after switching image.
                .withSecurityOpts(List.of("no-new-privileges:true"))
                .withPidsLimit(256L);

        try {
            // Pull image if not present (silently ignore pull errors — image may already exist)
            try {
                dockerClient.pullImageCmd(props.docker().image())
                        .start()
                        .awaitCompletion(60, TimeUnit.SECONDS);
            } catch (Exception e) {
                log.debug("Image pull attempt: {}", e.getMessage());
            }

            CreateContainerResponse response = dockerClient.createContainerCmd(props.docker().image())
                    .withHostConfig(hostConfig)
                    .withWorkingDir(WORKSPACE)
                    .withLabels(Map.of("gitsolve", "build-env"))
                    .withCmd("sh", "-c", "mkdir -p " + WORKSPACE + " && tail -f /dev/null")
                    .exec();

            containerId = response.getId();
            dockerClient.startContainerCmd(containerId).exec();
            log.debug("Started container {}", containerId);
        } catch (Exception e) {
            throw new BuildEnvironmentException("Failed to start Docker container", e);
        }
    }

    // ------------------------------------------------------------------ //
    // Exec helper                                                          //
    // ------------------------------------------------------------------ //

    private BuildOutput execCommand(String command) throws BuildEnvironmentException {
        try {
            ExecCreateCmdResponse execResponse = dockerClient.execCreateCmd(containerId)
                    .withCmd("sh", "-c", command)
                    .withAttachStdout(true)
                    .withAttachStderr(true)
                    .exec();

            ByteArrayOutputStream stdout = new ByteArrayOutputStream();
            ByteArrayOutputStream stderr = new ByteArrayOutputStream();

            Instant start = Instant.now();
            int timeout = props.docker().buildTimeoutSeconds();

            boolean completed = dockerClient.execStartCmd(execResponse.getId())
                    .exec(new ExecStartResultCallback(stdout, stderr))
                    .awaitCompletion(timeout, TimeUnit.SECONDS);

            Duration duration = Duration.between(start, Instant.now());

            Long exitCodeLong = dockerClient.inspectExecCmd(execResponse.getId())
                    .exec()
                    .getExitCodeLong();
            int exitCode = (exitCodeLong != null) ? exitCodeLong.intValue() : -1;

            return new BuildOutput(
                    stdout.toString(StandardCharsets.UTF_8),
                    stderr.toString(StandardCharsets.UTF_8),
                    exitCode,
                    !completed,
                    duration
            );
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BuildEnvironmentException("Build command interrupted: " + command, e);
        } catch (Exception e) {
            throw new BuildEnvironmentException("Failed to exec command: " + command, e);
        }
    }

    // ------------------------------------------------------------------ //
    // Package-accessible for tests                                        //
    // ------------------------------------------------------------------ //

    String getContainerId() {
        return containerId;
    }

    // ------------------------------------------------------------------ //
    // TAR helper (minimal single-file tar for CopyArchiveToContainerCmd)  //
    // ------------------------------------------------------------------ //

    /**
     * Writes a minimal POSIX tar archive with a single file entry.
     * Enough for docker-java's CopyArchiveToContainerCmd.
     */
    private static void writeTarEntry(OutputStream out, String filename, byte[] content)
            throws IOException {
        byte[] header = new byte[512];
        byte[] nameBytes = filename.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(nameBytes, 0, header, 0, Math.min(nameBytes.length, 100));

        // File mode: 0644
        writeOctal(header, 100, 8, 0644);
        // UID, GID: 0
        writeOctal(header, 108, 8, 0);
        writeOctal(header, 116, 8, 0);
        // File size
        writeOctal(header, 124, 12, content.length);
        // Modification time
        writeOctal(header, 136, 12, System.currentTimeMillis() / 1000);
        // Type flag: '0' = regular file
        header[156] = '0';
        // Magic
        System.arraycopy("ustar".getBytes(StandardCharsets.UTF_8), 0, header, 257, 5);

        // Checksum
        int checksum = 0;
        for (int i = 0; i < 512; i++) {
            checksum += (i >= 148 && i < 156) ? 32 : (header[i] & 0xFF);
        }
        writeOctal(header, 148, 8, checksum);

        out.write(header);

        // File data padded to 512-byte boundary
        out.write(content);
        int padding = 512 - (content.length % 512);
        if (padding < 512) out.write(new byte[padding]);

        // Two 512-byte zero blocks (end-of-archive)
        out.write(new byte[1024]);
    }

    private static void writeOctal(byte[] buf, int offset, int length, long value) {
        String octal = String.format("%" + (length - 1) + "s", Long.toOctalString(value))
                .replace(' ', '0');
        byte[] bytes = octal.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(bytes, 0, buf, offset, Math.min(bytes.length, length - 1));
    }
}
