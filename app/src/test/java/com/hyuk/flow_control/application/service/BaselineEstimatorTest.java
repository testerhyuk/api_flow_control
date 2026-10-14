package com.hyuk.flow_control.application.service;

import com.hyuk.flow_control.application.port.out.TargetSystemPolicyReader;
import com.hyuk.flow_control.config.HealthProperties;
import com.hyuk.flow_control.domain.policy.ManagedPolicy;
import com.hyuk.flow_control.domain.policy.TargetSystemPolicy;
import com.hyuk.flow_control.domain.request.OutcomeKind;
import com.hyuk.flow_control.support.Policies;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BaselineEstimatorTest {
    private static final String TARGET = "target-a";
    private static final TargetSystemPolicy POLICY =
            new TargetSystemPolicy(100, Duration.ofMillis(100), 0.95, Duration.ofMillis(10));

    private TargetSystemPolicyReader policyReader;

    @BeforeEach
    void setUp() {
        policyReader = mock(TargetSystemPolicyReader.class);
        when(policyReader.readAll()).thenReturn(Map.of(TARGET, POLICY));
        when(policyReader.read(TARGET)).thenReturn(POLICY);
    }

    @Test
    @DisplayName("표본이 모자라면 정책 값을 쓰고 배운 값은 없다")
    void usesPolicyUntilEnoughSamples() {
        BaselineEstimator estimator = estimator(true, Duration.ofMinutes(10));
        observe(estimator, 9, Duration.ofMillis(120));

        estimator.refresh();

        assertThat(estimator.effective(TARGET)).isEqualTo(Duration.ofMillis(100));
        assertThat(estimator.learned(TARGET)).isEmpty();
        assertThat(estimator.sampleCount(TARGET)).isEqualTo(9);
    }

    @Test
    @DisplayName("표본이 모이면 성공 응답의 중앙값을 쓴다")
    void usesMedian() {
        BaselineEstimator estimator = estimator(true, Duration.ofMinutes(10));
        observe(estimator, 5, Duration.ofMillis(80));
        observe(estimator, 6, Duration.ofMillis(120));

        estimator.refresh();

        assertThat(estimator.learned(TARGET)).contains(Duration.ofMillis(120));
        assertThat(estimator.effective(TARGET)).isEqualTo(Duration.ofMillis(120));
        assertThat(estimator.clampedBound(TARGET)).isEmpty();
    }

    @Test
    @DisplayName("성공과 업무 거절만 배우고 실패의 시간은 배우지 않는다")
    void learnsOnlySuccessfulResponses() {
        BaselineEstimator estimator = estimator(true, Duration.ofMinutes(10));
        for (int i = 0; i < 10; i++) {
            estimator.observe(TARGET, OutcomeKind.BUSINESS_REJECTED, Duration.ofMillis(90));
            estimator.observe(TARGET, OutcomeKind.TIMEOUT, Duration.ofSeconds(3));
        }

        estimator.refresh();

        assertThat(estimator.sampleCount(TARGET)).isEqualTo(10);
        assertThat(estimator.learned(TARGET)).contains(Duration.ofMillis(90));
    }

    @Test
    @DisplayName("배운 값이 정책 값의 1.5배를 넘으면 1.5배에서 멈추고 위쪽 한계에 닿았다고 알린다")
    void clampsUpper() {
        BaselineEstimator estimator = estimator(true, Duration.ofMinutes(10));
        observe(estimator, 10, Duration.ofMillis(400));

        estimator.refresh();

        assertThat(estimator.learned(TARGET)).contains(Duration.ofMillis(400));
        assertThat(estimator.effective(TARGET)).isEqualTo(Duration.ofMillis(150));
        assertThat(estimator.clampedBound(TARGET)).contains(BaselineEstimator.Bound.UPPER);
    }

    @Test
    @DisplayName("배운 값이 정책 값의 0.5배보다 작으면 0.5배에서 멈춘다")
    void clampsLower() {
        BaselineEstimator estimator = estimator(true, Duration.ofMinutes(10));
        observe(estimator, 10, Duration.ofMillis(10));

        estimator.refresh();

        assertThat(estimator.effective(TARGET)).isEqualTo(Duration.ofMillis(50));
        assertThat(estimator.clampedBound(TARGET)).contains(BaselineEstimator.Bound.LOWER);
    }

    @Test
    @DisplayName("다른 정책 값으로 한계를 잡으면 그 값 기준으로 자른다")
    void clampsToGivenPolicyBaseline() {
        BaselineEstimator estimator = estimator(true, Duration.ofMinutes(10));
        observe(estimator, 10, Duration.ofMillis(400));

        estimator.refresh();

        assertThat(estimator.effective(TARGET, Duration.ofMillis(300))).isEqualTo(Duration.ofMillis(400));
        assertThat(estimator.effective(TARGET, Duration.ofMillis(200))).isEqualTo(Duration.ofMillis(300));
    }

    @Test
    @DisplayName("오래된 표본은 버린다")
    void dropsOldSamples() throws InterruptedException {
        BaselineEstimator estimator = estimator(true, Duration.ofMillis(100));
        observe(estimator, 10, Duration.ofMillis(120));

        Thread.sleep(150);
        estimator.refresh();

        assertThat(estimator.sampleCount(TARGET)).isZero();
        assertThat(estimator.effective(TARGET)).isEqualTo(Duration.ofMillis(100));
    }

    @Test
    @DisplayName("정책의 baseline 이 바뀌면 배운 값을 버린다")
    void resetsWhenPolicyBaselineChanges() {
        BaselineEstimator estimator = estimator(true, Duration.ofMinutes(10));
        observe(estimator, 10, Duration.ofMillis(120));
        estimator.refresh();

        ManagedPolicy before = Policies.policy(TARGET);
        ManagedPolicy after = Policies.withLimits(before,
                new TargetSystemPolicy(100, Duration.ofMillis(200), 0.95, Duration.ofMillis(10)));
        estimator.onPolicyChanged(before, after);

        assertThat(estimator.learned(TARGET)).isEmpty();
        assertThat(estimator.sampleCount(TARGET)).isZero();
    }

    @Test
    @DisplayName("정책의 다른 값만 바뀌면 배운 값을 그대로 둔다")
    void keepsWhenOtherPolicyValuesChange() {
        BaselineEstimator estimator = estimator(true, Duration.ofMinutes(10));
        observe(estimator, 10, Duration.ofMillis(120));
        estimator.refresh();

        ManagedPolicy before = Policies.policy(TARGET);
        ManagedPolicy after = Policies.withLimits(before,
                new TargetSystemPolicy(200, before.limits().baselineRtt(), 0.95, Duration.ofMillis(10)));
        estimator.onPolicyChanged(before, after);

        assertThat(estimator.learned(TARGET)).contains(Duration.ofMillis(120));
    }

    @Test
    @DisplayName("자동 조정을 끄면 늘 정책 값을 쓰고 배우지 않는다")
    void disabled() {
        BaselineEstimator estimator = estimator(false, Duration.ofMinutes(10));
        observe(estimator, 10, Duration.ofMillis(400));

        estimator.refresh();

        assertThat(estimator.isEnabled()).isFalse();
        assertThat(estimator.effective(TARGET)).isEqualTo(Duration.ofMillis(100));
        assertThat(estimator.learned(TARGET)).isEmpty();
        assertThat(estimator.sampleCount(TARGET)).isZero();
    }

    private static void observe(BaselineEstimator estimator, int count, Duration latency) {
        for (int i = 0; i < count; i++) {
            estimator.observe(TARGET, OutcomeKind.SUCCESS, latency);
        }
    }

    private BaselineEstimator estimator(boolean enabled, Duration window) {
        HealthProperties properties = new HealthProperties(
                Duration.ofSeconds(10), Duration.ofSeconds(1), 50, 0.10, 0.50, 2.0, Duration.ofSeconds(30),
                true, null, 3, null, enabled, new HealthProperties.Baseline(window, 10, 0.5, 1.5));
        return new BaselineEstimator(policyReader, properties);
    }
}
