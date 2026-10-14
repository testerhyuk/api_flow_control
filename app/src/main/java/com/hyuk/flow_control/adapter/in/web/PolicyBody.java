package com.hyuk.flow_control.adapter.in.web;

import com.hyuk.flow_control.application.port.in.PolicyContent;
import com.hyuk.flow_control.domain.maintenance.MaintenanceSchedule;
import com.hyuk.flow_control.domain.maintenance.OneTimeMaintenance;
import com.hyuk.flow_control.domain.maintenance.RecurringMaintenance;
import com.hyuk.flow_control.domain.policy.ConnectionSettings;
import com.hyuk.flow_control.domain.policy.ManagedPolicy;
import com.hyuk.flow_control.domain.policy.ResponseRuleSet;
import com.hyuk.flow_control.domain.policy.TargetSystemPolicy;
import com.hyuk.flow_control.domain.request.OutcomeKind;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

public record PolicyBody(
        Boolean enabled,
        Connection connection,
        Limits limits,
        Response response,
        Maintenance maintenance
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
                new Response(response.statusCodes(), response.resultField(), response.resultCodes()),
                Maintenance.from(policy.maintenance()));
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
                new ResponseRuleSet(response.statusCodes(), response.resultField(), response.resultCodes()),
                maintenance == null ? MaintenanceSchedule.none() : maintenance.toSchedule());
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

    public record Maintenance(List<Recurring> recurring, List<OneTime> oneTime) {
        static Maintenance from(MaintenanceSchedule schedule) {
            return new Maintenance(
                    schedule.recurring().stream()
                            .map(window -> new Recurring(window.days(), window.start(), window.end()))
                            .toList(),
                    schedule.oneTime().stream()
                            .map(window -> new OneTime(window.start(), window.end()))
                            .toList());
        }

        MaintenanceSchedule toSchedule() {
            return new MaintenanceSchedule(
                    recurring == null ? List.of() : recurring.stream()
                            .map(window -> new RecurringMaintenance(
                                    Required.of(window, "maintenance.recurring").days(),
                                    window.start(),
                                    window.end()))
                            .toList(),
                    oneTime == null ? List.of() : oneTime.stream()
                            .map(window -> new OneTimeMaintenance(
                                    Required.of(window, "maintenance.oneTime").start(),
                                    window.end()))
                            .toList());
        }
    }

    public record Recurring(Set<DayOfWeek> days, LocalTime start, LocalTime end) {
    }

    public record OneTime(LocalDateTime start, LocalDateTime end) {
    }
}
