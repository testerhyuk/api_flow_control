package com.hyuk.flow_control.adapter.out.http;

import com.hyuk.flow_control.application.port.out.TargetSystemClient;
import com.hyuk.flow_control.config.TargetSystemRestClients;
import com.hyuk.flow_control.domain.request.OutboundRequest;
import com.hyuk.flow_control.domain.request.OutboundResult;
import com.hyuk.flow_control.domain.request.OutcomeKind;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Duration;
import java.time.Instant;

@Component
public class HttpTargetSystemClient implements TargetSystemClient {
    private static final String REQUEST_ID_HEADER = "x-request-id";
    private final TargetSystemRestClients restClients;

    public HttpTargetSystemClient(TargetSystemRestClients restClients) {
        this.restClients = restClients;
    }

    @Override
    public OutboundResult call(OutboundRequest request) {
        RestClient restClient = restClients.get(request.targetSystemId());
        long startedAt = System.nanoTime();

        try {
            ResponseEntity<TargetSystemResponse> response = restClient.post()
                    .uri("/call")
                    .header(REQUEST_ID_HEADER, request.requestId())
                    .body(request.payload())
                    .retrieve()
                    .onStatus(status -> true, (req, res) -> {})
                    .toEntity(TargetSystemResponse.class);

            return new OutboundResult(
                    toOutcomeKind(response),
                    elapsedSince(startedAt),
                    parseRetryAfter(response),
                    bodyOf(response)
            );
        } catch (ResourceAccessException e) {
            return new OutboundResult(OutcomeKind.TIMEOUT, elapsedSince(startedAt), null, null);
        } catch (RestClientException e) {
            return new OutboundResult(OutcomeKind.SERVER_ERROR, elapsedSince(startedAt), null, null);
        }
    }

    private Duration elapsedSince(long startedAt) {
        return Duration.ofNanos(System.nanoTime() - startedAt);
    }

    private String bodyOf(ResponseEntity<TargetSystemResponse> response) {
        return response.getBody() == null ? null : response.getBody().body();
    }

    private OutcomeKind toOutcomeKind(ResponseEntity<TargetSystemResponse> response) {
        TargetSystemResponse payload = response.getBody();

        if (payload == null || payload.status() == null) {
            return fromHttpStatus(response.getStatusCode().value());
        }

        return switch (payload.status()) {
            case "SUCCESS" -> OutcomeKind.SUCCESS;
            case "REJECTED" -> OutcomeKind.BUSINESS_REJECTED;
            case "INVALID_REQUEST" -> OutcomeKind.INVALID_REQUEST;
            case "TOO_MANY_REQUESTS" -> OutcomeKind.TOO_MANY_REQUESTS;
            case "MAINTENANCE" -> OutcomeKind.UNDER_MAINTENANCE;
            case "SERVER_ERROR", "OVERLOADED" -> OutcomeKind.SERVER_ERROR;
            default -> fromHttpStatus(response.getStatusCode().value());
        };
    }

    private OutcomeKind fromHttpStatus(int statusCode) {
        if (statusCode == 429) return OutcomeKind.TOO_MANY_REQUESTS;
        if (statusCode == 400) return OutcomeKind.INVALID_REQUEST;
        if (statusCode >= 500) return OutcomeKind.SERVER_ERROR;
        return OutcomeKind.SUCCESS;
    }

    private Instant parseRetryAfter(ResponseEntity<TargetSystemResponse> response) {
        String header = response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER);

        if (header == null) return null;

        try {
            return Instant.now().plusSeconds(Long.parseLong(header.trim()));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
