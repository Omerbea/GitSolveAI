package com.gitsolve.agent.swe;

import com.gitsolve.docker.BuildEnvironment;
import com.gitsolve.docker.BuildEnvironmentException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for ContextWindowManager — no Spring context, Mockito mock for BuildEnvironment.
 */
@Tag("unit")
class ContextWindowManagerTest {

    private BuildEnvironment env;

    @BeforeEach
    void setUp() {
        env = mock(BuildEnvironment.class);
    }

    // ------------------------------------------------------------------ //
    // Happy-path cases                                                     //
    // ------------------------------------------------------------------ //

    @Test
    void buildContext_singleFileWithinBudget_includedWithHeader()
            throws BuildEnvironmentException {
        ContextWindowManager manager =
                new ContextWindowManager(ContextWindowManager.MAX_CONTEXT_CHARS);
        when(env.readFile("Foo.java")).thenReturn(Optional.of("public class Foo {}"));

        String result = manager.buildContext(List.of("Foo.java"), env);

        assertThat(result).contains("// FILE: Foo.java\n");
        assertThat(result).contains("public class Foo {}");
    }

    @Test
    void buildContext_multipleFilesWithinBudget_allIncludedAlphabetically()
            throws BuildEnvironmentException {
        ContextWindowManager manager =
                new ContextWindowManager(ContextWindowManager.MAX_CONTEXT_CHARS);
        when(env.readFile("a/Alpha.java")).thenReturn(Optional.of("class Alpha {}"));
        when(env.readFile("b/Beta.java")).thenReturn(Optional.of("class Beta {}"));
        when(env.readFile("c/Gamma.java")).thenReturn(Optional.of("class Gamma {}"));

        // Supply in reverse alphabetical order — manager must sort
        String result = manager.buildContext(
                List.of("c/Gamma.java", "a/Alpha.java", "b/Beta.java"), env);

        assertThat(result).contains("// FILE: a/Alpha.java\n");
        assertThat(result).contains("// FILE: b/Beta.java\n");
        assertThat(result).contains("// FILE: c/Gamma.java\n");

        // Alphabetical ordering: Alpha before Beta before Gamma
        int alphaIdx = result.indexOf("// FILE: a/Alpha.java");
        int betaIdx  = result.indexOf("// FILE: b/Beta.java");
        int gammaIdx = result.indexOf("// FILE: c/Gamma.java");
        assertThat(alphaIdx).isLessThan(betaIdx);
        assertThat(betaIdx).isLessThan(gammaIdx);
    }

    @Test
    void buildContext_filesExceedBudget_outputNeverExceedsMaxContextChars()
            throws BuildEnvironmentException {
        // Use a small budget to force overflow
        int smallBudget = 200;
        ContextWindowManager manager = new ContextWindowManager(smallBudget);

        // Each file's block is about 70-80 chars; three together exceed 200
        when(env.readFile("A.java")).thenReturn(Optional.of("class A { /* content */ }"));
        when(env.readFile("B.java")).thenReturn(Optional.of("class B { /* content */ }"));
        when(env.readFile("C.java")).thenReturn(Optional.of("class C { /* content */ }"));

        String result = manager.buildContext(List.of("A.java", "B.java", "C.java"), env);

        assertThat(result.length()).isLessThanOrEqualTo(smallBudget);
    }

    @Test
    void buildContext_emptyFileList_returnsEmptyString()
            throws BuildEnvironmentException {
        ContextWindowManager manager =
                new ContextWindowManager(ContextWindowManager.MAX_CONTEXT_CHARS);

        String result = manager.buildContext(List.of(), env);

        assertThat(result).isEmpty();
    }

    @Test
    void buildContext_singleFileBlockExceedsMaxChars_skippedOutputIsEmpty()
            throws BuildEnvironmentException {
        // maxChars = 50; a file block that exceeds it must be skipped entirely
        int tinyBudget = 50;
        ContextWindowManager manager = new ContextWindowManager(tinyBudget);

        // Content + header will be well over 50 chars
        when(env.readFile("Big.java"))
                .thenReturn(Optional.of("class Big { /* a lot of content here */ }"));

        String result = manager.buildContext(List.of("Big.java"), env);

        assertThat(result).isEmpty();
    }

    @Test
    void buildContext_readFileReturnsEmpty_fileSilentlySkipped()
            throws BuildEnvironmentException {
        ContextWindowManager manager =
                new ContextWindowManager(ContextWindowManager.MAX_CONTEXT_CHARS);
        when(env.readFile("Missing.java")).thenReturn(Optional.empty());
        when(env.readFile("Present.java")).thenReturn(Optional.of("class Present {}"));

        String result = manager.buildContext(List.of("Missing.java", "Present.java"), env);

        assertThat(result).doesNotContain("Missing.java");
        assertThat(result).contains("// FILE: Present.java\n");
    }
}
