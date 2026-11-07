package com.hyuk.integration_test;

import com.hyuk.integration_test.support.Await;
import com.hyuk.integration_test.support.Generator;
import com.hyuk.integration_test.support.IntegrationEnvironment;
import com.hyuk.integration_test.support.IntegrationTestBase;
import com.hyuk.integration_test.support.Kafka;
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

@Order(3)
@DisplayName("점검")
class MaintenanceIT extends IntegrationTestBase {
    private static final String FAULT = "maintenance";
    private static final double RETRY_AFTER_SECONDS = 3600;
    private static final int RATE = 25;
    private static final long DURATION_SECONDS = 300;
    private static final Duration FAULT_DURATION = Duration.ofSeconds(65);
    private static final Duration SETTLE = Duration.ofSeconds(5);
    private static final double FLOOR_RATE = 5.0;
    private static final Duration RESUME_LIMIT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration MIN_PROBE_GAP = Duration.ofSeconds(25);
    private static final Duration MAX_PROBE_GAP = Duration.ofSeconds(35);
    private static final String DEAD_LETTER_TOPIC = "flow-control.dlq." + TARGET;
    private static final String ACTIVE = "flow_control_maintenance_active{target=\"" + TARGET + "\",source=\"response\"}";
    private static final String STARTED = "flow_control_maintenance_started_total{target=\"" + TARGET + "\",source=\"response\"}";
    private static final String LIMIT_RATE = "flow_control_limit_rate{target=\"" + TARGET + "\"}";
    private static final String PROBE_LOG = "대상 시스템 점검 확인 : " + TARGET;

    @Test
    @DisplayName("점검 응답을 받으면 호출을 멈추고, 점검이 끝나면 초당 5건에서 다시 시작한다")
    void pausesAndResumesAtFloorRate() {
        Generator.start(Map.of(TARGET, RATE), DURATION_SECONDS);
        awaitNormalJudgment();
        long deadLettersBefore = Kafka.messageCount(DEAD_LETTER_TOPIC);
        Instant faultStartedAt = Instant.now();
        Instant endedAt;

        TestTimeline timeline = new TestTimeline("점검");
        try {
            timeline.add(faultStartedAt, "점검 상태 켬");
            MockTarget.A.applyFault(FAULT, RETRY_AFTER_SECONDS);
            Await.until("점검 시작", Duration.ofSeconds(15), () -> Prometheus.value(ACTIVE) == 1.0);
            timeline.add(Instant.now(), "점검 시작");

            Await.sleep(SETTLE);
            double callsWhenPaused = Prometheus.value(CALLS);
            Await.sleep(FAULT_DURATION.minus(SETTLE));
            double callsBeforeClear = Prometheus.value(CALLS);
            assertThat(callsBeforeClear).as("멈춘 동안 호출 건수").isEqualTo(callsWhenPaused);

            timeline.add(Instant.now(), "점검 상태 끔");
            MockTarget.A.clearFault(FAULT);
            Await.until("점검 끝남", MAX_PROBE_GAP.plusSeconds(10), () -> Prometheus.value(ACTIVE) == 0.0);
            endedAt = Instant.now();
            timeline.add(endedAt, "점검 끝");
        } finally {
            timeline.finish();
        }

        Await.until("다시 시작할 때 상태가 회복 중", Duration.ofSeconds(5),
                () -> "RECOVERING".equals(Prometheus.stateAt(TARGET, Instant.now())));
        // 한도는 점검이 끝난 다음 수집에 바뀌고, 10초가 지나야 올라가므로 5초 안에 바닥 한도가 보여야 한다.
        Await.until("다시 시작할 때 초당 호출 한도가 5", RESUME_LIMIT_TIMEOUT,
                () -> Math.abs(Prometheus.value(LIMIT_RATE) - FLOOR_RATE) < 0.001);

        assertThat(Prometheus.value(STARTED)).as("점검 시작 횟수").isEqualTo(1.0);
        assertThat(Kafka.messageCount(DEAD_LETTER_TOPIC)).as("실패 보관 토픽 건수").isEqualTo(deadLettersBefore);
        assertProbesEveryThirtySeconds(faultStartedAt);
    }

    private static void assertProbesEveryThirtySeconds(Instant since) {
        List<Instant> probes = new ArrayList<>();
        IntegrationEnvironment.appLogsSince(since).lines()
                .filter(line -> line.contains(PROBE_LOG))
                .forEach(line -> probes.add(Instant.parse(line.substring(0, line.indexOf(' ')))));

        assertThat(probes).as("확인 호출 로그").hasSizeGreaterThanOrEqualTo(2);
        for (int i = 1; i < probes.size(); i++) {
            assertThat(Duration.between(probes.get(i - 1), probes.get(i)))
                    .as("확인 호출 간격")
                    .isBetween(MIN_PROBE_GAP, MAX_PROBE_GAP);
        }
    }
}
