package com.hyuk.flow_control.application.port.in;

import com.hyuk.flow_control.domain.maintenance.MaintenanceSchedule;
import com.hyuk.flow_control.domain.policy.ConnectionSettings;
import com.hyuk.flow_control.domain.policy.ManagedPolicy;
import com.hyuk.flow_control.domain.policy.ResponseRuleSet;
import com.hyuk.flow_control.domain.policy.TargetSystemPolicy;

public record PolicyContent(
        boolean enabled,
        ConnectionSettings connection,
        TargetSystemPolicy limits,
        ResponseRuleSet response,
        MaintenanceSchedule maintenance
) {
    public PolicyContent(boolean enabled, ConnectionSettings connection, TargetSystemPolicy limits,
                         ResponseRuleSet response) {
        this(enabled, connection, limits, response, MaintenanceSchedule.none());
    }

    public ManagedPolicy toPolicy(String targetSystemId, long version) {
        return new ManagedPolicy(targetSystemId, version, enabled, connection, limits, response, maintenance);
    }
}
