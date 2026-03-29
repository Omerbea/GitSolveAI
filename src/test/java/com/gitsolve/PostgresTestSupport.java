package com.gitsolve;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Base class for integration tests that require a live Postgres instance.
 *
 * Uses a manually-managed singleton container (started once per JVM, reused across
 * all subclasses). This avoids the @Testcontainers + @Container per-class lifecycle
 * which starts a new container for every subclass, causing HikariCP reconnection
 * failures when the first container is stopped while the second Spring context is
 * still holding connections to it.
 *
 * The container is started eagerly in the static initialiser and will be stopped
 * by the JVM shutdown hook registered by Testcontainers (Ryuk is still active for
 * cleanup, but the container itself is not managed by the @Container lifecycle).
 */
public abstract class PostgresTestSupport {

    private static final PostgreSQLContainer<?> POSTGRES;

    static {
        POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
                .withDatabaseName("gitsolve_test")
                .withUsername("test")
                .withPassword("test");
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",      POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }
}
