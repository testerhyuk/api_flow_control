package com.hyuk.flow_control.adapter.in.web;

import com.hyuk.flow_control.domain.policy.ChangeNote;

import java.util.List;

public final class RequesterRequests {
    private RequesterRequests() {
    }

    public record Register(String requesterId, List<String> targetSystemIds, String reason) {
        List<String> requiredTargetSystemIds() {
            return Required.of(targetSystemIds, "targetSystemIds");
        }

        ChangeNote note(String changedBy) {
            return new ChangeNote(changedBy, reason);
        }
    }

    public record ChangeEnabled(Boolean enabled, Long expectedVersion, String reason) {
        boolean requiredEnabled() {
            return Required.of(enabled, "enabled");
        }

        long requiredExpectedVersion() {
            return Required.of(expectedVersion, "expectedVersion");
        }

        ChangeNote note(String changedBy) {
            return new ChangeNote(changedBy, reason);
        }
    }

    public record ChangeTarget(Long expectedVersion, String reason) {
        long requiredExpectedVersion() {
            return Required.of(expectedVersion, "expectedVersion");
        }

        ChangeNote note(String changedBy) {
            return new ChangeNote(changedBy, reason);
        }
    }
}
