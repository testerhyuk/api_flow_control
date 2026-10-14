package com.hyuk.mock_target.web;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "mock-target.target=target-b",
                "management.server.port=0",
                "mock-target.systems.target-b.base-processing-millis=5",
                "mock-target.systems.target-b.max-tps=1000",
                "mock-target.systems.target-b.server-error-rate=0",
                "mock-target.systems.target-b.business-reject-rate=0"
        })
class MockTargetHttpTest {
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();

    @Value("${local.server.port}")
    private int serverPort;

    @Value("${local.management.port}")
    private int managementPort;

    @AfterEach
    void clearFaults() throws Exception {
        send(HttpRequest.newBuilder(admin("/actuator/faults")).DELETE().build());
    }

    @Test
    @DisplayName("POST /call 에 요청 ID 와 JSON 본문을 보내면 200 으로 응답한다")
    void succeeds() throws Exception {
        HttpResponse<String> response = call("req-1", "{\"amount\":1000}");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"requestId\":\"req-1\"");
    }

    @Test
    @DisplayName("본문이 JSON 이 아니면 400 으로 응답한다")
    void rejectsInvalidBody() throws Exception {
        assertThat(call("req-2", "not json").statusCode()).isEqualTo(400);
    }

    @Test
    @DisplayName("관리용 포트로 점검을 걸면 503 과 Retry-After 로 응답하고, 풀면 다시 처리한다")
    void maintenanceThroughAdminPort() throws Exception {
        HttpResponse<String> applied = applyFault("maintenance", "{\"value\":30}");
        assertThat(applied.statusCode()).isEqualTo(200);
        assertThat(applied.body()).contains("\"maintenance\":30");

        HttpResponse<String> response = call("req-3", "{}");
        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(response.headers().firstValue("Retry-After")).contains("30");

        send(HttpRequest.newBuilder(admin("/actuator/faults/maintenance")).DELETE().build());
        assertThat(call("req-4", "{}").statusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("다운을 걸면 /call 포트가 닫혀 연결이 거부되고, 풀면 다시 연결된다")
    void downClosesCallPort() throws Exception {
        assertThat(applyFault("down", "{}").statusCode()).isEqualTo(200);

        assertThatThrownBy(() -> call("req-5", "{}")).isInstanceOf(ConnectException.class);

        send(HttpRequest.newBuilder(admin("/actuator/faults/down")).DELETE().build());
        assertThat(call("req-6", "{}").statusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("모르는 장애 종류나 잘못된 값이면 400 으로 거절한다")
    void rejectsInvalidFault() throws Exception {
        assertThat(applyFault("boom", "{}").statusCode()).isEqualTo(400);
        assertThat(applyFault("slow", "{\"value\":0.5}").statusCode()).isEqualTo(400);
    }

    @Test
    @DisplayName("관리용 포트의 prometheus 주소에서 시뮬레이터 지표를 읽을 수 있다")
    void exposesMetrics() throws Exception {
        call("req-7", "{}");

        HttpResponse<String> response = send(HttpRequest.newBuilder(admin("/actuator/prometheus")).GET().build());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body())
                .contains("mock_target_requests_total{outcome=\"SUCCESS\",target=\"target-b\"}")
                .contains("mock_target_latency_seconds_bucket{")
                .contains("mock_target_inflight{target=\"target-b\"}")
                .contains("mock_target_queue{target=\"target-b\"}")
                .contains("mock_target_capacity{target=\"target-b\"} 20.0")
                .contains("mock_target_fault{target=\"target-b\",type=\"down\"} 0.0")
                .contains("mock_target_duplicates_total{target=\"target-b\"}");
    }

    private HttpResponse<String> call(String requestId, String body) throws IOException, InterruptedException {
        try (HttpClient fresh = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build()) {
            return fresh.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + serverPort + "/call"))
                    .header("x-request-id", requestId)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    private HttpResponse<String> applyFault(String type, String body) throws IOException, InterruptedException {
        return send(HttpRequest.newBuilder(admin("/actuator/faults/" + type))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build());
    }

    private URI admin(String path) {
        return URI.create("http://127.0.0.1:" + managementPort + path);
    }

    private HttpResponse<String> send(HttpRequest request) throws IOException, InterruptedException {
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
