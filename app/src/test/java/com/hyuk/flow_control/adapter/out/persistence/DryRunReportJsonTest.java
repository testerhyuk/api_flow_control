package com.hyuk.flow_control.adapter.out.persistence;

import com.hyuk.flow_control.domain.dryrun.DryRunReport;
import com.hyuk.flow_control.domain.health.HealthState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DryRunReportJsonTest {
    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    @Test
    @DisplayName("dry-run 결과를 DB 에 넣을 JSON 으로 바꾼다")
    void writesReport() {
        Instant now = Instant.parse("2026-11-02T00:00:00Z");
        DryRunReport report = new DryRunReport(now, now.plusSeconds(60), 120, 80, 50, Duration.ofSeconds(15),
                new DryRunReport.Limits(95.0, 47.5, 8, 2, 30, 5), List.of(), new DryRunReport.Timeouts(3, 0),
                new DryRunReport.Health(HealthState.HEALTHY, HealthState.DEGRADED, 1,
                        Map.of(HealthState.DEGRADED, Duration.ofSeconds(40)), 60, 0.05, Duration.ofMillis(210)),
                new DryRunReport.Maintenance(Duration.ofSeconds(30), 12, Duration.ofSeconds(30), Duration.ZERO),
                List.of("connect-timeout 은 계산하지 않았습니다"));

        JsonNode json = jsonMapper.readTree(JdbcDryRunRepository.reportJson(jsonMapper, report));

        assertThat(json.get("judgedCalls").asLong()).isEqualTo(80);
        assertThat(json.get("health").get("state").asString()).isEqualTo("DEGRADED");
        assertThat(json.get("maintenance").get("candidateCalls").asLong()).isEqualTo(12);
        assertThat(JdbcDryRunRepository.reportJson(jsonMapper, null)).isNull();
    }
}
