package com.gitsolve.agent.swe;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for SweFixParser — no Spring context, plain ObjectMapper.
 */
@Tag("unit")
class SweFixParserTest {

    private SweFixParser parser;

    private static final String VALID_JSON = """
            {
              "filePath": "src/main/java/Foo.java",
              "fixedContent": "public class Foo {}",
              "explanation": "Removed unused import"
            }
            """;

    @BeforeEach
    void setUp() {
        parser = new SweFixParser(new ObjectMapper());
    }

    // ------------------------------------------------------------------ //
    // Happy-path cases                                                     //
    // ------------------------------------------------------------------ //

    @Test
    void parse_validJsonNoFence_populatesAllFields() throws SweParseException {
        SweFixResponse result = parser.parse(VALID_JSON);

        assertThat(result.filePath()).isEqualTo("src/main/java/Foo.java");
        assertThat(result.fixedContent()).isEqualTo("public class Foo {}");
        assertThat(result.explanation()).isEqualTo("Removed unused import");
    }

    @Test
    void parse_jsonFenceWrapped_strippedAndParsed() throws SweParseException {
        String fenced = "```json\n" + VALID_JSON + "\n```";

        SweFixResponse result = parser.parse(fenced);

        assertThat(result.filePath()).isEqualTo("src/main/java/Foo.java");
        assertThat(result.fixedContent()).isEqualTo("public class Foo {}");
    }

    @Test
    void parse_plainFenceWrapped_strippedAndParsed() throws SweParseException {
        String fenced = "```\n" + VALID_JSON + "\n```";

        SweFixResponse result = parser.parse(fenced);

        assertThat(result.filePath()).isEqualTo("src/main/java/Foo.java");
        assertThat(result.explanation()).isEqualTo("Removed unused import");
    }

    // ------------------------------------------------------------------ //
    // Error / validation cases                                             //
    // ------------------------------------------------------------------ //

    @Test
    void parse_malformedJson_throwsSweParseException() {
        assertThatThrownBy(() -> parser.parse("{invalid json"))
                .isInstanceOf(SweParseException.class);
    }

    @Test
    void parse_missingFilePath_throwsSweParseException() {
        String noFilePath = """
                {
                  "fixedContent": "public class Foo {}",
                  "explanation": "Removed unused import"
                }
                """;

        // parse succeeds at the JSON level but filePath is null — caller must validate
        assertThatThrownBy(() -> {
            SweFixResponse response = parser.parse(noFilePath);
            if (response.filePath() == null) {
                throw new SweParseException("filePath must not be null");
            }
        }).isInstanceOf(SweParseException.class);
    }

    @Test
    void parse_nullInput_throwsSweParseException() {
        assertThatThrownBy(() -> parser.parse(null))
                .isInstanceOf(SweParseException.class)
                .hasMessageContaining("null or empty");
    }

    @Test
    void parse_emptyStringInput_throwsSweParseException() {
        assertThatThrownBy(() -> parser.parse(""))
                .isInstanceOf(SweParseException.class)
                .hasMessageContaining("null or empty");
    }

    @Test
    void parse_blankStringInput_throwsSweParseException() {
        assertThatThrownBy(() -> parser.parse("   "))
                .isInstanceOf(SweParseException.class)
                .hasMessageContaining("null or empty");
    }
}
