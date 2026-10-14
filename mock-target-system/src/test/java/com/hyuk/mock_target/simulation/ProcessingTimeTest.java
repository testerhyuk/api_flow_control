package com.hyuk.mock_target.simulation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class ProcessingTimeTest {

    @Test
    @DisplayName("자리가 비어 있으면 1배, 절반이면 1.4배, 다 차면 설정한 배수다")
    void loadFactorGrowsWithSquareOfUsage() {
        assertThat(ProcessingTime.loadFactor(0, 50, 2.6)).isEqualTo(1.0);
        assertThat(ProcessingTime.loadFactor(25, 50, 2.6)).isCloseTo(1.4, within(1e-9));
        assertThat(ProcessingTime.loadFactor(50, 50, 2.6)).isCloseTo(2.6, within(1e-9));
    }

    @Test
    @DisplayName("자리 수보다 많이 처리 중이어도 다 찼을 때 배수를 넘지 않는다")
    void loadFactorIsCappedAtFull() {
        assertThat(ProcessingTime.loadFactor(80, 50, 2.6)).isCloseTo(2.6, within(1e-9));
    }

    @Test
    @DisplayName("흔들림은 가운데 값이 1배이고, 95% 지점이 설정한 p95 배수다")
    void jitterMatchesP95Ratio() {
        assertThat(ProcessingTime.jitter(0, 1.5)).isEqualTo(1.0);
        assertThat(ProcessingTime.jitter(1.6448536269514722, 1.5)).isCloseTo(1.5, within(1e-9));
    }

    @Test
    @DisplayName("p95 배수가 1 이면 흔들리지 않는다")
    void noJitterWhenRatioIsOne() {
        assertThat(ProcessingTime.jitter(2.5, 1.0)).isEqualTo(1.0);
    }

    @Test
    @DisplayName("기본 처리시간에 부하, 흔들림, 장애 배수를 모두 곱한다")
    void multipliesAllFactors() {
        assertThat(ProcessingTime.of(100, 1.4, 1.5, 2.0)).isEqualTo(Duration.ofMillis(420));
    }
}
