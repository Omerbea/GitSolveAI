package com.gitsolve.agent.swe;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gitsolve.config.GitSolveProperties;
import com.gitsolve.docker.BuildEnvironment;
import com.gitsolve.docker.BuildOutput;
import com.gitsolve.docker.DockerBuildEnvironment;
import com.gitsolve.model.FixResult;
import com.gitsolve.model.GitIssue;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.service.TokenStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link SweService} — no Spring context, pure Mockito mocks.
 *
 * <p>The critical challenge is mocking {@link TokenStream}'s fluent chain
 * (onNext / onComplete / onError / start) so callbacks fire synchronously.
 * Without this the {@code CountDownLatch} inside {@code SweService.blockOnStream}
 * would block indefinitely. We use {@code doAnswer} to capture the consumers
 * stored by the fluent methods and invoke them inside the {@code start()} stub.
 */
@Tag("unit")
class SweServiceTest {

    // ------------------------------------------------------------------ //
    // Mocks                                                               //
    // ------------------------------------------------------------------ //

    private SweAiService           mockSweAiService;
    private DockerBuildEnvironment mockEnv;
    private ApplicationContext     mockContext;

    // real parser — no extra mocking needed for happy-path JSON
    private SweFixParser sweFixParser;

    // system under test (re-created per test in helpers below)
    // (declared here so tests can reference it)
    private SweService swe;

    // ------------------------------------------------------------------ //
    // Fixtures                                                            //
    // ------------------------------------------------------------------ //

    private static final GitIssue TEST_ISSUE = new GitIssue(
            "owner/repo", 42, "Fix NPE", "There is a NullPointerException in Foo.",
            "https://github.com/owner/repo/issues/42", List.of("bug"));

    private static final BuildOutput PASSING_BUILD =
            new BuildOutput("", "", 0, false, Duration.ofSeconds(5));

    private static final BuildOutput FAILING_BUILD =
            new BuildOutput("", "COMPILATION ERROR", 1, false, Duration.ofSeconds(5));

    // ------------------------------------------------------------------ //
    // Setup                                                               //
    // ------------------------------------------------------------------ //

    @BeforeEach
    void setUp() throws Exception {
        mockSweAiService = mock(SweAiService.class);
        mockEnv          = mock(DockerBuildEnvironment.class);
        mockContext      = mock(ApplicationContext.class);
        sweFixParser     = new SweFixParser(new ObjectMapper());

        // ApplicationContext.getBean(DockerBuildEnvironment.class) → mockEnv
        when(mockContext.getBean(DockerBuildEnvironment.class)).thenReturn(mockEnv);

        // cloneRepository is a void method — default mock is fine (does nothing)

        // listJavaFiles and readFile feed ContextWindowManager
        when(mockEnv.listJavaFiles(any())).thenReturn(List.of("src/Foo.java"));
        when(mockEnv.readFile(any())).thenReturn(Optional.of("class Foo {}"));

        // getDiff is called after each writeFile+runBuild
        when(mockEnv.getDiff()).thenReturn("--- diff ---");
    }

    // ------------------------------------------------------------------ //
    // TokenStream helpers                                                 //
    // ------------------------------------------------------------------ //

    /**
     * Builds a synchronous {@link TokenStream} mock.
     *
     * <p>{@code onNext}, {@code onComplete}, and {@code onError} capture their
     * consumer arguments and return {@code this}. {@code start()} invokes the
     * captured consumers synchronously so the {@code CountDownLatch} inside
     * {@code SweService.blockOnStream} is released before {@code start()} returns.
     *
     * <p>Uses {@code doAnswer(...).when(ts).method()} syntax throughout to avoid
     * Mockito's UnfinishedStubbing error, which can occur when a stubbed method
     * returns the same mock object that is being stubbed.
     */
    private static TokenStream makeTokenStream(String json) {
        TokenStream ts = mock(TokenStream.class);

        AtomicReference<Consumer<String>> onNextRef = new AtomicReference<>();
        AtomicReference<Consumer<Response<AiMessage>>> onCompleteRef = new AtomicReference<>();

        doAnswer(inv -> {
            onNextRef.set(inv.getArgument(0));
            return ts;
        }).when(ts).onNext(any());

        doAnswer(inv -> {
            onCompleteRef.set(inv.getArgument(0));
            return ts;
        }).when(ts).onComplete(any());

        doReturn(ts).when(ts).onError(any());

        // start() drives the whole stream synchronously
        doAnswer(inv -> {
            Consumer<String> nextConsumer = onNextRef.get();
            if (nextConsumer != null) {
                nextConsumer.accept(json);
            }
            Consumer<Response<AiMessage>> completeConsumer = onCompleteRef.get();
            if (completeConsumer != null) {
                completeConsumer.accept(Response.from(AiMessage.from(json)));
            }
            return null;
        }).when(ts).start();

        return ts;
    }

