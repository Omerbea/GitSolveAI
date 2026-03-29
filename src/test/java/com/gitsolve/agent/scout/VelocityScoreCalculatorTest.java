package com.gitsolve.agent.scout;

import com.gitsolve.model.GitRepository;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Pure unit tests for VelocityScoreCalculator — no Spring context.
 */
@Tag("unit")
class VelocityScoreCalculatorTest {

    private final VelocityScoreCalculator calc = new VelocityScoreCalculator();

    // ------------------------------------------------------------------ //
    // compute() boundary cases                                             //
    // ------------------------------------------------------------------ //

    @Test
    void score_allZeroInputs_returnsZero() {
        assertThat(calc.compute(0, 0, 0, 0, 0, 0)).isEqualTo(0.0);
    }

    @Test
    void score_allMaxInputs_returnsHundred() {
        assertThat(calc.compute(100, 100, 100, 100, 100, 100))
                .isCloseTo(100.0, within(0.001));
    }

    @Test
    void score_onlyCommitsAtMax_returnsCommitWeight() {
        // commits=100/100=1.0 * 0.5 * 100 = 50.0; stars and forks 0
        assertThat(calc.compute(100, 0, 0, 100, 100, 100))
                .isCloseTo(50.0, within(0.001));
    }

    @Test
    void score_halfCommitsNoStarsNoForks() {
        // commits=50/100=0.5 * 0.5 * 100 = 25.0
        assertThat(calc.compute(50, 0, 0, 100, 100, 100))
                .isCloseTo(25.0, within(0.001));
    }

    @Test
    void score_zeroMaxDoesNotDivideByZero() {
        // maxCommits=0 → contribution is 0; same for others
        assertThat(calc.compute(99, 99, 99, 0, 0, 0)).isEqualTo(0.0);
    }

    // ------------------------------------------------------------------ //
    // rankAndFilter()                                                      //
    // ------------------------------------------------------------------ //

    @Test
    void rankAndFilter_returnsExactlyTopN() {
        List<GitRepository> candidates = List.of(
                repo("a", 10, 100, 50),
                repo("b", 5, 200, 20),
                repo("c", 20, 50, 100),
                repo("d", 1, 10, 5),
                repo("e", 15, 150, 80)
        );

        List<GitRepository> result = calc.rankAndFilter(candidates, 3);

        assertThat(result).hasSize(3);
    }

    @Test
    void rankAndFilter_sortedDescendingByVelocityScore() {
        List<GitRepository> candidates = List.of(
                repo("low",  0, 0, 0),
                repo("high", 100, 100, 100),
                repo("mid",  50, 50, 50)
        );

        List<GitRepository> result = calc.rankAndFilter(candidates, 3);

        assertThat(result.get(0).fullName()).isEqualTo("high");
        assertThat(result.get(1).fullName()).isEqualTo("mid");
        assertThat(result.get(2).fullName()).isEqualTo("low");
    }

    @Test
    void rankAndFilter_topNGreaterThanSize_returnsAll() {
        List<GitRepository> candidates = List.of(
                repo("a", 10, 10, 10),
                repo("b", 20, 20, 20)
        );

        List<GitRepository> result = calc.rankAndFilter(candidates, 100);

        assertThat(result).hasSize(2);
    }

    @Test
    void rankAndFilter_emptyInput_returnsEmpty() {
        assertThat(calc.rankAndFilter(List.of(), 5)).isEmpty();
    }

    @Test
    void rankAndFilter_velocityScoreAssigned() {
        List<GitRepository> candidates = List.of(
                repo("max", 100, 100, 100)
        );

        List<GitRepository> result = calc.rankAndFilter(candidates, 1);

        assertThat(result.get(0).velocityScore()).isCloseTo(100.0, within(0.001));
    }

    // ------------------------------------------------------------------ //
    // Helper                                                               //
    // ------------------------------------------------------------------ //

    private static GitRepository repo(String name, int commits, int stars, int forks) {
        return new GitRepository(name, "https://github.com/" + name + ".git",
                "https://github.com/" + name, stars, forks, commits, 0.0);
    }
}
