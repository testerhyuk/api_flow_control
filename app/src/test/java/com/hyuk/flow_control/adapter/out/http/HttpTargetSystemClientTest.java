package com.hyuk.flow_control.adapter.out.http;

import com.hyuk.flow_control.config.TargetSystemProperties;
import com.hyuk.flow_control.domain.request.OutboundRequest;
import com.hyuk.flow_control.domain.request.OutboundResult;
import com.hyuk.flow_control.domain.request.OutcomeKind;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HttpTargetSystemClientTest {
    private static final String TARGET = "target-a";
    private static final Duration CONNECT_TIMEOUT = Duration.ofMillis(500);
    private static final Duration READ_TIMEOUT = Duration.ofMillis(300);
    private static final String PAYLOAD = "{\"amount\":1000}";
    private static final OutboundRequest REQUEST = new OutboundRequest("req-1", TARGET, PAYLOAD, Instant.now());

    private HttpServer server;
    private ExecutorService serverThreads;
    private HttpTargetSystemClient client;

    private volatile int responseStatus;
    private volatile String responseBody;
    private volatile String retryAfterHeader;
    private volatile Duration responseDelay;

    private volatile String receivedMethod;
    private volatile String receivedPath;
    private volatile String receivedRequestId;
    private volatile String receivedContentType;
    private volatile String receivedBody;

    @BeforeEach
    void setUp() throws IOException {
        responseStatus = 200;
        responseBody = null;
        retryAfterHeader = null;
        responseDelay = Duration.ZERO;

        serverThreads = Executors.newCachedThreadPool();
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setExecutor(serverThreads);
        server.createContext("/", exchange -> {
            receivedMethod = exchange.getRequestMethod();
            receivedPath = exchange.getRequestURI().getPath();
            receivedRequestId = exchange.getRequestHeaders().getFirst("x-request-id");
            receivedContentType = exchange.getRequestHeaders().getFirst("Content-Type");
            receivedBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);

            try {
                Thread.sleep(responseDelay.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                exchange.close();
                return;
            }

            if (retryAfterHeader != null) {
                exchange.getResponseHeaders().add("Retry-After", retryAfterHeader);
            }

            if (responseBody == null) {
                exchange.sendResponseHeaders(responseStatus, -1);
            } else {
                byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(responseStatus, bytes.length);
                exchange.getResponseBody().write(bytes);
            }

            exchange.close();
        });
        server.start();

        client = clientFor("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
        serverThreads.shutdownNow();
    }

    @ParameterizedTest(name = "{0} → {1}")
    @DisplayName("본문의 status 를 OutcomeKind 로 바꾼다")
    @CsvSource({
            "SUCCESS, SUCCESS",
            "REJECTED, BUSINESS_REJECTED",
            "INVALID_REQUEST, INVALID_REQUEST",
            "TOO_MANY_REQUESTS, TOO_MANY_REQUESTS",
            "MAINTENANCE, UNDER_MAINTENANCE",
            "SERVER_ERROR, SERVER_ERROR",
            "OVERLOADED, SERVER_ERROR",
            "SOMETHING_ELSE, UNEXPECTED_RESPONSE"
    })
    void convertsBodyStatus(String status, OutcomeKind expected) {
        respond(200, json(status));

        OutboundResult result = client.call(REQUEST);

        assertThat(result.kind()).isEqualTo(expected);
    }

    @ParameterizedTest(name = "HTTP {0} → {1}")
    @DisplayName("본문이 없으면 HTTP 상태 코드로 판단한다")
    @CsvSource({
            "429, TOO_MANY_REQUESTS",
            "400, INVALID_REQUEST",
            "500, SERVER_ERROR",
            "503, SERVER_ERROR",
            "200, UNEXPECTED_RESPONSE",
            "404, UNEXPECTED_RESPONSE"
    })
    void usesHttpStatusWhenBodyIsEmpty(int httpStatus, OutcomeKind expected) {
        respond(httpStatus, null);

        OutboundResult result = client.call(REQUEST);

        assertThat(result.kind()).isEqualTo(expected);
        assertThat(result.responseBody()).isNull();
    }

    @Test
    @DisplayName("본문에 status 가 없으면 HTTP 상태 코드로 판단한다")
    void usesHttpStatusWhenBodyHasNoStatus() {
        respond(429, "{\"requestId\":\"req-1\"}");

        OutboundResult result = client.call(REQUEST);

        assertThat(result.kind()).isEqualTo(OutcomeKind.TOO_MANY_REQUESTS);
    }

    @Test
    @DisplayName("본문의 status 가 HTTP 상태 코드보다 먼저다")
    void bodyStatusWinsOverHttpStatus() {
        respond(500, json("MAINTENANCE"));

        OutboundResult result = client.call(REQUEST);

        assertThat(result.kind()).isEqualTo(OutcomeKind.UNDER_MAINTENANCE);
    }

    @Test
    @DisplayName("응답 본문의 body 값을 그대로 돌려준다")
    void returnsResponseBody() {
        respond(200, json("SUCCESS"));

        OutboundResult result = client.call(REQUEST);

        assertThat(result.responseBody()).isEqualTo("ok");
    }

    @Test
    @DisplayName("본문에 모르는 필드가 있어도 읽는다")
    void ignoresUnknownFields() {
        respond(200, "{\"requestId\":\"req-1\",\"status\":\"SUCCESS\",\"body\":\"ok\",\"extra\":\"x\"}");

        OutboundResult result = client.call(REQUEST);

        assertThat(result.kind()).isEqualTo(OutcomeKind.SUCCESS);
    }

    @Test
    @DisplayName("본문이 JSON 이 아니면 SERVER_ERROR 다")
    void returnsServerErrorWhenBodyIsNotJson() {
        respond(200, "not json");

        OutboundResult result = client.call(REQUEST);

        assertThat(result.kind()).isEqualTo(OutcomeKind.SERVER_ERROR);
    }

    @Test
    @DisplayName("POST /call 로 요청 ID 헤더와 본문을 보낸다")
    void sendsRequest() {
        respond(200, json("SUCCESS"));

        client.call(REQUEST);

        assertThat(receivedMethod).isEqualTo("POST");
        assertThat(receivedPath).isEqualTo("/call");
        assertThat(receivedRequestId).isEqualTo("req-1");
        assertThat(receivedContentType).startsWith("application/json");
        assertThat(receivedBody).isEqualTo(PAYLOAD);
    }

    @Test
    @DisplayName("응답 시간을 잰다")
    void measuresLatency() {
        respond(200, json("SUCCESS"));
        responseDelay = Duration.ofMillis(150);

        OutboundResult result = client.call(REQUEST);

        assertThat(result.latency()).isBetween(Duration.ofMillis(100), READ_TIMEOUT);
    }

    @Test
    @DisplayName("Retry-After 가 초 단위 숫자면 그만큼 뒤의 시각으로 바꾼다")
    void parsesRetryAfterSeconds() {
        respond(429, null);
        retryAfterHeader = "3";

        Instant before = Instant.now();
        OutboundResult result = client.call(REQUEST);
        Instant after = Instant.now();

        assertThat(result.retryAfter()).isBetween(before.plusSeconds(3), after.plusSeconds(3));
    }

    @Test
    @DisplayName("Retry-After 가 없으면 retryAfter 는 null 이다")
    void retryAfterIsNullWithoutHeader() {
        respond(429, null);

        OutboundResult result = client.call(REQUEST);

        assertThat(result.retryAfter()).isNull();
    }

    @Test
    @DisplayName("Retry-After 가 숫자가 아니면 retryAfter 는 null 이다")
    void retryAfterIsNullWhenNotNumeric() {
        respond(429, null);
        retryAfterHeader = "Wed, 21 Oct 2026 07:28:00 GMT";

        OutboundResult result = client.call(REQUEST);

        assertThat(result.retryAfter()).isNull();
    }

    @Test
    @DisplayName("읽기 제한 시간을 넘기면 TIMEOUT 이다")
    void returnsTimeoutWhenReadTimesOut() {
        respond(200, json("SUCCESS"));
        responseDelay = READ_TIMEOUT.plusMillis(700);

        OutboundResult result = client.call(REQUEST);

        assertThat(result.kind()).isEqualTo(OutcomeKind.TIMEOUT);
        assertThat(result.latency()).isBetween(READ_TIMEOUT.minusMillis(50), READ_TIMEOUT.plusMillis(500));
        assertThat(result.retryAfter()).isNull();
        assertThat(result.responseBody()).isNull();
    }

    @Test
    @DisplayName("연결이 거부되면 CONNECTION_FAILED 다")
    void returnsConnectionFailedWhenConnectionIsRefused() throws IOException {
        HttpTargetSystemClient refused = clientFor("http://127.0.0.1:" + closedPort());

        OutboundResult result = refused.call(REQUEST);

        assertThat(result.kind()).isEqualTo(OutcomeKind.CONNECTION_FAILED);
        assertThat(result.retryAfter()).isNull();
        assertThat(result.responseBody()).isNull();
    }

    @Test
    @DisplayName("연결 제한 시간을 넘기면 CONNECTION_FAILED 다")
    void returnsConnectionFailedWhenConnectTimesOut() {
        HttpTargetSystemClient unreachable = clientFor(
                "http://10.255.255.1:81", Duration.ofMillis(300), Duration.ofSeconds(3));

        OutboundResult result = unreachable.call(REQUEST);

        assertThat(result.kind()).isEqualTo(OutcomeKind.CONNECTION_FAILED);
        assertThat(result.latency()).isBetween(Duration.ofMillis(250), Duration.ofSeconds(2));
    }

    @Test
    @DisplayName("응답 없이 연결이 끊기면 NETWORK_ERROR 다")
    void returnsNetworkErrorWhenConnectionIsClosedWithoutResponse() throws Exception {
        try (ServerSocket dropper = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            Thread acceptor = new Thread(() -> {
                try {
                    dropper.accept().close();
                } catch (IOException ignored) {
                }
            });
            acceptor.start();
            HttpTargetSystemClient dropped = clientFor("http://127.0.0.1:" + dropper.getLocalPort());

            OutboundResult result = dropped.call(REQUEST);

            assertThat(result.kind()).isEqualTo(OutcomeKind.NETWORK_ERROR);
            acceptor.join(2000);
        }
    }

    @Test
    @DisplayName("설정에 없는 대상이면 예외가 난다")
    void rejectsUnknownTarget() {
        OutboundRequest unknown = new OutboundRequest("req-2", "target-z", PAYLOAD, Instant.now());

        assertThatThrownBy(() -> client.call(unknown))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private void respond(int status, String body) {
        responseStatus = status;
        responseBody = body;
    }

    private static String json(String status) {
        return "{\"requestId\":\"req-1\",\"status\":\"%s\",\"body\":\"ok\"}".formatted(status);
    }

    private static HttpTargetSystemClient clientFor(String url) {
        return clientFor(url, CONNECT_TIMEOUT, READ_TIMEOUT);
    }

    private static HttpTargetSystemClient clientFor(String url, Duration connectTimeout, Duration readTimeout) {
        TargetSystemProperties properties = new TargetSystemProperties(Map.of(
                TARGET, new TargetSystemProperties.TargetSystem(
                        url, connectTimeout, readTimeout,
                        100, Duration.ofMillis(100), 0.95, Duration.ofMillis(10))
        ));

        return new HttpTargetSystemClient(new RestClientConfig().targetSystemRestClients(properties));
    }

    private static int closedPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
