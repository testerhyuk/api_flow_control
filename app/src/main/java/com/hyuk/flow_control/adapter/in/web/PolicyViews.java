package com.hyuk.flow_control.adapter.in.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.hyuk.flow_control.domain.policy.LoadedPolicy;
import com.hyuk.flow_control.domain.policy.ManagedPolicy;
import com.hyuk.flow_control.domain.policy.PolicyHistoryEntry;

import java.time.Instant;

public final class PolicyViews {
    private PolicyViews() {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Policy(String targetSystemId, long version, boolean valid, String error, PolicyBody policy) {
        static Policy from(ManagedPolicy policy) {
            return new Policy(policy.targetSystemId(), policy.version(), true, null, PolicyBody.from(policy));
        }

        static Policy from(LoadedPolicy loaded) {
            return switch (loaded) {
                case LoadedPolicy.Valid valid -> from(valid.policy());
                case LoadedPolicy.Invalid invalid ->
                        new Policy(invalid.targetSystemId(), invalid.version(), false, invalid.error(), null);
            };
        }
    }

    public record History(long version, Instant changedAt, String changedBy, String reason, PolicyBody policy) {
        static History from(PolicyHistoryEntry entry) {
            return new History(
                    entry.policy().version(),
                    entry.changedAt(),
                    entry.note().changedBy(),
                    entry.note().reason(),
                    PolicyBody.from(entry.policy()));
        }
    }
}
