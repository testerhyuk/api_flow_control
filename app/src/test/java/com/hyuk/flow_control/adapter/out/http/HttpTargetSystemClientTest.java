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
import tools.jackson.databind.json.JsonMapper;

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
    private static final String BODY_TARGET = "target-b";
    private static final OutboundRequest REQUEST = new OutboundRequest("req-1", TARGET, PAYLOAD, Instant.now());
    private static final OutboundRequest BODY_REQUEST = new OutboundRequest("req-2", BODY_TARGET, PAYLOAD, Instant.now());
    private static final TargetSystemProperties.Response STATUS_ONLY = new TargetSystemProperties.Response(
            Map.of("2xx", OutcomeKind.SUCCESS,
                    "400", OutcomeKind.INVALID_REQUEST,
                    "429", OutcomeKind.TOO_MANY_REQUESTS,
                    "5xx", OutcomeKind.SERVER_ERROR,
                    "503", OutcomeKind.UNDER_MAINTENANCE),
            null, null);
    private static final TargetSystemProperties.Response WITH_RESULT_CODE = new TargetSystemProperties.Response(
            Map.of("2xx", OutcomeKind.SUCCESS,
                    "429", OutcomeKind.TOO_MANY_REQUESTS,
                    "5xx", OutcomeKind.SERVER_ERROR),
            "/header/resultCode",
            Map.of("0000", OutcomeKind.SUCCESS,
                    "E100", OutcomeKind.BUSINESS_REJECTED,
                    "100", OutcomeKind.BUSINESS_REJECTED,
                    "E900", OutcomeKind.TOO_MANY_REQUESTS));

    private HttpServer server;
    private ExecutorService serverThreads;
    private HttpTargetSystemClient client;

    private volatile int responseStatus;
    private volatile String responseBody;
    private volatile String responseContentType;
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
        responseContentType = "application/json";
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
                exchange.getResponseHeaders().add("Content-Type", responseContentType);
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

    @ParameterizedTest(name = "HTTP {0} → {1}")
    @DisplayName("상태 코드 표대로 판단한다")
    @CsvSource({
            "200, SUCCESS",
            "204, SUCCESS",
            "400, INVALID_REQUEST",
            "429, TOO_MANY_REQUESTS",
            "500, SERVER_ERROR",
            "502, SERVER_ERROR",
            "503, UNDER_MAINTENANCE",
            "404, UNEXPECTED_RESPONSE",
            "401, UNEXPECTED_RESPONSE"
    })
    void judgesByStatusCodeTable(int httpStatus, OutcomeKind expected) {
        respond(httpStatus, null);

        OutboundResult result = client.call(REQUEST);

        assertThat(result.kind()).isEqualTo(expected);
        assertThat(result.statusCode()).isEqualTo(httpStatus);
        assertThat(result.resultCode()).isNull();
        assertThat(result.responseBody()).isNull();
    }

    @Test
    @DisplayName("결과 코드 위치가 없는 기관은 본문을 보지 않는다")
    void ignoresBodyWithoutResultField() {
        respond(200, "{\"status\":\"FAIL\"}");

        OutboundResult result = client.call(REQUEST);

        assertThat(result.kind()).isEqualTo(OutcomeKind.SUCCESS);
        assertThat(result.resultCode()).isNull();
    }

    @Test
    @DisplayName("본문이 JSON 이 아니어도 상태 코드로 판단한다")
    void judgesByStatusCodeWhenBodyIsNotJson() {
        respond(429, "Too Many Requests", "text/plain");

        OutboundResult result = client.call(REQUEST);

        assertThat(result.kind()).isEqualTo(OutcomeKind.TOO_MANY_REQUESTS);
    }

    @Test
    @DisplayName("응답 본문 원문을 그대로 돌려준다")
    void returnsRawResponseBody() {
        respond(200, "{\"anything\":\"ok\"}");

        OutboundResult result = client.call(REQUEST);

        assertThat(result.responseBody()).isEqualTo("{\"anything\":\"ok\"}");
    }

    @ParameterizedTest(name = "{0} → {1}")
    @DisplayName("결과 코드 위치가 있는 기관은 본문의 결과 코드 표대로 판단한다")
    @CsvSource({
            "0000, SUCCESS",
            "E100, BUSINESS_REJECTED",
            "E900, TOO_MANY_REQUESTS",
            "E777, UNEXPECTED_RESPONSE"
    })
    void judgesByResultCodeTable(String code, OutcomeKind expected) {
        respond(200, resultBody("\"" + code + "\""));

        OutboundResult result = client.call(BODY_REQUEST);

        assertThat(result.kind()).isEqualTo(expected);
        assertThat(result.statusCode()).isEqualTo(200);
        assertThat(result.resultCode()).isEqualTo(code);
    }

    @Test
    @DisplayName("본문의 결과 코드가 상태 코드보다 먼저다")
    void resultCodeWinsOverStatusCode() {
        respond(400, resultBody("\"E100\""));

        OutboundResult result = client.call(BODY_REQUEST);

        assertThat(result.kind()).isEqualTo(OutcomeKind.BUSINESS_REJECTED);
    }

    @Test
    @DisplayName("결과 코드가 숫자면 글자로 바꿔서 표와 비교한다")
    void comparesNumericResultCodeAsText() {
        respond(200, resultBody("100"));

        OutboundResult result = client.call(BODY_REQUEST);

        assertThat(result.kind()).isEqualTo(OutcomeKind.BUSINESS_REJECTED);
        assertThat(result.resultCode()).isEqualTo("100");
    }

    @ParameterizedTest(name = "{0}")
    @DisplayName("결과 코드를 찾지 못했는데 2xx 면 UNEXPECTED_RESPONSE 다")
    @CsvSource(delimiter = '|', value = {
            "{\"header\":{}}",
            "{\"header\":{\"resultCode\":null}}",
            "{\"header\":{\"resultCode\":{\"value\":\"0000\"}}}",
            "not json"
    })
    void unexpectedWhenResultCodeMissingOn2xx(String body) {
        respond(200, body);

        OutboundResult result = client.call(BODY_REQUEST);

        assertThat(result.kind()).isEqualTo(OutcomeKind.UNEXPECTED_RESPONSE);
        assertThat(result.resultCode()).isNull();
    }

    @Test
    @DisplayName("결과 코드를 찾지 못했는데 본문도 없고 2xx 면 UNEXPECTED_RESPONSE 다")
    void unexpectedWhenBodyIsEmptyOn2xx() {
        respond(200, null);

        OutboundResult result = client.call(BODY_REQUEST);

        assertThat(result.kind()).isEqualTo(OutcomeKind.UNEXPECTED_RESPONSE);
    }

    @Test
    @DisplayName("결과 코드를 찾지 못했는데 2xx 가 아니면 상태 코드로 판단한다")
    void judgesByStatusCodeWhenResultCodeMissing() {
        respond(503, "<html>Service Unavailable</html>", "text/html");

        OutboundResult result = client.call(BODY_REQUEST);

        assertThat(result.kind()).isEqualTo(OutcomeKind.SERVER_ERROR);
        assertThat(result.statusCode()).isEqualTo(503);
    }

    @Test
    @DisplayName("POST /call 로 요청 ID 헤더와 본문을 보낸다")
    void sendsRequest() {
        respond(200, null);

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
        respond(200, null);
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
    @DisplayName("Retry-After 가 날짜와 시각이면 그 시각을 그대로 쓴다")
    void parsesRetryAfterDate() {
        respond(429, null);
        retryAfterHeader = "Wed, 21 Oct 2026 07:28:00 GMT";

        OutboundResult result = client.call(REQUEST);

        assertThat(result.retryAfter()).isEqualTo(Instant.parse("2026-10-21T07:28:00Z"));
    }

    @Test
    @DisplayName("Retry-After 가 숫자도 날짜도 아니면 retryAfter 는 null 이다")
    void retryAfterIsNullWhenUnreadable() {
        respond(429, null);
        retryAfterHeader = "soon";

        OutboundResult result = client.call(REQUEST);

        assertThat(result.retryAfter()).isNull();
    }

    @Test
    @DisplayName("읽기 제한 시간을 넘기면 TIMEOUT 이다")
    void returnsTimeoutWhenReadTimesOut() {
        respond(200, null);
        responseDelay = READ_TIMEOUT.plusMillis(700);

        OutboundResult result = client.call(REQUEST);

        assertThat(result.kind()).isEqualTo(OutcomeKind.TIMEOUT);
        assertThat(result.latency()).isBetween(READ_TIMEOUT.minusMillis(50), READ_TIMEOUT.plusMillis(500));
        assertThat(result.retryAfter()).isNull();
        assertThat(result.responseBody()).isNull();
        assertThat(result.statusCode()).isNull();
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
        respond(status, body, "application/json");
    }

    private void respond(int status, String body, String contentType) {
        responseStatus = status;
        responseBody = body;
        responseContentType = contentType;
    }

    private static String resultBody(String code) {
        return "{\"header\":{\"resultCode\":%s},\"body\":{}}".formatted(code);
    }

    private static HttpTargetSystemClient clientFor(String url) {
        return clientFor(url, CONNECT_TIMEOUT, READ_TIMEOUT);
    }

    private static HttpTargetSystemClient clientFor(String url, Duration connectTimeout, Duration readTimeout) {
        TargetSystemProperties properties = new TargetSystemProperties(Map.of(
                TARGET, new TargetSystemProperties.TargetSystem(
                        url, connectTimeout, readTimeout,
                        100, Duration.ofMillis(100), 0.95, Duration.ofMillis(10), STATUS_ONLY),
                BODY_TARGET, new TargetSystemProperties.TargetSystem(
                        url, connectTimeout, readTimeout,
                        100, Duration.ofMillis(100), 0.95, Duration.ofMillis(10), WITH_RESULT_CODE)
        ));
        RestClientConfig config = new RestClientConfig();

        return new HttpTargetSystemClient(
                config.targetSystemRestClients(properties),
                config.targetSystemResponseRules(properties),
                JsonMapper.builder().build());
    }

    private static int closedPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
