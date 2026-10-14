package com.hyuk.flow_control.adapter.in.web;

import com.hyuk.flow_control.domain.policy.ChangeNote;

public final class PolicyRequests {
    private PolicyRequests() {
    }

    public record Create(String targetSystemId, PolicyBody policy, String changedBy, String reason) {
        PolicyBody requiredPolicy() {
            return Required.of(policy, "policy");
        }

        ChangeNote note() {
            return new ChangeNote(changedBy, reason);
        }
    }

    public record Update(Long expectedVersion, PolicyBody policy, String changedBy, String reason) {
        PolicyBody requiredPolicy() {
            return Required.of(policy, "policy");
        }

        long requiredExpectedVersion() {
            return Required.of(expectedVersion, "expectedVersion");
        }

        ChangeNote note() {
            return new ChangeNote(changedBy, reason);
        }
    }

    public record ChangeEnabled(Boolean enabled, Long expectedVersion, String changedBy, String reason) {
        boolean requiredEnabled() {
            return Required.of(enabled, "enabled");
        }

        long requiredExpectedVersion() {
            return Required.of(expectedVersion, "expectedVersion");
        }

        ChangeNote note() {
            return new ChangeNote(changedBy, reason);
        }
    }

    public record Rollback(Long toVersion, Long expectedVersion, String changedBy, String reason) {
        long requiredToVersion() {
            return Required.of(toVersion, "toVersion");
        }

        long requiredExpectedVersion() {
            return Required.of(expectedVersion, "expectedVersion");
        }

        ChangeNote note() {
            return new ChangeNote(changedBy, reason);
        }
    }

    public record StartDryRun(Long expectedVersion, PolicyBody policy, String changedBy, String reason) {
        PolicyBody requiredPolicy() {
            return Required.of(policy, "policy");
        }

        long requiredExpectedVersion() {
            return Required.of(expectedVersion, "expectedVersion");
        }

        ChangeNote note() {
            return new ChangeNote(changedBy, reason);
        }
    }

    public record ApplyDryRun(String changedBy, String reason) {
        ChangeNote note() {
            return new ChangeNote(changedBy, reason);
        }
    }
}
