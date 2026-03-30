package com.gitsolve.agent.execution;

import com.gitsolve.docker.BuildEnvironment;
import com.gitsolve.docker.BuildEnvironmentException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@Tag("unit")
@ExtendWith(MockitoExtension.class)
class TargetedContextBuilderTest {

    @Mock
    private BuildEnvironment env;

    private final TargetedContextBuilder builder = new TargetedContextBuilder();

    // -----------------------------------------------------------------------
    // Test 1 — under-cap: file content < 30k chars is included verbatim
    // -----------------------------------------------------------------------
    @Test
    void build_underCap_includesContentVerbatim() throws BuildEnvironmentException {
        String content = "x".repeat(TargetedContextBuilder.MAX_FILE_CHARS - 1);
        when(env.readFile("Foo.java")).thenReturn(Optional.of(content));

        String result = builder.build(List.of("Foo.java"), env);

        assertThat(result).contains(content);
        assertThat(result).doesNotContain("[file truncated at");
    }

    // -----------------------------------------------------------------------
    // Test 2 — over-cap: content > 30k is truncated at exactly 30k with marker
    // -----------------------------------------------------------------------
    @Test
    void build_overCap_truncatesAtMaxFileCharsWithMarker() throws BuildEnvironmentException {
        String content = "y".repeat(TargetedContextBuilder.MAX_FILE_CHARS + 500);
        when(env.readFile("Big.java")).thenReturn(Optional.of(content));

        String result = builder.build(List.of("Big.java"), env);

        String expectedMarker = "// ... [file truncated at " + TargetedContextBuilder.MAX_FILE_CHARS + " chars]";
        assertThat(result).contains(expectedMarker);

        // The included content must be exactly MAX_FILE_CHARS 'y' chars (no more)
        String fileBlock = result.substring(result.indexOf('\n') + 1); // skip "// FILE: Big.java\n"
        String contentPart = fileBlock.substring(0, TargetedContextBuilder.MAX_FILE_CHARS);
        assertThat(contentPart).isEqualTo("y".repeat(TargetedContextBuilder.MAX_FILE_CHARS));

        // Nothing past the cap boundary should appear before the marker
        int markerPos = result.indexOf(expectedMarker);
        int capBoundaryPos = result.indexOf("// FILE: Big.java\n") + "// FILE: Big.java\n".length()
                + TargetedContextBuilder.MAX_FILE_CHARS;
        assertThat(markerPos).isGreaterThanOrEqualTo(capBoundaryPos);
    }

    // -----------------------------------------------------------------------
    // Test 3 — total-cap: a file that would push total past 80k is skipped
    // -----------------------------------------------------------------------
    @Test
    void build_totalCap_skipsFilesThatWouldExceedTotalBudget() throws BuildEnvironmentException {
        // A and B are each just under MAX_FILE_CHARS (so no per-file truncation).
        // A_block (~29k) + B_block (~29k) = ~58k — fits inside 80k.
        // C is 30k chars: B_block already in sb (~29k×2=58k), C_block = ~30k → 88k > 80k → skipped.
        // The block overhead ("// FILE: X.java\n" + content + "\n\n") is ~20 extra chars, so
        // use content sizes well below MAX_FILE_CHARS to avoid per-file truncation.

        int nearMax = TargetedContextBuilder.MAX_FILE_CHARS - 1; // 29 999 chars, no per-file trunc

        String a = "a".repeat(nearMax);
        String b = "b".repeat(nearMax);
        // C would push total well past 80k
        String c = "c".repeat(nearMax);

        when(env.readFile("A.java")).thenReturn(Optional.of(a));
        when(env.readFile("B.java")).thenReturn(Optional.of(b));
        when(env.readFile("C.java")).thenReturn(Optional.of(c));

        String result = builder.build(List.of("A.java", "B.java", "C.java"), env);

        // A and B fit; C must be skipped because adding it would exceed MAX_TOTAL_CHARS
        assertThat(result).contains("// FILE: A.java");
        assertThat(result).contains("// FILE: B.java");
        assertThat(result).doesNotContain("// FILE: C.java");
        // Total length must not exceed the cap
        assertThat(result.length()).isLessThanOrEqualTo(TargetedContextBuilder.MAX_TOTAL_CHARS);
    }
}
