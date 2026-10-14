package com.hyuk.mock_target.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "mock-target.target=target-a",
                "management.server.port=0",
                "mock-target.systems.target-a.base-processing-millis=1",
                "mock-target.systems.target-a.max-tps=1",
                "mock-target.systems.target-a.server-error-rate=0",
                "mock-target.systems.target-a.business-reject-rate=0"
        })
class ResultCodeFormatHttpTest {
    private final HttpClient httpClient = HttpClient.newHttpClient();

    @Value("${local.server.port}")
    private int serverPort;

    @Test
    @DisplayName("결과 코드 방식 대상은 초당 한도를 넘으면 HTTP 200 에 E429 와 Retry-After 로 응답한다")
    void rateLimitedWithResultCode() throws Exception {
        List<HttpResponse<String>> responses = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            responses.add(httpClient.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + serverPort + "/call"))
                    .header("x-request-id", "req-" + i)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{}"))
                    .build(), HttpResponse.BodyHandlers.ofString()));
        }

        assertThat(responses).allMatch(response -> response.statusCode() == 200);
        assertThat(responses).anyMatch(response -> response.body().contains("\"resultCode\":\"0000\""));
        assertThat(responses)
                .filteredOn(response -> response.body().contains("\"resultCode\":\"E429\""))
                .isNotEmpty()
                .allMatch(response -> response.headers().firstValue("Retry-After").orElse("").equals("10"));
    }
}
