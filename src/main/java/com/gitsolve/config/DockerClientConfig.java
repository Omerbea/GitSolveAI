package com.gitsolve.config;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.zerodep.ZerodepDockerHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Configures the docker-java DockerClient bean.
 *
 * Uses ZerodepDockerHttpClient which has built-in Unix socket support (no JNA needed).
 * This is required for OrbStack and Docker Desktop on macOS where the socket path
 * is non-standard and Apache HttpClient 5 does not handle Unix sockets natively.
 *
 * DOCKER_HOST is resolved in priority order:
 *   1. System property (set via .mvn/maven.config → forwarded to Surefire JVM)
 *   2. Environment variable (set in the shell)
 *   3. docker-java default (/var/run/docker.sock on Linux, npipe on Windows)
 */
@Configuration
public class DockerClientConfig {

    private static final Logger log = LoggerFactory.getLogger(DockerClientConfig.class);

    @Bean
    public DockerClient dockerClient() {
        String dockerHost = System.getProperty("DOCKER_HOST");
        if (dockerHost == null || dockerHost.isBlank()) {
            dockerHost = System.getenv("DOCKER_HOST");
        }

        log.info("DockerClientConfig: resolved DOCKER_HOST={}", dockerHost);

        DefaultDockerClientConfig.Builder configBuilder =
                DefaultDockerClientConfig.createDefaultConfigBuilder();
        if (dockerHost != null && !dockerHost.isBlank()) {
            configBuilder.withDockerHost(dockerHost);
        }

        DefaultDockerClientConfig config = configBuilder.build();
        log.info("DockerClientConfig: using dockerHost={}", config.getDockerHost());

        ZerodepDockerHttpClient httpClient = new ZerodepDockerHttpClient.Builder()
                .dockerHost(config.getDockerHost())
                .sslConfig(config.getSSLConfig())
                .maxConnections(10)
                .connectionTimeout(Duration.ofSeconds(30))
                .responseTimeout(Duration.ofSeconds(120))
                .build();

        return DockerClientImpl.getInstance(config, httpClient);
    }
}
