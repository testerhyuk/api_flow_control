package com.hyuk.mock_target.web;

import com.hyuk.mock_target.config.ResponseFormat;
import com.hyuk.mock_target.simulation.CallResult;
import com.hyuk.mock_target.simulation.Outcome;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class ResponseRenderer {
    private final JsonMapper jsonMapper;

    public ResponseRenderer(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    public ResponseEntity<String> render(CallResult result, String requestId, ResponseFormat format) {
        Outcome outcome = result.outcome();

        if (outcome == Outcome.OVERLOADED) {
            return ResponseEntity.status(503).build();
        }

        int status = format == ResponseFormat.STATUS_CODE ? statusCodeOf(outcome) : resultFormatStatusOf(outcome);
        String resultCode = format == ResponseFormat.RESULT_CODE ? resultCodeOf(outcome) : null;

        ResponseEntity.BodyBuilder builder = ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON);

        if (result.retryAfterSeconds() != null) {
            builder.header(HttpHeaders.RETRY_AFTER, String.valueOf(result.retryAfterSeconds()));
        }

        return builder.body(body(requestId, resultCode, messageOf(outcome)));
    }

    private String body(String requestId, String resultCode, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (resultCode != null) {
            body.put("resultCode", resultCode);
        }
        body.put("requestId", requestId);
        body.put("message", message);

        return jsonMapper.writeValueAsString(body);
    }

    private static int statusCodeOf(Outcome outcome) {
        return switch (outcome) {
            case SUCCESS -> 200;
            case BUSINESS_REJECTED -> 422;
            case RATE_LIMITED -> 429;
            case SERVER_ERROR -> 500;
            case MAINTENANCE, OVERLOADED -> 503;
            case INVALID_REQUEST -> 400;
        };
    }

    private static int resultFormatStatusOf(Outcome outcome) {
        return switch (outcome) {
            case SUCCESS, BUSINESS_REJECTED, RATE_LIMITED, SERVER_ERROR -> 200;
            case MAINTENANCE, OVERLOADED -> 503;
            case INVALID_REQUEST -> 400;
        };
    }

    private static String resultCodeOf(Outcome outcome) {
        return switch (outcome) {
            case SUCCESS -> "0000";
            case BUSINESS_REJECTED -> "E100";
            case RATE_LIMITED -> "E429";
            case SERVER_ERROR -> "E500";
            case MAINTENANCE -> "E900";
            case OVERLOADED, INVALID_REQUEST -> null;
        };
    }

    private static String messageOf(Outcome outcome) {
        return switch (outcome) {
            case SUCCESS -> "처리되었습니다";
            case BUSINESS_REJECTED -> "업무 규칙에 따라 거절되었습니다";
            case RATE_LIMITED -> "초당 요청 한도를 넘었습니다";
            case SERVER_ERROR -> "서버 오류가 발생했습니다";
            case MAINTENANCE -> "점검 중입니다";
            case OVERLOADED -> "요청이 너무 많습니다";
            case INVALID_REQUEST -> "요청 본문이 올바르지 않습니다";
        };
    }
}
