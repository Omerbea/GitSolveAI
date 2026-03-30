package com.gitsolve.agent.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("unit")
class FileSelectorParserTest {

    private final FileSelectorParser parser = new FileSelectorParser(new ObjectMapper());

    private static final List<String> AVAILABLE = List.of(
            "src/main/java/com/example/Foo.java",
            "src/main/java/com/example/Bar.java",
            "src/main/java/com/example/Baz.java",
            "src/main/java/com/example/Qux.java",
            "src/main/java/com/example/Quux.java",
            "src/main/java/com/example/Corge.java"
    );

    @Test
    void parse_validResponse_returnsMatchingPaths() {
        String raw = "{\"paths\": [\"src/main/java/com/example/Foo.java\", \"src/main/java/com/example/Bar.java\"]}";
        List<String> result = parser.parse(raw, AVAILABLE);
        assertThat(result).containsExactly(
                "src/main/java/com/example/Foo.java",
                "src/main/java/com/example/Bar.java"
        );
    }

    @Test
    void parse_hallucinatedPath_droppedFromResult() {
        String raw = "{\"paths\": [\"src/main/java/com/example/Foo.java\", \"src/main/java/com/example/DoesNotExist.java\"]}";
        List<String> result = parser.parse(raw, AVAILABLE);
        assertThat(result).containsExactly("src/main/java/com/example/Foo.java");
        assertThat(result).doesNotContain("src/main/java/com/example/DoesNotExist.java");
    }

    @Test
    void parse_emptyPaths_returnsEmptyList() {
        String raw = "{\"paths\": []}";
        assertThat(parser.parse(raw, AVAILABLE)).isEmpty();
    }

    @Test
    void parse_nullInput_returnsEmptyList() {
        assertThat(parser.parse(null, AVAILABLE)).isEmpty();
    }

    @Test
    void parse_malformedJson_returnsEmptyList() {
        assertThat(parser.parse("not json at all", AVAILABLE)).isEmpty();
    }

    @Test
    void parse_caps_atMaxPaths() {
        // LLM returns 6 paths but MAX_PATHS is 5
        String raw = "{\"paths\": [" +
                "\"src/main/java/com/example/Foo.java\"," +
                "\"src/main/java/com/example/Bar.java\"," +
                "\"src/main/java/com/example/Baz.java\"," +
                "\"src/main/java/com/example/Qux.java\"," +
                "\"src/main/java/com/example/Quux.java\"," +
                "\"src/main/java/com/example/Corge.java\"" +
                "]}";
        List<String> result = parser.parse(raw, AVAILABLE);
        assertThat(result).hasSize(FileSelectorParser.MAX_PATHS);
    }

    @Test
    void parse_stripsMarkdownFences() {
        String raw = "```json\n{\"paths\": [\"src/main/java/com/example/Foo.java\"]}\n```";
        assertThat(parser.parse(raw, AVAILABLE))
                .containsExactly("src/main/java/com/example/Foo.java");
    }
}
