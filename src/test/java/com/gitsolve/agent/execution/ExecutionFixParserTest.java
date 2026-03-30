package com.gitsolve.agent.execution;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for ExecutionFixParser — no Spring context.
 */
@Tag("unit")
class ExecutionFixParserTest {

    private final ExecutionFixParser parser = new ExecutionFixParser(
            new com.fasterxml.jackson.databind.ObjectMapper());

    // ------------------------------------------------------------------ //
    // Valid cases                                                          //
    // ------------------------------------------------------------------ //

    @Test
    void parse_validMultiFileJson_returnsResponse() {
        String json = """
                {
                  "files": [
                    {"path": "src/main/java/Foo.java", "content": "public class Foo {}"},
                    {"path": "README.md", "content": "# My Project\\n\\nThis is the readme content."}
                  ],
                  "commitMessage": "fix(core): resolve NPE in Foo",
                  "prTitle": "Fix NPE in Foo",
                  "prBody": "Closes #42"
                }
                """;

        ExecutionFixResponse response = parser.parse(json);

        assertThat(response.files()).hasSize(2);
        assertThat(response.files().get(0).path()).isEqualTo("src/main/java/Foo.java");
        assertThat(response.commitMessage()).isEqualTo("fix(core): resolve NPE in Foo");
        assertThat(response.prTitle()).isEqualTo("Fix NPE in Foo");
    }

    @Test
    void parse_jsonWrappedInMarkdownFences_stripsAndParses() {
        String wrapped = """
                ```json
                {
                  "files": [{"path": "src/Foo.java", "content": "public class Foo {}"}],
                  "commitMessage": "fix: the fix",
                  "prTitle": "The fix",
                  "prBody": "Closes #1"
                }
                ```
                """;

        ExecutionFixResponse response = parser.parse(wrapped);
        assertThat(response.files()).hasSize(1);
        assertThat(response.commitMessage()).isEqualTo("fix: the fix");
    }

    // ------------------------------------------------------------------ //
    // Invalid cases — ExecutionParseException expected                    //
    // ------------------------------------------------------------------ //

    @Test
    void parse_emptyFilesList_throwsExecutionParseException() {
        String json = """
                {
                  "files": [],
                  "commitMessage": "fix: something",
                  "prTitle": "Fix",
                  "prBody": "Closes #1"
                }
                """;

        assertThatThrownBy(() -> parser.parse(json))
                .isInstanceOf(ExecutionParseException.class)
                .hasMessageContaining("no files");
    }

    @Test
    void parse_missingFilePath_throwsExecutionParseException() {
        String json = """
                {
                  "files": [{"path": null, "content": "public class Foo {}"}],
                  "commitMessage": "fix: the fix",
                  "prTitle": "Fix",
                  "prBody": "Closes #1"
                }
                """;

        assertThatThrownBy(() -> parser.parse(json))
                .isInstanceOf(ExecutionParseException.class)
                .hasMessageContaining("null or blank path");
    }

    @Test
    void parse_shortContent_throwsExecutionParseException() {
        // Content is only 5 chars — below the 10-char truncation guard
        String json = """
                {
                  "files": [{"path": "src/Foo.java", "content": "short"}],
                  "commitMessage": "fix: the fix",
                  "prTitle": "Fix",
                  "prBody": "Closes #1"
                }
                """;

        assertThatThrownBy(() -> parser.parse(json))
                .isInstanceOf(ExecutionParseException.class)
                .hasMessageContaining("too short");
    }

    @Test
    void parse_blankCommitMessage_throwsExecutionParseException() {
        String json = """
                {
                  "files": [{"path": "src/Foo.java", "content": "public class Foo {}"}],
                  "commitMessage": "   ",
                  "prTitle": "Fix",
                  "prBody": "Closes #1"
                }
                """;

        assertThatThrownBy(() -> parser.parse(json))
                .isInstanceOf(ExecutionParseException.class)
                .hasMessageContaining("commitMessage");
    }
}
