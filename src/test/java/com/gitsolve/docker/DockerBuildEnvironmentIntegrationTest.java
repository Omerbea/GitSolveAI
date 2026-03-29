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

            env.writeFile("TestPatch.java", "public class TestPatch {}");

            String diff = env.getDiff();

            assertThat(diff).isNotBlank();
            assertThat(diff).contains("TestPatch.java");
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

            // networkMode=none means no named networks (empty map, or only "none" key)
            if (networks != null && !networks.isEmpty()) {
                assertThat(networks.keySet())
                        .as("Container should have no real network interfaces (only 'none' allowed)")
                        .allMatch(n -> n.equals("none"));
            }
            // If networks is null or empty, that also satisfies the isolation requirement
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