    /** Returns a well-formed SWE fix JSON for the given relative file path. */
    private static String makeFixJson(String filePath) {
        return "{\"filePath\":\"" + filePath + "\","
                + "\"fixedContent\":\"class Foo {fixed}\","
                + "\"explanation\":\"Fixed it\"}";
    }

    /** Builds a {@link GitSolveProperties} stub with the given llm/docker values. */
    private static GitSolveProperties makeProps(int maxIterations) {
        GitSolveProperties.GitHub github = mock(GitSolveProperties.GitHub.class);
        GitSolveProperties.Llm    llm    = mock(GitSolveProperties.Llm.class);
        GitSolveProperties.Docker docker = mock(GitSolveProperties.Docker.class);

        when(llm.maxIterationsPerFix()).thenReturn(maxIterations);
        when(docker.image()).thenReturn("eclipse-temurin:21-jdk");

        GitSolveProperties props = mock(GitSolveProperties.class);
        when(props.github()).thenReturn(github);
        when(props.llm()).thenReturn(llm);
        when(props.docker()).thenReturn(docker);

        return props;
    }

    // ------------------------------------------------------------------ //
    // Test 1 — iteration 1 fails, iteration 2 passes                      //
    // ------------------------------------------------------------------ //

    @Test
    void fix_iterationOneFailsTwoPass_returnsSuccessWithTwoAttempts() throws Exception {
        // Build the TokenStream mock BEFORE entering any when() chain to avoid
        // Mockito UnfinishedStubbingException (makeTokenStream itself sets up stubs).
        TokenStream ts = makeTokenStream(makeFixJson("src/Foo.java"));
        when(mockSweAiService.generateFix(any(), any(), any(), any(), any()))
                .thenReturn(ts);

        // First build fails; second passes
        when(mockEnv.runBuild(any()))
                .thenReturn(FAILING_BUILD)
                .thenReturn(PASSING_BUILD);

        swe = new SweService(mockSweAiService, mockContext, sweFixParser, makeProps(5));
        FixResult result = swe.fix(TEST_ISSUE);

        assertThat(result.success()).isTrue();
        assertThat(result.attempts()).hasSize(2);
        assertThat(result.attempts().get(0).buildPassed()).isFalse();
        assertThat(result.attempts().get(1).buildPassed()).isTrue();
    }

    // ------------------------------------------------------------------ //
    // Test 2 — exhausts max iterations                                    //
    // ------------------------------------------------------------------ //

    @Test
    void fix_exhaustsMaxIterations_returnsFailure() throws Exception {
        TokenStream ts = makeTokenStream(makeFixJson("src/Foo.java"));
        when(mockSweAiService.generateFix(any(), any(), any(), any(), any()))
                .thenReturn(ts);

        // Build always fails
        when(mockEnv.runBuild(any())).thenReturn(FAILING_BUILD);

        swe = new SweService(mockSweAiService, mockContext, sweFixParser, makeProps(2));
        FixResult result = swe.fix(TEST_ISSUE);

        assertThat(result.success()).isFalse();
        assertThat(result.attempts()).hasSize(2);
        assertThat(result.failureReason()).contains("Exhausted");
    }

    // ------------------------------------------------------------------ //
    // Test 3 — malformed LLM response (parse error per iteration)         //
    // ------------------------------------------------------------------ //

    @Test
    void fix_parseExceptionOnMalformedResponse_returnsFailure() throws Exception {
        TokenStream ts = makeTokenStream("not-valid-json");
        when(mockSweAiService.generateFix(any(), any(), any(), any(), any()))
                .thenReturn(ts);

        // build stubs not needed — we'll never reach runBuild with maxIterations=1
        // but provide a safe stub in case the iteration count varies
        when(mockEnv.runBuild(any())).thenReturn(FAILING_BUILD);

        swe = new SweService(mockSweAiService, mockContext, sweFixParser, makeProps(1));
        FixResult result = swe.fix(TEST_ISSUE);

        // Per SweService implementation: SweParseException is caught per iteration,
        // the loop continues (consuming all maxIterations), then exhaustion returns failure.
        assertThat(result.success()).isFalse();
    }
}
