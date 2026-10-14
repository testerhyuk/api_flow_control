package com.hyuk.flow_control.adapter.out.persistence;

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
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

final class PolicyColumns {
    private static final TypeReference<Map<String, String>> CODE_TABLE = new TypeReference<>() {
    };

    static final String NAMES = """
            target_system_id, enabled, url, connect_timeout_ms, read_timeout_ms,
            max_tps, baseline_rtt_ms, safety_margin, burst_window_ms,
            status_codes, result_field, result_codes""";

    static final String VALUES = """
            :targetSystemId, :enabled, :url, :connectTimeoutMs, :readTimeoutMs,
            :maxTps, :baselineRttMs, :safetyMargin, :burstWindowMs,
            cast(:statusCodes as jsonb), :resultField, cast(:resultCodes as jsonb)""";

    static final String ASSIGNMENTS = """
            enabled = :enabled, url = :url,
            connect_timeout_ms = :connectTimeoutMs, read_timeout_ms = :readTimeoutMs,
            max_tps = :maxTps, baseline_rtt_ms = :baselineRttMs, safety_margin = :safetyMargin,
            burst_window_ms = :burstWindowMs,
            status_codes = cast(:statusCodes as jsonb), result_field = :resultField,
            result_codes = cast(:resultCodes as jsonb)""";

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
                .param("resultCodes", response.resultCodes().isEmpty() ? null : toJson(response.resultCodes()));
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
                        fromJson(rs.getString("result_codes"))));
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
