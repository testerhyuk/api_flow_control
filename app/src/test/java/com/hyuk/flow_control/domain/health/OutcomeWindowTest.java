package com.hyuk.flow_control.domain.health;

import com.hyuk.flow_control.domain.request.OutcomeKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OutcomeWindowTest {
    private static final Instant NOW = Instant.parse("2026-10-14T00:00:10Z");

    @Test
    @DisplayName("최신 결과부터 정한 건수까지만 담고, 오래된 순서로 돌려준다")
    void picksLatestSamples() {
        List<RecordedOutcome> outcomes = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            outcomes.add(outcome(OutcomeKind.SUCCESS, NOW.minusSeconds(9 - i)));
        }

        OutcomeWindow window = OutcomeWindow.latest(outcomes.reversed().iterator(), 3, NOW.minusSeconds(10));

        assertThat(window.outcomes()).extracting(RecordedOutcome::recordedAt)
                .containsExactly(NOW.minusSeconds(2), NOW.minusSeconds(1), NOW);
        assertThat(window.span()).isEqualTo(Duration.ofSeconds(2));
    }

    @Test
    @DisplayName("정한 시각보다 오래된 결과는 담지 않는다")
    void stopsAtSince() {
        List<RecordedOutcome> outcomes = List.of(
                outcome(OutcomeKind.SUCCESS, NOW.minusSeconds(20)),
                outcome(OutcomeKind.SUCCESS, NOW.minusSeconds(5)),
                outcome(OutcomeKind.SUCCESS, NOW));

        OutcomeWindow window = OutcomeWindow.latest(outcomes.reversed().iterator(), 50, NOW.minusSeconds(10));

        assertThat(window.outcomes()).hasSize(2);
        assertThat(window.span()).isEqualTo(Duration.ofSeconds(5));
    }

    @Test
    @DisplayName("판정에서 빼는 결과는 건너뛰고 건수에도 넣지 않는다")
    void skipsExcluded() {
        List<RecordedOutcome> outcomes = List.of(
                outcome(OutcomeKind.SUCCESS, NOW.minusSeconds(2)),
                outcome(OutcomeKind.UNEXPECTED_RESPONSE, NOW.minusSeconds(1)),
                outcome(OutcomeKind.TIMEOUT, NOW));

        OutcomeWindow window = OutcomeWindow.latest(outcomes.reversed().iterator(), 2, NOW.minusSeconds(10));

        assertThat(window.outcomes()).extracting(RecordedOutcome::kind)
                .containsExactly(OutcomeKind.SUCCESS, OutcomeKind.TIMEOUT);
        assertThat(window.statistics().sampleCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("담긴 결과가 없으면 시간 범위가 없다")
    void emptyWindow() {
        OutcomeWindow window = OutcomeWindow.latest(List.<RecordedOutcome>of().iterator(), 50, NOW);

        assertThat(window.outcomes()).isEmpty();
        assertThat(window.span()).isNull();
        assertThat(window.statistics().sampleCount()).isZero();
    }

    private static RecordedOutcome outcome(OutcomeKind kind, Instant at) {
        return new RecordedOutcome(kind, Duration.ofMillis(100), at);
    }
}
