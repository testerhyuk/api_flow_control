package com.hyuk.flow_control.domain.health;

import com.hyuk.flow_control.domain.request.OutcomeKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

class OutcomeStatisticsTest {

    @Test
    @DisplayName("결과가 없으면 건수 0, 나쁘다 0, p95 없음, 나쁘다 비율 0")
    void emptyOutcomes() {
        OutcomeStatistics statistics = OutcomeStatistics.from(List.of());

        assertThat(statistics).isEqualTo(new OutcomeStatistics(0, 0, null));
        assertThat(statistics.failureRate()).isZero();
    }

    @ParameterizedTest
    @EnumSource(value = OutcomeKind.class, names = {"SUCCESS", "BUSINESS_REJECTED", "INVALID_REQUEST"})
    @DisplayName("나쁘지 않다 kind 는 건수에 들어가고 latency 도 p95 계산에 쓰인다")
    void notBadKinds(OutcomeKind kind) {
        OutcomeStatistics statistics = OutcomeStatistics.from(List.of(outcome(kind, 120)));

        assertThat(statistics).isEqualTo(new OutcomeStatistics(1, 0, Duration.ofMillis(120)));
    }

    @ParameterizedTest
    @EnumSource(value = OutcomeKind.class, names = {
            "TIMEOUT", "CONNECTION_FAILED", "NETWORK_ERROR",
            "SERVER_ERROR", "TOO_MANY_REQUESTS", "UNDER_MAINTENANCE"})
    @DisplayName("나쁘다 kind 는 건수와 나쁘다 건수에 들어가고 latency 는 p95 계산에 쓰이지 않는다")
    void badKinds(OutcomeKind kind) {
        OutcomeStatistics statistics = OutcomeStatistics.from(List.of(outcome(kind, 3000)));

        assertThat(statistics).isEqualTo(new OutcomeStatistics(1, 1, null));
    }

    @Test
    @DisplayName("UNEXPECTED_RESPONSE 는 건수에도 나쁘다 건수에도 p95 에도 들어가지 않는다")
    void unexpectedResponseIsExcluded() {
        OutcomeStatistics statistics = OutcomeStatistics.from(List.of(outcome(OutcomeKind.UNEXPECTED_RESPONSE, 500)));

        assertThat(statistics).isEqualTo(new OutcomeStatistics(0, 0, null));
    }

    @Test
    @DisplayName("나쁘다 비율은 나쁘다 건수를 전체 건수로 나눈 값이다")
    void failureRate() {
        List<RecordedOutcome> outcomes = new ArrayList<>();
        addMany(outcomes, OutcomeKind.SUCCESS, 100, 7);
        addMany(outcomes, OutcomeKind.TIMEOUT, 3000, 3);
        addMany(outcomes, OutcomeKind.UNEXPECTED_RESPONSE, 100, 5);

        OutcomeStatistics statistics = OutcomeStatistics.from(outcomes);

        assertThat(statistics.sampleCount()).isEqualTo(10);
        assertThat(statistics.badCount()).isEqualTo(3);
        assertThat(statistics.failureRate()).isEqualTo(0.3);
    }

    @Test
    @DisplayName("100건이면 작은 쪽부터 95번째 값이 p95 다")
    void p95OfHundred() {
        OutcomeStatistics statistics = OutcomeStatistics.from(successesFrom1To(100));

        assertThat(statistics.p95()).isEqualTo(Duration.ofMillis(95));
    }

    @Test
    @DisplayName("20건이면 작은 쪽부터 19번째 값이 p95 다")
    void p95OfTwenty() {
        OutcomeStatistics statistics = OutcomeStatistics.from(successesFrom1To(20));

        assertThat(statistics.p95()).isEqualTo(Duration.ofMillis(19));
    }

    @Test
    @DisplayName("1건이면 그 값이 p95 다")
    void p95OfOne() {
        OutcomeStatistics statistics = OutcomeStatistics.from(List.of(outcome(OutcomeKind.SUCCESS, 42)));

        assertThat(statistics.p95()).isEqualTo(Duration.ofMillis(42));
    }

    @Test
    @DisplayName("결과가 들어온 순서와 상관없이 p95 는 같다")
    void p95IgnoresOrder() {
        List<RecordedOutcome> shuffled = new ArrayList<>(successesFrom1To(100));
        Collections.shuffle(shuffled, new Random(7));

        OutcomeStatistics statistics = OutcomeStatistics.from(shuffled);

        assertThat(statistics.p95()).isEqualTo(Duration.ofMillis(95));
    }

    @Test
    @DisplayName("나쁘다 kind 의 latency 가 길어도 p95 는 바뀌지 않는다")
    void p95IgnoresBadLatency() {
        List<RecordedOutcome> outcomes = new ArrayList<>(successesFrom1To(100));
        addMany(outcomes, OutcomeKind.TIMEOUT, 3000, 50);

        OutcomeStatistics statistics = OutcomeStatistics.from(outcomes);

        assertThat(statistics.p95()).isEqualTo(Duration.ofMillis(95));
    }

    @Test
    @DisplayName("나쁘다 kind 만 있으면 p95 는 없다")
    void p95IsNullWhenOnlyBad() {
        List<RecordedOutcome> outcomes = new ArrayList<>();
        addMany(outcomes, OutcomeKind.SERVER_ERROR, 100, 10);

        OutcomeStatistics statistics = OutcomeStatistics.from(outcomes);

        assertThat(statistics.p95()).isNull();
        assertThat(statistics.failureRate()).isEqualTo(1.0);
    }

    private static List<RecordedOutcome> successesFrom1To(int count) {
        List<RecordedOutcome> outcomes = new ArrayList<>();
        for (int millis = 1; millis <= count; millis++) {
            outcomes.add(outcome(OutcomeKind.SUCCESS, millis));
        }
        return outcomes;
    }

    private static void addMany(List<RecordedOutcome> outcomes, OutcomeKind kind, long millis, int count) {
        for (int i = 0; i < count; i++) {
            outcomes.add(outcome(kind, millis));
        }
    }

    private static RecordedOutcome outcome(OutcomeKind kind, long millis) {
        return new RecordedOutcome(kind, Duration.ofMillis(millis), Instant.now());
    }
}
