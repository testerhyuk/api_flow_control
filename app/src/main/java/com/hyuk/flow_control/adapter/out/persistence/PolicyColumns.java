package com.hyuk.flow_control.adapter.out.persistence;

import com.hyuk.flow_control.domain.maintenance.MaintenanceSchedule;
import com.hyuk.flow_control.domain.maintenance.OneTimeMaintenance;
import com.hyuk.flow_control.domain.maintenance.RecurringMaintenance;
import com.hyuk.flow_control.domain.policy.ConnectionSettings;
import com.hyuk.flow_control.domain.policy.ManagedPolicy;
import com.hyuk.flow_control.domain.policy.ResponseRuleSet;
import com.hyuk.flow_control.domain.policy.TargetSystemPolicy;
import com.hyuk.flow_control.domain.request.OutcomeKind;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

final class PolicyColumns {
    private static final TypeReference<Map<String, String>> CODE_TABLE = new TypeReference<>() {
    };

    static final String NAMES = """
            target_system_id, enabled, url, connect_timeout_ms, read_timeout_ms,
            max_tps, baseline_rtt_ms, safety_margin, burst_window_ms,
            status_codes, result_field, result_codes, maintenance""";

    static final String VALUES = """
            :targetSystemId, :enabled, :url, :connectTimeoutMs, :readTimeoutMs,
            :maxTps, :baselineRttMs, :safetyMargin, :burstWindowMs,
            cast(:statusCodes as jsonb), :resultField, cast(:resultCodes as jsonb),
            cast(:maintenance as jsonb)""";

    static final String ASSIGNMENTS = """
            enabled = :enabled, url = :url,
            connect_timeout_ms = :connectTimeoutMs, read_timeout_ms = :readTimeoutMs,
            max_tps = :maxTps, baseline_rtt_ms = :baselineRttMs, safety_margin = :safetyMargin,
            burst_window_ms = :burstWindowMs,
            status_codes = cast(:statusCodes as jsonb), result_field = :resultField,
            result_codes = cast(:resultCodes as jsonb),
            maintenance = cast(:maintenance as jsonb)""";

    private final JsonMapper jsonMapper;

    PolicyColumns(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    JdbcClient.StatementSpec bind(JdbcClient.StatementSpec statement, ManagedPolicy policy) {
        ResponseRuleSet response = policy.response();

        return statement
                .param("targetSystemId", policy.targetSystemId())
                .param("enabled", policy.enabled())
                .param("url", policy.connection().url())
                .param("connectTimeoutMs", policy.connection().connectTimeout().toMillis())
                .param("readTimeoutMs", policy.connection().readTimeout().toMillis())
                .param("maxTps", policy.limits().maxTps())
                .param("baselineRttMs", policy.limits().baselineRtt().toMillis())
                .param("safetyMargin", policy.limits().safetyMargin())
                .param("burstWindowMs", policy.limits().burstWindow().toMillis())
                .param("statusCodes", toJson(response.statusCodes()))
                .param("resultField", response.resultField())
                .param("resultCodes", response.resultCodes().isEmpty() ? null : toJson(response.resultCodes()))
                .param("maintenance", toJson(policy.maintenance()));
    }

    ManagedPolicy read(ResultSet rs, long version) throws SQLException {
        return new ManagedPolicy(
                rs.getString("target_system_id"),
                version,
                rs.getBoolean("enabled"),
                new ConnectionSettings(
                        rs.getString("url"),
                        Duration.ofMillis(rs.getLong("connect_timeout_ms")),
                        Duration.ofMillis(rs.getLong("read_timeout_ms"))),
                new TargetSystemPolicy(
                        rs.getInt("max_tps"),
                        Duration.ofMillis(rs.getLong("baseline_rtt_ms")),
                        rs.getDouble("safety_margin"),
                        Duration.ofMillis(rs.getLong("burst_window_ms"))),
                new ResponseRuleSet(
                        fromJson(rs.getString("status_codes")),
                        rs.getString("result_field"),
                        fromJson(rs.getString("result_codes"))),
                maintenanceOf(rs.getString("maintenance")));
    }

    private String toJson(MaintenanceSchedule schedule) {
        return jsonMapper.writeValueAsString(new StoredMaintenance(
                schedule.recurring().stream()
                        .map(window -> new StoredRecurring(
                                window.days().stream().map(DayOfWeek::name).toList(),
                                window.start().toString(),
                                window.end().toString()))
                        .toList(),
                schedule.oneTime().stream()
                        .map(window -> new StoredOneTime(window.start().toString(), window.end().toString()))
                        .toList()));
    }

    private MaintenanceSchedule maintenanceOf(String json) {
        if (json == null) {
            return MaintenanceSchedule.none();
        }

        StoredMaintenance stored = jsonMapper.readValue(json, StoredMaintenance.class);

        return new MaintenanceSchedule(
                stored.recurring() == null ? List.of() : stored.recurring().stream()
                        .map(window -> new RecurringMaintenance(
                                days(window.days()),
                                parse(window.start(), LocalTime::parse),
                                parse(window.end(), LocalTime::parse)))
                        .toList(),
                stored.oneTime() == null ? List.of() : stored.oneTime().stream()
                        .map(window -> new OneTimeMaintenance(
                                parse(window.start(), LocalDateTime::parse),
                                parse(window.end(), LocalDateTime::parse)))
                        .toList());
    }

    private static <T> T parse(String value, Function<String, T> parser) {
        if (value == null) {
            throw new IllegalArgumentException("점검 시각이 비어 있습니다");
        }
        try {
            return parser.apply(value);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("점검 시각 형식이 잘못되었습니다 : " + value, e);
        }
    }

    private static EnumSet<DayOfWeek> days(List<String> names) {
        if (names == null || names.isEmpty()) {
            throw new IllegalArgumentException("반복 점검의 요일이 비어 있습니다");
        }

        EnumSet<DayOfWeek> days = EnumSet.noneOf(DayOfWeek.class);
        names.forEach(name -> days.add(DayOfWeek.valueOf(name)));
        return days;
    }

    record StoredMaintenance(List<StoredRecurring> recurring, List<StoredOneTime> oneTime) {
    }

    record StoredRecurring(List<String> days, String start, String end) {
    }

    record StoredOneTime(String start, String end) {
    }

    private String toJson(Map<String, OutcomeKind> codes) {
        Map<String, String> names = new LinkedHashMap<>();
        codes.forEach((code, kind) -> names.put(code, kind.name()));
        return jsonMapper.writeValueAsString(names);
    }

    private Map<String, OutcomeKind> fromJson(String json) {
        if (json == null) {
            return Map.of();
        }

        Map<String, OutcomeKind> codes = new LinkedHashMap<>();
        jsonMapper.readValue(json, CODE_TABLE).forEach((code, kind) -> {
            if (kind == null) {
                throw new IllegalArgumentException("결과가 비어 있습니다 : " + code);
            }
            codes.put(code, OutcomeKind.valueOf(kind));
        });
        return codes;
    }
}
