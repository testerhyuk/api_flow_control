package com.hyuk.flow_control.support;

import com.hyuk.flow_control.domain.policy.ConnectionSettings;
import com.hyuk.flow_control.domain.policy.ManagedPolicy;
import com.hyuk.flow_control.domain.policy.ResponseRuleSet;
import com.hyuk.flow_control.domain.policy.TargetSystemPolicy;
import com.hyuk.flow_control.domain.request.OutcomeKind;

import java.time.Duration;
import java.util.Map;

public final class Policies {
    public static final ConnectionSettings CONNECTION =
            new ConnectionSettings("http://localhost:9001", Duration.ofSeconds(1), Duration.ofSeconds(3));
    public static final TargetSystemPolicy LIMITS =
            new TargetSystemPolicy(100, Duration.ofMillis(150), 0.95, Duration.ofMillis(10));
    public static final ResponseRuleSet STATUS_RULES = new ResponseRuleSet(
            Map.of("2xx", OutcomeKind.SUCCESS,
                    "400", OutcomeKind.INVALID_REQUEST,
                    "422", OutcomeKind.BUSINESS_REJECTED,
                    "429", OutcomeKind.TOO_MANY_REQUESTS,
                    "5xx", OutcomeKind.SERVER_ERROR,
                    "503", OutcomeKind.UNDER_MAINTENANCE),
            null, null);

    private Policies() {
    }

    public static ManagedPolicy policy(String targetSystemId) {
        return policy(targetSystemId, 1);
    }

    public static ManagedPolicy policy(String targetSystemId, long version) {
        return new ManagedPolicy(targetSystemId, version, true, CONNECTION, LIMITS, STATUS_RULES);
    }

    public static ManagedPolicy withLimits(ManagedPolicy policy, TargetSystemPolicy limits) {
        return new ManagedPolicy(policy.targetSystemId(), policy.version(), policy.enabled(),
                policy.connection(), limits, policy.response());
    }

    public static ManagedPolicy withConnection(ManagedPolicy policy, ConnectionSettings connection) {
        return new ManagedPolicy(policy.targetSystemId(), policy.version(), policy.enabled(),
                connection, policy.limits(), policy.response());
    }

    public static ManagedPolicy withResponse(ManagedPolicy policy, ResponseRuleSet response) {
        return new ManagedPolicy(policy.targetSystemId(), policy.version(), policy.enabled(),
                policy.connection(), policy.limits(), response);
    }
}
