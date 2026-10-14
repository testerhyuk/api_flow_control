package com.hyuk.flow_control.adapter.in.web;

import com.hyuk.flow_control.application.port.in.PolicyContent;
import com.hyuk.flow_control.domain.policy.ConnectionSettings;
import com.hyuk.flow_control.domain.policy.ManagedPolicy;
import com.hyuk.flow_control.domain.policy.ResponseRuleSet;
import com.hyuk.flow_control.domain.policy.TargetSystemPolicy;
import com.hyuk.flow_control.domain.request.OutcomeKind;

import java.time.Duration;
import java.util.Map;

public record PolicyBody(
        Boolean enabled,
        Connection connection,
        Limits limits,
        Response response
) {
    public static PolicyBody from(ManagedPolicy policy) {
        ConnectionSettings connection = policy.connection();
        TargetSystemPolicy limits = policy.limits();
        ResponseRuleSet response = policy.response();

        return new PolicyBody(
                policy.enabled(),
                new Connection(connection.url(),
                        connection.connectTimeout().toMillis(),
                        connection.readTimeout().toMillis()),
                new Limits(limits.maxTps(),
                        limits.baselineRtt().toMillis(),
                        limits.safetyMargin(),
                        limits.burstWindow().toMillis()),
                new Response(response.statusCodes(), response.resultField(), response.resultCodes()));
    }

    public PolicyContent toContent() {
        Connection connection = Required.of(this.connection, "connection");
        Limits limits = Required.of(this.limits, "limits");
        Response response = Required.of(this.response, "response");

        return new PolicyContent(
                Required.of(enabled, "enabled"),
                new ConnectionSettings(
                        connection.url(),
                        millis(connection.connectTimeoutMs(), "connection.connectTimeoutMs"),
                        millis(connection.readTimeoutMs(), "connection.readTimeoutMs")),
                new TargetSystemPolicy(
                        Required.of(limits.maxTps(), "limits.maxTps"),
                        millis(limits.baselineRttMs(), "limits.baselineRttMs"),
                        Required.of(limits.safetyMargin(), "limits.safetyMargin"),
                        millis(limits.burstWindowMs(), "limits.burstWindowMs")),
                new ResponseRuleSet(response.statusCodes(), response.resultField(), response.resultCodes()));
    }

    private static Duration millis(Long value, String name) {
        return Duration.ofMillis(Required.of(value, name));
    }

    public record Connection(String url, Long connectTimeoutMs, Long readTimeoutMs) {
    }

    public record Limits(Integer maxTps, Long baselineRttMs, Double safetyMargin, Long burstWindowMs) {
    }

    public record Response(
            Map<String, OutcomeKind> statusCodes,
            String resultField,
            Map<String, OutcomeKind> resultCodes
    ) {
    }
}
