package com.hyuk.flow_control.adapter.in.web;

import com.hyuk.flow_control.application.port.in.DryRunStatus;
import com.hyuk.flow_control.application.port.in.DryRunUseCase;
import com.hyuk.flow_control.application.port.in.PolicyContent;
import com.hyuk.flow_control.domain.dryrun.DryRun;
import com.hyuk.flow_control.domain.dryrun.DryRunAlreadyRunningException;
import com.hyuk.flow_control.domain.dryrun.DryRunNotFoundException;
import com.hyuk.flow_control.domain.dryrun.DryRunReport;
import com.hyuk.flow_control.domain.health.HealthState;
import com.hyuk.flow_control.domain.policy.ChangeNote;
import com.hyuk.flow_control.domain.request.OutcomeKind;
import com.hyuk.flow_control.support.Policies;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DryRunAdminControllerTest {
    private static final Instant START = Instant.parse("2026-10-14T00:00:00Z");
    private static final ChangeNote NOTE = new ChangeNote("hyuk", "baseline 조정");
    private static final DryRun DRY_RUN =
            new DryRun(Policies.policy("target-a", 4), 3, START, START.plus(Duration.ofMinutes(30)), NOTE);
    private static final String BODY = """
            {
              "expectedVersion": 3,
              "policy": {
                "enabled": true,
                "connection": {"url": "http://localhost:9001", "connectTimeoutMs": 1000, "readTimeoutMs": 3000},
                "limits": {"maxTps": 100, "baselineRttMs": 90, "safetyMargin": 0.95, "burstWindowMs": 10},
                "response": {"statusCodes": {"2xx": "SUCCESS"}}
              },
              "changedBy": "hyuk",
              "reason": "baseline 조정"
            }""";

    private DryRunUseCase dryRuns;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        dryRuns = mock(DryRunUseCase.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new DryRunAdminController(dryRuns))
                .setControllerAdvice(new PolicyAdminExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("시작하면 201 과 저장된 dry-run 을 돌려준다")
    void starts() throws Exception {
        when(dryRuns.start(eq("target-a"), any(PolicyContent.class), eq(3L), eq(NOTE))).thenReturn(DRY_RUN);

        mockMvc.perform(post("/admin/policies/target-a/dry-run").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.baseVersion").value(3))
                .andExpect(jsonPath("$.startedBy").value("hyuk"))
                .andExpect(jsonPath("$.result").doesNotExist());
    }

    @Test
    @DisplayName("이미 진행 중이면 409 다")
    void rejectsSecond() throws Exception {
        when(dryRuns.start(eq("target-a"), any(), eq(3L), any()))
                .thenThrow(new DryRunAlreadyRunningException("target-a"));

        mockMvc.perform(post("/admin/policies/target-a/dry-run").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("url 이나 enabled 를 바꾸려 하면 400 이다")
    void rejectsUnsupportedChange() throws Exception {
        when(dryRuns.start(eq("target-a"), any(), eq(3L), any()))
                .thenThrow(new IllegalArgumentException("url 을 바꾸는 정책은 dry-run 할 수 없습니다"));

        mockMvc.perform(post("/admin/policies/target-a/dry-run").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("조회하면 이 서버의 계산 결과를 함께 보여준다")
    void getsResult() throws Exception {
        DryRunReport report = new DryRunReport(
                START, START.plusSeconds(60), 120, Duration.ofSeconds(15),
                new DryRunReport.Limits(95.0, 47.5, 8, 2, 30, 5),
                List.of(new DryRunReport.JudgmentChange(OutcomeKind.BUSINESS_REJECTED, OutcomeKind.SERVER_ERROR, 4)),
                new DryRunReport.Timeouts(3, 0),
                new DryRunReport.Health(HealthState.HEALTHY, HealthState.DEGRADED, 1,
                        Map.of(HealthState.HEALTHY, Duration.ofSeconds(20), HealthState.DEGRADED, Duration.ofSeconds(40)),
                        60, 0.05, Duration.ofMillis(210)),
                List.of("connect-timeout 은 계산하지 않았습니다"));
        when(dryRuns.get("target-a")).thenReturn(new DryRunStatus(DRY_RUN, report));

        mockMvc.perform(get("/admin/policies/target-a/dry-run"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.observedCalls").value(120))
                .andExpect(jsonPath("$.result.overlapMs").value(15000))
                .andExpect(jsonPath("$.result.limits.wouldBlockByTokenBucket").value(30))
                .andExpect(jsonPath("$.result.limits.waitedByCurrentLimit").value(5))
                .andExpect(jsonPath("$.result.judgmentChanges[0].candidate").value("SERVER_ERROR"))
                .andExpect(jsonPath("$.result.timeouts.wouldTimeout").value(3))
                .andExpect(jsonPath("$.result.health.state").value("DEGRADED"))
                .andExpect(jsonPath("$.result.health.timeInStateMs.DEGRADED").value(40000))
                .andExpect(jsonPath("$.result.health.p95Ms").value(210))
                .andExpect(jsonPath("$.result.unknowns[0]").value("connect-timeout 은 계산하지 않았습니다"));
    }

    @Test
    @DisplayName("진행 중인 dry-run 이 없으면 404 다")
    void getMissing() throws Exception {
        when(dryRuns.get("target-a")).thenThrow(new DryRunNotFoundException("target-a"));

        mockMvc.perform(get("/admin/policies/target-a/dry-run")).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("취소하면 204 다")
    void cancels() throws Exception {
        mockMvc.perform(delete("/admin/policies/target-a/dry-run")).andExpect(status().isNoContent());

        verify(dryRuns).cancel("target-a");
    }

    @Test
    @DisplayName("없는 dry-run 을 취소하면 404 다")
    void cancelMissing() throws Exception {
        doThrow(new DryRunNotFoundException("target-a")).when(dryRuns).cancel("target-a");

        mockMvc.perform(delete("/admin/policies/target-a/dry-run")).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("적용하면 저장된 새 정책을 돌려준다")
    void applies() throws Exception {
        when(dryRuns.apply("target-a", new ChangeNote("hyuk", "적용"))).thenReturn(Policies.policy("target-a", 4));

        mockMvc.perform(post("/admin/policies/target-a/dry-run/apply").contentType(MediaType.APPLICATION_JSON).content("""
                        {"changedBy": "hyuk", "reason": "적용"}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(4));
    }
}
