package com.gitsolve.agent.execution;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the ExecutionService.redactToken() static helper — no mocks, no Spring.
 * Kept separate from ExecutionServiceTest to avoid Mockito UnnecessaryStubbingException.
 */
@Tag("unit")
class ExecutionServiceRedactTokenTest {

    @Test
    void redactToken_replacesTokenInUrl() {
        String raw      = "https://x-access-token:ghp_secret123@github.com/owner/repo.git";
        String redacted = ExecutionService.redactToken(raw);
        assertThat(redacted).contains("[REDACTED]");
        assertThat(redacted).doesNotContain("ghp_secret123");
    }

    @Test
    void redactToken_nullInput_returnsNull() {
        assertThat(ExecutionService.redactToken(null)).isNull();
    }

    @Test
    void redactToken_noTokenInUrl_returnsUnchanged() {
        String url = "https://github.com/owner/repo.git";
        assertThat(ExecutionService.redactToken(url)).isEqualTo(url);
    }

    // ------------------------------------------------------------------ //
    // buildPrBody                                                          //
    // ------------------------------------------------------------------ //

    @Test
    void buildPrBody_withClosesRef_returnsUnchanged() {
        String body = "This PR fixes the NPE.\n\nCloses #42";
        assertThat(ExecutionService.buildPrBody(body, 42)).isEqualTo(body);
    }

    @Test
    void buildPrBody_withoutClosesRef_appendsIt() {
        String body = "This PR fixes the NPE.";
        String result = ExecutionService.buildPrBody(body, 42);
        assertThat(result).contains("Closes #42");
        assertThat(result).startsWith("This PR fixes the NPE.");
    }

    @Test
    void buildPrBody_nullBody_returnsOnlyClosesRef() {
        assertThat(ExecutionService.buildPrBody(null, 42)).isEqualTo("Closes #42");
    }

    @Test
    void buildPrBody_blankBody_returnsOnlyClosesRef() {
        assertThat(ExecutionService.buildPrBody("   ", 42)).isEqualTo("Closes #42");
    }
}
