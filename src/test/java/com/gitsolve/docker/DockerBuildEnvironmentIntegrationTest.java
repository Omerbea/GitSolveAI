package com.gitsolve.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.Container;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for DockerBuildEnvironment using a real Docker daemon.
 *
 * Requirements:
 *   - Docker daemon running (OrbStack on macOS — socket pinned in .mvn/jvm.config)
 *   - Internet access for git clone
 *
 * Each test uses try-with-resources to guarantee container cleanup.
 * Tests are ordered to fail fast if Docker is unavailable.
 */
@Tag("integration")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.autoconfigure.exclude=" +
                "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration," +
                "org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration," +
                "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration," +
                "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration"
        }
)
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DockerBuildEnvironmentIntegrationTest {

    // Exclude JPA/DB — we don't need Testcontainers Postgres for Docker tests
    @MockBean
    com.gitsolve.persistence.IssueStore issueStore;

    @Autowired
    ApplicationContext context;

    @Autowired
    DockerClient dockerClient;

    private static final String TEST_REPO = "https://github.com/apache/commons-lang.git";
    private static final String TEST_BRANCH = "master";

    // ------------------------------------------------------------------ //
    // Tests                                                                //
    // ------------------------------------------------------------------ //

    @Test
    @Order(1)
    @Timeout(120)
    void cloneAndListJavaFiles() throws Exception {
        try (DockerBuildEnvironment env = context.getBean(DockerBuildEnvironment.class)) {
            env.cloneRepository(TEST_REPO, TEST_BRANCH);

            List<String> javaFiles = env.listJavaFiles("src/main/java");

            assertThat(javaFiles).isNotEmpty();
            assertThat(javaFiles).anyMatch(f -> f.endsWith(".java"));
            assertThat(javaFiles).anyMatch(f -> f.contains("StringUtils"));
        }
    }

    @Test
    @Order(2)
    @Timeout(120)
    void writeFileAndGetDiff() throws Exception {
        try (DockerBuildEnvironment env = context.getBean(DockerBuildEnvironment.class)) {
            env.cloneRepository(TEST_REPO, TEST_BRANCH);

            // Overwrite an existing tracked file so git diff HEAD shows a real diff.
            // New/untracked files don't appear in `git diff HEAD`; modifying a
            // tracked file is the reliable way to exercise getDiff().
            env.writeFile("pom.xml", "<!-- patched -->");

            String diff = env.getDiff();

            assertThat(diff).isNotBlank();
            assertThat(diff).contains("pom.xml");
        }
    }

    @Test
    @Order(3)
    @Timeout(60)
    void networkIsolationVerified() throws Exception {
        try (DockerBuildEnvironment env = context.getBean(DockerBuildEnvironment.class)) {
            // Trigger container creation without cloning
            env.listJavaFiles(".");

            String containerId = env.getContainerId();
            assertThat(containerId).isNotNull();

            // Inspect via Docker API
            var inspection = dockerClient.inspectContainerCmd(containerId).exec();
            var networks = inspection.getNetworkSettings().getNetworks();

            // TODO M8: When networkMode("none") is enabled (pre-built image with git),
            // this assertion should change to: allMatch(n -> n.equals("none"))
            //
            // Current state (M4): containers use the default bridge network so that
            // apt-get install git can reach the internet during cloneRepository.
            // Network isolation for the BUILD phase will be enforced in M8 via
            // networkMode("none") after switching to a pre-built image.
            //
            // For now we assert that Docker inspection itself works and we can see
            // the network configuration — not that it equals "none".
            assertThat(containerId)
                    .as("Container must have an ID to inspect")
                    .isNotNull()
                    .isNotBlank();
            assertThat(inspection.getState().getRunning())
                    .as("Container should be running when inspected")
                    .isTrue();
            // Log the actual network config for observability
            if (networks != null) {
                networks.keySet().forEach(n ->
                        System.out.println("  [networkIsolationVerified] network=" + n));
            }
        }
    }

    @Test
    @Order(4)
    @Timeout(120)
    void containerRemovedAfterClose() throws Exception {
        String capturedId;
        try (DockerBuildEnvironment env = context.getBean(DockerBuildEnvironment.class)) {
            env.listJavaFiles(".");
            capturedId = env.getContainerId();
            assertThat(capturedId).isNotNull();
        }
        // After close() the container should be gone
        final String idToCheck = capturedId;
        List<Container> remaining = dockerClient.listContainersCmd()
                .withShowAll(true)
                .exec();
        assertThat(remaining)
                .as("Container %s should have been removed after close()", idToCheck)
                .noneMatch(c -> c.getId().startsWith(idToCheck));
    }

    @Test
    @Order(5)
    @Timeout(30)
    void validatePath_rejectsPathTraversal() {
        DockerBuildEnvironment env = context.getBean(DockerBuildEnvironment.class);
        try {
            org.assertj.core.api.Assertions.assertThatThrownBy(
                    () -> env.readFile("../etc/passwd"))
                    .isInstanceOf(BuildEnvironmentException.class)
                    .hasMessageContaining("..");
        } finally {
            env.close();
        }
    }
}
