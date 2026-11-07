package com.hyuk.integration_test;

import com.hyuk.integration_test.support.Await;
import com.hyuk.integration_test.support.Generator;
import com.hyuk.integration_test.support.IntegrationTestBase;
import com.hyuk.integration_test.support.MockTarget;
import com.hyuk.integration_test.support.Prometheus;
import com.hyuk.integration_test.support.TestTimeline;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Order(2)
@DisplayName("상태 회복")
class HealthRecoveryIT extends IntegrationTestBase {
    private static final String FAULT = "failure-rate";
    private static final double FAILURE_RATE = 0.6;
    private static final int RATE = 25;
    // 회복 중에 판정 하나라도 애매하면 연속 횟수를 처음부터 다시 세므로, 회복에 걸리는 시간이 실행마다 다르다.
    private static final long DURATION_SECONDS = 900;
    private static final Duration FAULT_DURATION = Duration.ofSeconds(30);
    private static final Duration RECOVERY_TIMEOUT = Duration.ofMinutes(10);
    private static final Duration SAMPLE_INTERVAL = Duration.ofMillis(500);
    private static final long MINIMUM_RECOVERY_CALLS = 1450;
    private static final Map<String, String> STATE_NAMES = Map.of(
            "HEALTHY", "정상", "DEGRADED", "저하", "CRITICAL", "위험", "RECOVERING", "회복 중");

    private record StateSample(String state, double calls, Instant at) {
    }

    @Test
    @DisplayName("서버 오류율을 60%로 올렸다가 되돌리면 위험, 회복 중을 거쳐 정상으로 돌아오고, 회복 중 호출이 1450건 이상이다")
    void recoversThroughRecovering() {
        Generator.start(Map.of(TARGET, RATE), DURATION_SECONDS);
        awaitNormalJudgment();

        List<StateSample> samples = new ArrayList<>();
        try {
            MockTarget.A.applyFault(FAULT, FAILURE_RATE);
            collectFor(FAULT_DURATION, samples);
            MockTarget.A.clearFault(FAULT);

            collectUntilHealthyAfterRecovering(samples);
        } finally {
            recordTimeline(samples);
        }

        assertThat(indexOfFirst(samples, "CRITICAL", 0)).as("위험까지 내려감 : %s", transitions(samples)).isNotNegative();
        int critical = indexOfFirst(samples, "CRITICAL", 0);
        int healthy = samples.size() - 1;
        int recovering = startOfLastRecovering(samples, critical, healthy);

        assertThat(recovering).as("위험 뒤에 회복 중을 거침 : %s", transitions(samples)).isGreaterThan(critical);
        assertThat(samples.get(healthy).state()).isEqualTo("HEALTHY");

        double recoveryCalls = samples.get(healthy).calls() - samples.get(recovering).calls();
        System.out.println("상태 변화 : " + transitions(samples) + ", 회복 중 호출 " + recoveryCalls + "건");
        assertThat(recoveryCalls).as("회복 중 호출 건수").isGreaterThanOrEqualTo(MINIMUM_RECOVERY_CALLS);
    }

    private static void collectFor(Duration duration, List<StateSample> samples) {
        long deadline = System.nanoTime() + duration.toNanos();
        while (System.nanoTime() < deadline) {
            samples.add(sample());
            Await.sleep(SAMPLE_INTERVAL);
        }
    }

    private static void collectUntilHealthyAfterRecovering(List<StateSample> samples) {
        Await.until("상태가 회복 중을 거쳐 정상으로 돌아옴", RECOVERY_TIMEOUT, () -> {
            StateSample latest = sample();
            samples.add(latest);
            return "HEALTHY".equals(latest.state()) && indexOfFirst(samples, "RECOVERING", 0) >= 0;
        });
    }

    private static StateSample sample() {
        return new StateSample(Prometheus.stateOf(TARGET), Prometheus.value(CALLS), Instant.now());
    }

    private static void recordTimeline(List<StateSample> samples) {
        TestTimeline timeline = new TestTimeline("상태 회복");
        String previous = null;
        for (StateSample sample : samples) {
            if (!sample.state().equals(previous)) {
                timeline.add(sample.at(), STATE_NAMES.getOrDefault(sample.state(), sample.state()));
                previous = sample.state();
            }
        }
        timeline.finish();
    }

    private static int indexOfFirst(List<StateSample> samples, String state, int from) {
        for (int i = from; i < samples.size(); i++) {
            if (state.equals(samples.get(i).state())) {
                return i;
            }
        }
        return -1;
    }

    /** 정상이 되기 직전에 이어진 회복 중 구간의 시작. 회복 중 다시 나빠지면 그 뒤 구간부터 센다. */
    private static int startOfLastRecovering(List<StateSample> samples, int after, int healthy) {
        int start = -1;
        for (int i = healthy - 1; i > after && "RECOVERING".equals(samples.get(i).state()); i--) {
            start = i;
        }
        return start;
    }

    private static List<String> transitions(List<StateSample> samples) {
        List<String> states = new ArrayList<>();
        for (StateSample sample : samples) {
            if (states.isEmpty() || !states.getLast().equals(sample.state())) {
                states.add(sample.state());
            }
        }
        return states;
    }
}
