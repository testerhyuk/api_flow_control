package com.hyuk.flow_control;

import com.hyuk.flow_control.adapter.in.kafka.KafkaRequestIntakeContainers;
import com.hyuk.flow_control.application.service.DryRunMonitor;
import com.hyuk.flow_control.application.service.PolicyRegistry;
import com.hyuk.flow_control.support.TestcontainersConfiguration;
import com.sun.net.httpserver.HttpServer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
                "management.server.port=0",
                "flow-control.policy.refresh-interval=200ms"
        })
@EmbeddedKafka
@Import(TestcontainersConfiguration.class)
class PolicyManagementFlowTest {
    private static final Duration WAIT = Duration.ofSeconds(30);

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private final Set<String> receivedRequestIds = ConcurrentHashMap.newKeySet();

    @Value("${local.server.port}")
    private int serverPort;

    @Autowired
    private EmbeddedKafkaBroker broker;

    @Autowired
    private PolicyRegistry policies;

    @Autowired
    private KafkaRequestIntakeContainers containers;

    @Autowired
    private DryRunMonitor dryRunMonitor;

    private HttpServer targetSystem;

    @BeforeEach
    void startTargetSystem() throws IOException {
        targetSystem = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        targetSystem.createContext("/call", exchange -> {
            receivedRequestIds.add(exchange.getRequestHeaders().getFirst("x-request-id"));
            exchange.getRequestBody().readAllBytes();
            byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        targetSystem.start();
    }

    @AfterEach
    void stopTargetSystem() {
        targetSystem.stop(0);
    }

    @Test
    @DisplayName("API 로 추가한 대상은 앱을 다시 띄우지 않아도 요청을 읽어 호출하고, 끄면 읽기를 멈춘다")
    void addsTargetAtRuntimeAndTogglesIt() throws Exception {
        String target = "target-flow-" + shortId();

        HttpResponse<String> created = send("POST", "/admin/policies", """
                {"targetSystemId": "%s", "policy": %s, "changedBy": "hyuk", "reason": "새 기관"}
                """.formatted(target, policyJson(10, 100, true)));
        assertThat(created.statusCode()).isEqualTo(201);

        await(() -> containers.isConsuming(target));
        assertThat(policies.policyOf(target).version()).isEqualTo(1);

        String requestId = UUID.randomUUID().toString();
        produce(target, requestId);
        await(() -> receivedRequestIds.contains(requestId));

        HttpResponse<String> disabled = send("PUT", "/admin/policies/" + target + "/enabled", """
                {"enabled": false, "expectedVersion": 1, "changedBy": "hyuk", "reason": "점검"}
                """);
        assertThat(disabled.statusCode()).isEqualTo(200);
        await(() -> !containers.isConsuming(target));

        HttpResponse<String> stale = send("PUT", "/admin/policies/" + target + "/enabled", """
                {"enabled": true, "expectedVersion": 1, "changedBy": "hyuk", "reason": "옛 버전"}
                """);
        assertThat(stale.statusCode()).isEqualTo(409);

        HttpResponse<String> enabled = send("PUT", "/admin/policies/" + target + "/enabled", """
                {"enabled": true, "expectedVersion": 2, "changedBy": "hyuk", "reason": "점검 끝"}
                """);
        assertThat(enabled.statusCode()).isEqualTo(200);
        await(() -> containers.isConsuming(target));

        JsonNode history = jsonMapper.readTree(send("GET", "/admin/policies/" + target + "/history", null).body());
        assertThat(history.size()).isEqualTo(3);
    }

    @Test
    @DisplayName("dry-run 을 시작하면 실제 호출을 새 값으로 계산해 보여주고, 적용하면 새 정책이 반영된다")
    void runsDryRunAndAppliesIt() throws Exception {
        String target = "target-dry-" + shortId();
        send("POST", "/admin/policies", """
                {"targetSystemId": "%s", "policy": %s, "changedBy": "hyuk", "reason": "새 기관"}
                """.formatted(target, policyJson(10, 100, true)));
        await(() -> containers.isConsuming(target));

        HttpResponse<String> urlChange = send("POST", "/admin/policies/" + target + "/dry-run", """
                {"expectedVersion": 1, "policy": %s, "changedBy": "hyuk", "reason": "url"}
                """.formatted(policyJson(10, 100, true).replace(urlOf(), "http://127.0.0.1:1")));
        assertThat(urlChange.statusCode()).isEqualTo(400);

        HttpResponse<String> started = send("POST", "/admin/policies/" + target + "/dry-run", """
                {"expectedVersion": 1, "policy": %s, "changedBy": "hyuk", "reason": "baseline 조정"}
                """.formatted(policyJson(5, 20, true)));
        assertThat(started.statusCode()).isEqualTo(201);

        HttpResponse<String> second = send("POST", "/admin/policies/" + target + "/dry-run", """
                {"expectedVersion": 1, "policy": %s, "changedBy": "hyuk", "reason": "두 번째"}
                """.formatted(policyJson(5, 20, true)));
        assertThat(second.statusCode()).isEqualTo(409);

        await(() -> dryRunMonitor.isRunning(target));

        for (int i = 0; i < 3; i++) {
            String requestId = UUID.randomUUID().toString();
            produce(target, requestId);
            await(() -> receivedRequestIds.contains(requestId));
        }

        JsonNode status = jsonMapper.readTree(send("GET", "/admin/policies/" + target + "/dry-run", null).body());
        assertThat(status.get("baseVersion").asLong()).isEqualTo(1);
        assertThat(status.get("result").get("observedCalls").asLong()).isEqualTo(3);
        assertThat(status.get("result").get("limits").get("rate").asDouble()).isEqualTo(4.75);

        HttpResponse<String> applied = send("POST", "/admin/policies/" + target + "/dry-run/apply", """
                {"changedBy": "hyuk", "reason": "적용"}
                """);
        assertThat(applied.statusCode()).isEqualTo(200);

        await(() -> policies.policyOf(target).version() == 2);
        assertThat(policies.policyOf(target).limits().maxTps()).isEqualTo(5);
        await(() -> !dryRunMonitor.isRunning(target));
        assertThat(send("GET", "/admin/policies/" + target + "/dry-run", null).statusCode()).isEqualTo(404);
    }

    private String policyJson(int maxTps, long baselineRttMs, boolean enabled) {
        return """
                {
                  "enabled": %s,
                  "connection": {"url": "%s", "connectTimeoutMs": 1000, "readTimeoutMs": 3000},
                  "limits": {"maxTps": %d, "baselineRttMs": %d, "safetyMargin": 0.95, "burstWindowMs": 1000},
                  "response": {"statusCodes": {"2xx": "SUCCESS", "5xx": "SERVER_ERROR"}}
                }""".formatted(enabled, urlOf(), maxTps, baselineRttMs);
    }

    private String urlOf() {
        return "http://127.0.0.1:" + targetSystem.getAddress().getPort();
    }

    private HttpResponse<String> send(String method, String path, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + serverPort + path))
                .header("Content-Type", "application/json");
        HttpRequest.BodyPublisher publisher = body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body);

        return httpClient.send(request.method(method, publisher).build(), HttpResponse.BodyHandlers.ofString());
    }

    private void produce(String target, String requestId) {
        String value = "{\"requestId\":\"" + requestId + "\",\"targetSystemId\":\"" + target
                + "\",\"payload\":\"{}\",\"createdAt\":\"" + Instant.now() + "\"}";

        try (KafkaProducer<String, String> producer = new KafkaProducer<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, broker.getBrokersAsString(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class))) {
            producer.send(new ProducerRecord<>("flow-control.requests." + target, requestId, value));
            producer.flush();
        }
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + WAIT.toNanos();

        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("기다린 상태가 되지 않았습니다");
    }

    private static String shortId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
