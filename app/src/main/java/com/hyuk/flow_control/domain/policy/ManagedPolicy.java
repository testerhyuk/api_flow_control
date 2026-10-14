package com.hyuk.flow_control.domain.policy;

import com.hyuk.flow_control.domain.maintenance.MaintenanceSchedule;

import java.util.regex.Pattern;

public record ManagedPolicy(
        String targetSystemId,
        long version,
        boolean enabled,
        ConnectionSettings connection,
        TargetSystemPolicy limits,
        ResponseRuleSet response,
        MaintenanceSchedule maintenance
) {
    private static final Pattern TARGET_SYSTEM_ID = Pattern.compile("[a-zA-Z0-9._-]{1,100}");

    public ManagedPolicy {
        if (targetSystemId == null || !TARGET_SYSTEM_ID.matcher(targetSystemId).matches()) {
            throw new IllegalArgumentException(
                    "대상 시스템 이름은 영문, 숫자, '.', '_', '-' 로 100자 이하여야 합니다 : " + targetSystemId);
        }
        if (version < 1) {
            throw new IllegalArgumentException("version 은 1 이상이어야 합니다");
        }
        if (connection == null) {
            throw new IllegalArgumentException("connection 은 필수입니다");
        }
        if (limits == null) {
            throw new IllegalArgumentException("limits 는 필수입니다");
        }
        if (response == null) {
            throw new IllegalArgumentException("response 는 필수입니다");
        }
        if (maintenance == null) {
            maintenance = MaintenanceSchedule.none();
        }
    }

    public ManagedPolicy(String targetSystemId, long version, boolean enabled, ConnectionSettings connection,
                         TargetSystemPolicy limits, ResponseRuleSet response) {
        this(targetSystemId, version, enabled, connection, limits, response, MaintenanceSchedule.none());
    }

    public ManagedPolicy withVersion(long newVersion) {
        return new ManagedPolicy(targetSystemId, newVersion, enabled, connection, limits, response, maintenance);
    }

    public ManagedPolicy withEnabled(boolean newEnabled) {
        return new ManagedPolicy(targetSystemId, version, newEnabled, connection, limits, response, maintenance);
    }
}
