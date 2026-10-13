package com.hyuk.flow_control.config;

import com.hyuk.flow_control.application.port.out.FlowControlMetrics;
import com.hyuk.flow_control.domain.health.HealthState;
import com.hyuk.flow_control.domain.request.OutcomeKind;
import com.hyuk.flow_control.domain.retry.DeadLetterReason;
import com.hyuk.flow_control.domain.retry.RetryStage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "management.server.port=0",
                "flow-control.intake.auto-startup=false",
                "spring.kafka.admin.auto-create=false"
        })
class MetricsEndpointTest {
    private final HttpClient httpClient = HttpClient.newHttpClient();

    @Value("${local.server.port}")
    private int serverPort;

    @Value("${local.management.port}")
    private int managementPort;

    @Autowired
    private FlowControlMetrics metrics;

    @Test
    @DisplayName("관리용 포트의 prometheus 주소에서 대상별 상태와 한도 지표를 읽을 수 있다")
    void exposesGaugesOnManagementPort() throws Exception {
        HttpResponse<String> response = get(managementPort, "/actuator/prometheus");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body())
                .contains("flow_control_health_state{state=\"HEALTHY\",target=\"target-a\"}")
                .contains("flow_control_health_failure_rate{target=\"target-a\"}")
                .contains("flow_control_health_p95_seconds{target=\"target-a\"}")
                .contains("flow_control_health_samples{target=\"target-a\"}")
                .contains("flow_control_health_baseline_rtt_seconds{target=\"target-a\"}")
                .contains("flow_control_health_latency_threshold_seconds{target=\"target-a\"}")
                .contains("flow_control_limit_rate{target=\"target-a\"}")
                .contains("flow_control_limit_rate_max{target=\"target-a\"}")
                .contains("flow_control_limit_concurrency{target=\"target-a\"}")
                .contains("flow_control_inflight{target=\"target-a\"}");
    }

    @Test
    @DisplayName("기록한 호출, latency, 막힌 요청, 상태 변경이 prometheus 형식으로 나온다")
    void exposesEventMetrics() throws Exception {
        metrics.recordCall("target-b", OutcomeKind.TIMEOUT, Duration.ofSeconds(3));
        metrics.recordBlockedByTokenBucket("target-b");
        metrics.recordStateTransition("target-b", HealthState.HEALTHY, HealthState.CRITICAL);
        metrics.recordIntakeWait("target-b", Duration.ofMillis(120));
        metrics.recordRetryScheduled("target-b", RetryStage.FIRST, OutcomeKind.TIMEOUT);
        metrics.recordRetrySucceeded("target-b", RetryStage.FIRST);
        metrics.recordDeadLetter("target-b", DeadLetterReason.RETRY_EXHAUSTED);

        String body = get(managementPort, "/actuator/prometheus").body();

        assertThat(body)
                .contains("flow_control_outbound_calls_total{kind=\"TIMEOUT\",target=\"target-b\"}")
                .contains("flow_control_outbound_latency_seconds_bucket{kind=\"TIMEOUT\",target=\"target-b\",le=\"3.0\"} 1")
                .contains("flow_control_outbound_blocked_attempts_total{reason=\"token_bucket\",target=\"target-b\"}")
                .contains("flow_control_health_transitions_total{from=\"HEALTHY\",target=\"target-b\",to=\"CRITICAL\"}")
                .contains("flow_control_intake_wait_seconds_bucket{target=\"target-b\",le=\"0.2\"} 1")
                .contains("flow_control_retry_scheduled_total{kind=\"TIMEOUT\",stage=\"1s\",target=\"target-b\"}")
                .contains("flow_control_retry_succeeded_total{stage=\"1s\",target=\"target-b\"}")
                .contains("flow_control_dlq_sent_total{reason=\"retry_exhausted\",target=\"target-b\"}");
    }

    @Test
    @DisplayName("관리용 포트에서 health 는 열려 있다")
    void exposesHealth() throws Exception {
        assertThat(get(managementPort, "/actuator/health").statusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("health, prometheus 말고 다른 주소는 열려 있지 않다")
    void hidesOtherEndpoints() throws Exception {
        assertThat(get(managementPort, "/actuator/env").statusCode()).isEqualTo(404);
        assertThat(get(managementPort, "/actuator/configprops").statusCode()).isEqualTo(404);
        assertThat(get(managementPort, "/actuator/beans").statusCode()).isEqualTo(404);
    }

    @Test
    @DisplayName("앱 포트에서는 지표 주소가 열려 있지 않다")
    void notExposedOnServerPort() throws Exception {
        assertThat(get(serverPort, "/actuator/prometheus").statusCode()).isEqualTo(404);
    }

    private HttpResponse<String> get(int port, String path) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
