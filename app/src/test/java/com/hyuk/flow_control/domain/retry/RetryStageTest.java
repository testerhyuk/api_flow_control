package com.hyuk.flow_control.domain.retry;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class RetryStageTest {

    @Test
    @DisplayName("단계는 1초, 10초, 60초, 300초다")
    void stages() {
        assertThat(RetryStage.values()).extracting(RetryStage::delay).containsExactly(
                Duration.ofSeconds(1), Duration.ofSeconds(10), Duration.ofSeconds(60), Duration.ofSeconds(300));
        assertThat(RetryStage.values()).extracting(RetryStage::label).containsExactly("1s", "10s", "60s", "300s");
    }

    @Test
    @DisplayName("처음 실패하면 1초 단계로 간다")
    void firstFailureGoesToFirstStage() {
        assertThat(RetryStage.firstAfter(null, Duration.ZERO)).contains(RetryStage.FIRST);
    }

    @Test
    @DisplayName("재시도에서 또 실패하면 다음 단계로 간다")
    void nextStage() {
        assertThat(RetryStage.firstAfter(RetryStage.FIRST, Duration.ZERO)).contains(RetryStage.SECOND);
        assertThat(RetryStage.firstAfter(RetryStage.SECOND, Duration.ZERO)).contains(RetryStage.THIRD);
        assertThat(RetryStage.firstAfter(RetryStage.THIRD, Duration.ZERO)).contains(RetryStage.FOURTH);
    }

    @Test
    @DisplayName("마지막 단계에서 실패하면 갈 단계가 없다")
    void noStageAfterLast() {
        assertThat(RetryStage.firstAfter(RetryStage.FOURTH, Duration.ZERO)).isEmpty();
    }

    @Test
    @DisplayName("외부 기관이 알려 준 대기 시간보다 짧은 단계는 건너뛴다")
    void skipsShorterStagesThanRetryAfter() {
        assertThat(RetryStage.firstAfter(null, Duration.ofSeconds(30))).contains(RetryStage.THIRD);
        assertThat(RetryStage.firstAfter(null, Duration.ofSeconds(10))).contains(RetryStage.SECOND);
        assertThat(RetryStage.firstAfter(RetryStage.SECOND, Duration.ofSeconds(100))).contains(RetryStage.FOURTH);
    }

    @Test
    @DisplayName("알려 준 대기 시간이 마지막 단계보다 길면 갈 단계가 없다")
    void noStageLongEnough() {
        assertThat(RetryStage.firstAfter(null, Duration.ofMinutes(10))).isEmpty();
    }
}
