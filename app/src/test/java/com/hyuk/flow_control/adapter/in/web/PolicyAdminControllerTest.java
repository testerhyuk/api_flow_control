package com.hyuk.flow_control.adapter.in.web;

import com.hyuk.flow_control.application.port.in.PolicyContent;
import com.hyuk.flow_control.application.port.in.PolicyManagementUseCase;
import com.hyuk.flow_control.domain.maintenance.MaintenanceSchedule;
import com.hyuk.flow_control.domain.maintenance.OneTimeMaintenance;
import com.hyuk.flow_control.domain.maintenance.RecurringMaintenance;
import com.hyuk.flow_control.domain.policy.ChangeNote;
import com.hyuk.flow_control.domain.policy.LoadedPolicy;
import com.hyuk.flow_control.domain.policy.ManagedPolicy;
import com.hyuk.flow_control.domain.policy.PolicyAlreadyExistsException;
import com.hyuk.flow_control.domain.policy.PolicyHistoryEntry;
import com.hyuk.flow_control.domain.policy.PolicyNotFoundException;
import com.hyuk.flow_control.domain.policy.PolicyVersionConflictException;
import com.hyuk.flow_control.support.Policies;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PolicyAdminControllerTest {
    private static final String POLICY_JSON = """
            {
              "enabled": true,
              "connection": {"url": "http://localhost:9001", "connectTimeoutMs": 1000, "readTimeoutMs": 3000},
              "limits": {"maxTps": 80, "baselineRttMs": 150, "safetyMargin": 0.95, "burstWindowMs": 10},
              "response": {
                "statusCodes": {"2xx": "SUCCESS", "5xx": "SERVER_ERROR"},
                "resultField": "/resultCode",
                "resultCodes": {"0000": "SUCCESS"}
              }
            }""";

    private PolicyManagementUseCase management;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        management = mock(PolicyManagementUseCase.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new PolicyAdminController(management))
                .setControllerAdvice(new PolicyAdminExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("목록은 올바른 정책과 잘못된 정책을 함께 보여준다")
    void lists() throws Exception {
        when(management.list()).thenReturn(List.of(
                new LoadedPolicy.Valid(Policies.policy("target-a", 3)),
                new LoadedPolicy.Invalid("target-b", 2, "max-tps")));

        mockMvc.perform(get("/admin/policies"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].targetSystemId").value("target-a"))
                .andExpect(jsonPath("$[0].version").value(3))
                .andExpect(jsonPath("$[0].valid").value(true))
                .andExpect(jsonPath("$[0].policy.limits.maxTps").value(100))
                .andExpect(jsonPath("$[0].policy.connection.readTimeoutMs").value(3000))
                .andExpect(jsonPath("$[1].valid").value(false))
                .andExpect(jsonPath("$[1].error").value("max-tps"))
                .andExpect(jsonPath("$[1].policy").doesNotExist());
    }

    @Test
    @DisplayName("없는 정책을 조회하면 404 다")
    void getMissing() throws Exception {
        when(management.get("target-z")).thenThrow(new PolicyNotFoundException("target-z"));

        mockMvc.perform(get("/admin/policies/target-z"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").exists());
    }

    @Test
    @DisplayName("추가하면 보낸 값을 그대로 넘기고 201 과 주소를 돌려준다")
    void creates() throws Exception {
        when(management.create(eq("target-d"), any(), any())).thenAnswer(invocation ->
                ((PolicyContent) invocation.getArgument(1)).toPolicy("target-d", 1));

        mockMvc.perform(post("/admin/policies").contentType(MediaType.APPLICATION_JSON).content("""
                        {"targetSystemId": "target-d", "policy": %s, "changedBy": "hyuk", "reason": "새 기관"}
                        """.formatted(POLICY_JSON)))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/admin/policies/target-d"))
                .andExpect(jsonPath("$.version").value(1));

        ArgumentCaptor<PolicyContent> content = ArgumentCaptor.forClass(PolicyContent.class);
        verify(management).create(eq("target-d"), content.capture(), eq(new ChangeNote("hyuk", "새 기관")));
        assertThat(content.getValue().limits().maxTps()).isEqualTo(80);
        assertThat(content.getValue().connection().readTimeout()).isEqualTo(Duration.ofSeconds(3));
        assertThat(content.getValue().response().resultField()).isEqualTo("/resultCode");
    }

    @Test
    @DisplayName("점검 시간표를 보내면 한국 시간 기준 반복 점검과 특정 날짜 점검으로 넘긴다")
    void createsWithMaintenance() throws Exception {
        when(management.create(eq("target-d"), any(), any())).thenAnswer(invocation ->
                ((PolicyContent) invocation.getArgument(1)).toPolicy("target-d", 1));
        String policy = withMaintenance("""
                {
                  "recurring": [{"days": ["SATURDAY", "SUNDAY"], "start": "23:50", "end": "00:10"}],
                  "oneTime": [{"start": "2026-10-20T01:00:00", "end": "2026-10-20T05:00:00"}]
                }""");

        mockMvc.perform(post("/admin/policies").contentType(MediaType.APPLICATION_JSON).content("""
                        {"targetSystemId": "target-d", "policy": %s, "changedBy": "hyuk", "reason": "점검 등록"}
                        """.formatted(policy)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.policy.maintenance.recurring[0].days[0]").value("SATURDAY"))
                .andExpect(jsonPath("$.policy.maintenance.recurring[0].start").value(startsWith("23:50")))
                .andExpect(jsonPath("$.policy.maintenance.oneTime[0].end").value(startsWith("2026-10-20T05:00")));

        ArgumentCaptor<PolicyContent> content = ArgumentCaptor.forClass(PolicyContent.class);
        verify(management).create(eq("target-d"), content.capture(), any());
        assertThat(content.getValue().maintenance()).isEqualTo(new MaintenanceSchedule(
                List.of(new RecurringMaintenance(Set.of(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY),
                        LocalTime.of(23, 50), LocalTime.of(0, 10))),
                List.of(new OneTimeMaintenance(LocalDateTime.of(2026, 10, 20, 1, 0),
                        LocalDateTime.of(2026, 10, 20, 5, 0)))));
    }

    @Test
    @DisplayName("점검 시간표를 보내지 않으면 점검 없음이다")
    void createsWithoutMaintenance() throws Exception {
        when(management.create(eq("target-d"), any(), any())).thenAnswer(invocation ->
                ((PolicyContent) invocation.getArgument(1)).toPolicy("target-d", 1));

        mockMvc.perform(post("/admin/policies").contentType(MediaType.APPLICATION_JSON).content("""
                        {"targetSystemId": "target-d", "policy": %s, "changedBy": "hyuk", "reason": "새 기관"}
                        """.formatted(POLICY_JSON)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.policy.maintenance.recurring").isEmpty())
                .andExpect(jsonPath("$.policy.maintenance.oneTime").isEmpty());

        ArgumentCaptor<PolicyContent> content = ArgumentCaptor.forClass(PolicyContent.class);
        verify(management).create(eq("target-d"), content.capture(), any());
        assertThat(content.getValue().maintenance()).isEqualTo(MaintenanceSchedule.none());
    }

    @Test
    @DisplayName("점검 끝 일시가 시작 일시보다 앞이면 400 이다")
    void rejectsInvalidMaintenance() throws Exception {
        String policy = withMaintenance("""
                {"oneTime": [{"start": "2026-10-20T05:00:00", "end": "2026-10-20T01:00:00"}]}""");

        mockMvc.perform(post("/admin/policies").contentType(MediaType.APPLICATION_JSON).content("""
                        {"targetSystemId": "target-d", "policy": %s, "changedBy": "hyuk", "reason": "잘못된 점검"}
                        """.formatted(policy)))
                .andExpect(status().isBadRequest());

        verify(management, never()).create(any(), any(), any());
    }

    private static String withMaintenance(String maintenance) {
        return POLICY_JSON.substring(0, POLICY_JSON.lastIndexOf('}')) + ", \"maintenance\": " + maintenance + "}";
    }

    @Test
    @DisplayName("이미 있는 대상을 추가하면 409 다")
    void createDuplicate() throws Exception {
        when(management.create(eq("target-a"), any(), any())).thenThrow(new PolicyAlreadyExistsException("target-a"));

        mockMvc.perform(post("/admin/policies").contentType(MediaType.APPLICATION_JSON).content("""
                        {"targetSystemId": "target-a", "policy": %s, "changedBy": "hyuk", "reason": "중복"}
                        """.formatted(POLICY_JSON)))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("값이 빠졌거나 잘못됐으면 400 이고 저장하지 않는다")
    void rejectsInvalidBody() throws Exception {
        mockMvc.perform(put("/admin/policies/target-a").contentType(MediaType.APPLICATION_JSON).content("""
                        {"policy": %s, "changedBy": "hyuk", "reason": "버전 없음"}
                        """.formatted(POLICY_JSON)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("expectedVersion 는 필수입니다"));

        mockMvc.perform(put("/admin/policies/target-a").contentType(MediaType.APPLICATION_JSON).content("""
                        {"expectedVersion": 1, "policy": %s, "changedBy": "", "reason": "사람 없음"}
                        """.formatted(POLICY_JSON)))
                .andExpect(status().isBadRequest());

        mockMvc.perform(put("/admin/policies/target-a").contentType(MediaType.APPLICATION_JSON).content("""
                        {"expectedVersion": 1, "policy": %s, "changedBy": "hyuk", "reason": "0 tps"}
                        """.formatted(POLICY_JSON.replace("\"maxTps\": 80", "\"maxTps\": 0"))))
                .andExpect(status().isBadRequest());

        mockMvc.perform(put("/admin/policies/target-a").contentType(MediaType.APPLICATION_JSON).content("""
                        {"expectedVersion": 1, "policy": %s, "changedBy": "hyuk", "reason": "없는 결과"}
                        """.formatted(POLICY_JSON.replace("SERVER_ERROR", "NOPE"))))
                .andExpect(status().isBadRequest());

        verify(management, never()).update(any(), any(), anyLong(), any());
    }

    @Test
    @DisplayName("수정할 때 버전이 맞지 않으면 409 다")
    void updateConflict() throws Exception {
        when(management.update(eq("target-a"), any(), eq(1L), any()))
                .thenThrow(new PolicyVersionConflictException("target-a", 1));

        mockMvc.perform(put("/admin/policies/target-a").contentType(MediaType.APPLICATION_JSON).content("""
                        {"expectedVersion": 1, "policy": %s, "changedBy": "hyuk", "reason": "한도"}
                        """.formatted(POLICY_JSON)))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("켜고 끄기는 사용 여부와 버전만 받는다")
    void changesEnabled() throws Exception {
        when(management.changeEnabled("target-a", false, 3, new ChangeNote("hyuk", "점검")))
                .thenReturn(Policies.policy("target-a", 4).withEnabled(false));

        mockMvc.perform(put("/admin/policies/target-a/enabled").contentType(MediaType.APPLICATION_JSON).content("""
                        {"enabled": false, "expectedVersion": 3, "changedBy": "hyuk", "reason": "점검"}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(4))
                .andExpect(jsonPath("$.policy.enabled").value(false));
    }

    @Test
    @DisplayName("이력은 버전별로 바꾼 사람과 사유를 보여준다")
    void history() throws Exception {
        ManagedPolicy policy = Policies.policy("target-a", 2);
        when(management.history("target-a")).thenReturn(List.of(
                new PolicyHistoryEntry(policy, Instant.parse("2026-10-14T00:00:00Z"), new ChangeNote("hyuk", "한도"))));

        mockMvc.perform(get("/admin/policies/target-a/history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].version").value(2))
                .andExpect(jsonPath("$[0].changedBy").value("hyuk"))
                .andExpect(jsonPath("$[0].reason").value("한도"));
    }

    @Test
    @DisplayName("되돌리기는 되돌릴 버전과 지금 버전을 받는다")
    void rollsBack() throws Exception {
        when(management.rollback("target-a", 1, 3, new ChangeNote("hyuk", "되돌림")))
                .thenReturn(Policies.policy("target-a", 4));

        mockMvc.perform(post("/admin/policies/target-a/rollback").contentType(MediaType.APPLICATION_JSON).content("""
                        {"toVersion": 1, "expectedVersion": 3, "changedBy": "hyuk", "reason": "되돌림"}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(4));
    }
}
