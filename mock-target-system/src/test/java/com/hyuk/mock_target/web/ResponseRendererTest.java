package com.hyuk.mock_target.web;

import com.hyuk.mock_target.config.ResponseFormat;
import com.hyuk.mock_target.simulation.CallResult;
import com.hyuk.mock_target.simulation.Outcome;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class ResponseRendererTest {
    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private final ResponseRenderer renderer = new ResponseRenderer(jsonMapper);

    @ParameterizedTest(name = "{0} → HTTP {1}")
    @DisplayName("상태 코드 방식은 결과를 HTTP 상태 코드로 알려 주고 결과 코드는 넣지 않는다")
    @CsvSource({
            "SUCCESS, 200",
            "BUSINESS_REJECTED, 422",
            "RATE_LIMITED, 429",
            "SERVER_ERROR, 500",
            "MAINTENANCE, 503",
            "INVALID_REQUEST, 400"
    })
    void statusCodeFormat(Outcome outcome, int status) {
        ResponseEntity<String> response = renderer.render(CallResult.of(outcome), "req-1", ResponseFormat.STATUS_CODE);

        assertThat(response.getStatusCode().value()).isEqualTo(status);
        JsonNode body = jsonMapper.readTree(response.getBody());
        assertThat(body.has("resultCode")).isFalse();
        assertThat(body.get("requestId").asString()).isEqualTo("req-1");
    }

    @ParameterizedTest(name = "{0} → HTTP {1} + {2}")
    @DisplayName("결과 코드 방식은 결과를 본문의 resultCode 로 알려 준다")
    @CsvSource({
            "SUCCESS, 200, 0000",
            "BUSINESS_REJECTED, 200, E100",
            "RATE_LIMITED, 200, E429",
            "SERVER_ERROR, 200, E500",
            "MAINTENANCE, 503, E900"
    })
    void resultCodeFormat(Outcome outcome, int status, String resultCode) {
        ResponseEntity<String> response = renderer.render(CallResult.of(outcome), "req-1", ResponseFormat.RESULT_CODE);

        assertThat(response.getStatusCode().value()).isEqualTo(status);
        assertThat(jsonMapper.readTree(response.getBody()).get("resultCode").asString()).isEqualTo(resultCode);
    }

    @Test
    @DisplayName("결과 코드 방식에서도 잘못된 요청은 결과 코드 없이 400 이다")
    void invalidRequestHasNoResultCode() {
        ResponseEntity<String> response = renderer.render(
                CallResult.of(Outcome.INVALID_REQUEST), "req-1", ResponseFormat.RESULT_CODE);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(jsonMapper.readTree(response.getBody()).has("resultCode")).isFalse();
    }

    @Test
    @DisplayName("과부하는 두 방식 모두 본문 없이 503 이다")
    void overloadedHasNoBody() {
        for (ResponseFormat format : ResponseFormat.values()) {
            ResponseEntity<String> response = renderer.render(CallResult.of(Outcome.OVERLOADED), "req-1", format);

            assertThat(response.getStatusCode().value()).isEqualTo(503);
            assertThat(response.getBody()).isNull();
            assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isNull();
        }
    }

    @Test
    @DisplayName("Retry-After 가 있으면 초 단위로 헤더에 넣는다")
    void writesRetryAfter() {
        ResponseEntity<String> response = renderer.render(
                new CallResult(Outcome.RATE_LIMITED, 10L), "req-1", ResponseFormat.STATUS_CODE);

        assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("10");
    }
}
