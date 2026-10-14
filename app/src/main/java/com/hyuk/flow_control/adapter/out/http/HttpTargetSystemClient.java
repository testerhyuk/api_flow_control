package com.hyuk.flow_control.adapter.out.http;

import com.hyuk.flow_control.application.port.out.TargetSystemClient;
import com.hyuk.flow_control.domain.request.OutboundRequest;
import com.hyuk.flow_control.domain.request.OutboundResult;
import com.hyuk.flow_control.domain.request.OutcomeKind;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonPointer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.ConnectException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

@Component
public class HttpTargetSystemClient implements TargetSystemClient {
    private static final String REQUEST_ID_HEADER = "x-request-id";

    private final TargetSystemRestClients restClients;
    private final TargetSystemResponseRules responseRules;
    private final JsonMapper jsonMapper;

    public HttpTargetSystemClient(TargetSystemRestClients restClients,
                                  TargetSystemResponseRules responseRules,
                                  JsonMapper jsonMapper) {
        this.restClients = restClients;
        this.responseRules = responseRules;
        this.jsonMapper = jsonMapper;
    }

    @Override
    public OutboundResult call(OutboundRequest request) {
        RestClient restClient = restClients.get(request.targetSystemId());
        ResponseRules rules = responseRules.get(request.targetSystemId());
        long startedAt = System.nanoTime();

        try {
            ResponseEntity<String> response = restClient.post()
                    .uri("/call")
                    .header(REQUEST_ID_HEADER, request.requestId())
                    .body(request.payload())
                    .retrieve()
                    .onStatus(status -> true, (req, res) -> {})
                    .toEntity(String.class);

            int statusCode = response.getStatusCode().value();
            String body = response.getBody();
            String resultCode = rules.readsResultCode() ? resultCodeOf(body, rules.resultField()) : null;

            return new OutboundResult(
                    rules.judge(statusCode, resultCode),
                    elapsedSince(startedAt),
                    parseRetryAfter(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)),
                    body,
                    statusCode,
                    resultCode
            );
        } catch (ResourceAccessException e) {
            return new OutboundResult(fromFailureCause(e.getCause()), elapsedSince(startedAt), null, null, null, null);
        } catch (RestClientException e) {
            return new OutboundResult(OutcomeKind.SERVER_ERROR, elapsedSince(startedAt), null, null, null, null);
        }
    }

    private String resultCodeOf(String body, JsonPointer resultField) {
        if (body == null || body.isBlank()) {
            return null;
        }

        try {
            JsonNode value = jsonMapper.readTree(body).at(resultField);

            if (value.isMissingNode() || value.isNull() || value.isObject() || value.isArray()) {
                return null;
            }

            return value.asString();
        } catch (JacksonException e) {
            return null;
        }
    }

    private Duration elapsedSince(long startedAt) {
        return Duration.ofNanos(System.nanoTime() - startedAt);
    }

    private OutcomeKind fromFailureCause(Throwable cause) {
        if (cause instanceof ConnectException || cause instanceof HttpConnectTimeoutException) {
            return OutcomeKind.CONNECTION_FAILED;
        }
        if (cause instanceof HttpTimeoutException) {
            return OutcomeKind.TIMEOUT;
        }
        return OutcomeKind.NETWORK_ERROR;
    }

    private Instant parseRetryAfter(String header) {
        if (header == null || header.isBlank()) {
            return null;
        }

        String value = header.trim();

        try {
            return Instant.now().plusSeconds(Long.parseLong(value));
        } catch (NumberFormatException ignored) {
        }

        try {
            return ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
