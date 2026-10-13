package com.hyuk.flow_control.adapter.out.metrics;

import com.hyuk.flow_control.domain.health.HealthState;
import com.hyuk.flow_control.domain.request.OutcomeKind;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.distribution.CountAtBucket;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class MicrometerFlowControlMetricsTest {
    private SimpleMeterRegistry registry;
    private MicrometerFlowControlMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new MicrometerFlowControlMetrics(registry);
    }

    @Test
    @DisplayName("호출 결과를 대상과 kind 별로 센다")
    void countsCallsByTargetAndKind() {
        metrics.recordCall("target-a", OutcomeKind.SUCCESS, Duration.ofMillis(120));
        metrics.recordCall("target-a", OutcomeKind.SUCCESS, Duration.ofMillis(80));
        metrics.recordCall("target-a", OutcomeKind.TIMEOUT, Duration.ofSeconds(3));
        metrics.recordCall("target-b", OutcomeKind.SUCCESS, Duration.ofMillis(300));

        assertThat(callCount("target-a", "SUCCESS")).isEqualTo(2.0);
        assertThat(callCount("target-a", "TIMEOUT")).isEqualTo(1.0);
        assertThat(callCount("target-b", "SUCCESS")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("latency 를 대상과 kind 별로 기록한다")
    void recordsLatency() {
        metrics.recordCall("target-a", OutcomeKind.SUCCESS, Duration.ofMillis(120));
        metrics.recordCall("target-a", OutcomeKind.SUCCESS, Duration.ofMillis(80));

        Timer timer = latencyTimer("target-a", "SUCCESS");

        assertThat(timer.count()).isEqualTo(2);
        assertThat(timer.totalTime(TimeUnit.MILLISECONDS)).isEqualTo(200.0);
    }

    @Test
    @DisplayName("latency 구간 경계는 10ms 부터 5s 까지 11 개다")
    void latencyHasElevenBuckets() {
        metrics.recordCall("target-a", OutcomeKind.SUCCESS, Duration.ofMillis(120));

        CountAtBucket[] buckets = latencyTimer("target-a", "SUCCESS").takeSnapshot().histogramCounts();

        assertThat(Arrays.stream(buckets).mapToDouble(bucket -> bucket.bucket(TimeUnit.MILLISECONDS)))
                .containsExactly(10.0, 25.0, 50.0, 100.0, 200.0, 300.0, 500.0, 1000.0, 2000.0, 3000.0, 5000.0);
    }

    @Test
    @DisplayName("latency 는 자기 값 이상인 구간에만 들어간다")
    void latencyFallsIntoUpperBuckets() {
        metrics.recordCall("target-a", OutcomeKind.SUCCESS, Duration.ofMillis(120));

        CountAtBucket[] buckets = latencyTimer("target-a", "SUCCESS").takeSnapshot().histogramCounts();

        assertThat(countAt(buckets, 100)).isZero();
        assertThat(countAt(buckets, 200)).isEqualTo(1.0);
        assertThat(countAt(buckets, 5000)).isEqualTo(1.0);
    }

    @Test
    @DisplayName("세마포어와 토큰 버킷에서 막힌 요청을 이유별로 센다")
    void countsBlockedByReason() {
        metrics.recordBlockedBySemaphore("target-a");
        metrics.recordBlockedByTokenBucket("target-a");
        metrics.recordBlockedByTokenBucket("target-a");

        assertThat(blockedCount("target-a", "semaphore")).isEqualTo(1.0);
        assertThat(blockedCount("target-a", "token_bucket")).isEqualTo(2.0);
    }

    @Test
    @DisplayName("상태가 바뀐 횟수를 바뀌기 전과 후 상태별로 센다")
    void countsStateTransitions() {
        metrics.recordStateTransition("target-a", HealthState.HEALTHY, HealthState.CRITICAL);
        metrics.recordStateTransition("target-a", HealthState.CRITICAL, HealthState.RECOVERING);
        metrics.recordStateTransition("target-a", HealthState.HEALTHY, HealthState.CRITICAL);

        assertThat(registry.get("flow.control.health.transitions")
                .tags("target", "target-a", "from", "HEALTHY", "to", "CRITICAL")
                .counter().count()).isEqualTo(2.0);
        assertThat(registry.get("flow.control.health.transitions")
                .tags("target", "target-a", "from", "CRITICAL", "to", "RECOVERING")
                .counter().count()).isEqualTo(1.0);
    }

    private double callCount(String target, String kind) {
        return registry.get("flow.control.outbound.calls").tags("target", target, "kind", kind).counter().count();
    }

    private Timer latencyTimer(String target, String kind) {
        return registry.get("flow.control.outbound.latency").tags("target", target, "kind", kind).timer();
    }

    private double blockedCount(String target, String reason) {
        return registry.get("flow.control.outbound.blocked").tags("target", target, "reason", reason).counter().count();
    }

    private static double countAt(CountAtBucket[] buckets, double millis) {
        return Arrays.stream(buckets)
                .filter(bucket -> bucket.bucket(TimeUnit.MILLISECONDS) == millis)
                .findFirst()
                .orElseThrow()
                .count();
    }
}
