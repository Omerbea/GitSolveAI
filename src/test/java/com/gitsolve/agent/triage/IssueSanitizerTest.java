package com.gitsolve.agent.triage;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for IssueSanitizer — no Spring context required.
 */
@Tag("unit")
class IssueSanitizerTest {

    private final IssueSanitizer sanitizer = new IssueSanitizer();

    @Test
    void sanitize_null_returnsEmpty() {
        assertThat(sanitizer.sanitize(null)).isEmpty();
    }

    @Test
    void sanitize_blank_returnsEmpty() {
        assertThat(sanitizer.sanitize("   ")).isEmpty();
    }

    @Test
    void sanitize_stripsHtmlTags() {
        assertThat(sanitizer.sanitize("<b>hello</b> world"))
                .isEqualTo("hello world");
    }

    @Test
    void sanitize_stripsNestedHtmlTags() {
        assertThat(sanitizer.sanitize("<div><p>text</p></div>"))
                .isEqualTo("text");
    }

    @Test
    void sanitize_collapsesLargeCodeBlock() {
        // Build a code block with 51 lines
        String lines = "x\n".repeat(51);
        String input = "Before\n```java\n" + lines + "```\nAfter";

        String result = sanitizer.sanitize(input);

        assertThat(result).contains(IssueSanitizer.CODE_BLOCK_TRUNCATED);
        // Should NOT contain the raw 51-line content
        assertThat(result).doesNotContain("x\nx\nx\nx\nx");
    }

    @Test
    void sanitize_preservesSmallCodeBlock() {
        String lines = "int x = 1;\n".repeat(10);
        String input = "Description\n```java\n" + lines + "```\n";

        String result = sanitizer.sanitize(input);

        assertThat(result).contains("int x = 1;");
        assertThat(result).doesNotContain(IssueSanitizer.CODE_BLOCK_TRUNCATED);
    }

    @Test
    void sanitize_truncatesLongInput() {
        String longInput = "a".repeat(10_000);

        String result = sanitizer.sanitize(longInput);

        assertThat(result.length()).isLessThanOrEqualTo(
                IssueSanitizer.MAX_BODY_CHARS + IssueSanitizer.TRUNCATED_MARKER.length());
        assertThat(result).endsWith(IssueSanitizer.TRUNCATED_MARKER);
    }

    @Test
    void sanitize_noTruncationForShortInput() {
        String input = "Fix the NPE in StringUtils.isBlank when null is passed.";

        String result = sanitizer.sanitize(input);

        assertThat(result).isEqualTo(input);
        assertThat(result).doesNotContain(IssueSanitizer.TRUNCATED_MARKER);
    }

    @Test
    void sanitize_htmlAndTruncationCombined() {
        // ~5000 chars of HTML content
        String input = "<b>x</b>".repeat(625); // 8 chars * 625 = 5000

        String result = sanitizer.sanitize(input);

        assertThat(result).doesNotContain("<b>");
        assertThat(result).doesNotContain("</b>");
        assertThat(result.length()).isLessThanOrEqualTo(
                IssueSanitizer.MAX_BODY_CHARS + IssueSanitizer.TRUNCATED_MARKER.length());
    }

    @Test
    void sanitize_exactlyAtLimit_notTruncated() {
        String input = "a".repeat(IssueSanitizer.MAX_BODY_CHARS);

        String result = sanitizer.sanitize(input);

        assertThat(result).hasSize(IssueSanitizer.MAX_BODY_CHARS);
        assertThat(result).doesNotContain(IssueSanitizer.TRUNCATED_MARKER);
    }

    @Test
    void sanitize_codeBlockExactly50Lines_preserved() {
        String lines = "x\n".repeat(50);
        String input = "```\n" + lines + "```";

        String result = sanitizer.sanitize(input);

        // Exactly 50 lines → preserved (threshold is > 50)
        assertThat(result).doesNotContain(IssueSanitizer.CODE_BLOCK_TRUNCATED);
    }
}
