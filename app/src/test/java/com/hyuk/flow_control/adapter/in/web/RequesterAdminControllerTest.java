package com.hyuk.flow_control.adapter.in.web;

import com.hyuk.flow_control.application.port.in.RequesterManagementUseCase;
import com.hyuk.flow_control.domain.policy.ChangeNote;
import com.hyuk.flow_control.domain.requester.Requester;
import com.hyuk.flow_control.domain.requester.RequesterAlreadyExistsException;
import com.hyuk.flow_control.domain.requester.RequesterHistoryEntry;
import com.hyuk.flow_control.domain.requester.RequesterNotFoundException;
import com.hyuk.flow_control.domain.requester.RequesterVersionConflictException;
import com.hyuk.flow_control.support.AdminKeys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RequesterAdminControllerTest {
    private static final ChangeNote NOTE = new ChangeNote("hyuk", "테스트");
    private static final Requester LOAN = new Requester("loan", 2, true, List.of("target-a", "target-b"));

    private RequesterManagementUseCase management;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        management = mock(RequesterManagementUseCase.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new RequesterAdminController(management))
                .setControllerAdvice(new PolicyAdminExceptionHandler())
                .addFilters(AdminKeys.filter())
                .defaultRequest(AdminKeys.authorized())
                .build();
    }

    @Test
    @DisplayName("조회하면 요청 토픽과 결과 토픽 이름을 함께 보여준다")
    void showsTopics() throws Exception {
        when(management.get("loan")).thenReturn(LOAN);

        mockMvc.perform(get("/admin/requesters/loan"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requesterId").value("loan"))
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.targetSystemIds[1]").value("target-b"))
                .andExpect(jsonPath("$.requestTopics.target-a").value("flow-control.requests.target-a.loan"))
                .andExpect(jsonPath("$.resultTopic").value("flow-control.results.loan"));
    }

    @Test
    @DisplayName("목록을 보여준다")
    void lists() throws Exception {
        when(management.list()).thenReturn(List.of(LOAN));

        mockMvc.perform(get("/admin/requesters"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].requesterId").value("loan"));
    }

    @Test
    @DisplayName("없는 요청자를 조회하면 404 다")
    void getMissing() throws Exception {
        when(management.get("card")).thenThrow(new RequesterNotFoundException("card"));

        mockMvc.perform(get("/admin/requesters/card"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("등록하면 201 과 위치를 돌려준다")
    void registers() throws Exception {
        Requester card = new Requester("card", 1, true, List.of("target-a"));
        when(management.register("card", List.of("target-a"), NOTE)).thenReturn(card);

        mockMvc.perform(post("/admin/requesters").contentType(MediaType.APPLICATION_JSON).content("""
                        {"requesterId": "card", "targetSystemIds": ["target-a"], "reason": "테스트"}
                        """))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/admin/requesters/card"))
                .andExpect(jsonPath("$.resultTopic").value("flow-control.results.card"));
    }

    @Test
    @DisplayName("외부 서버 목록 없이 등록하면 400 이다")
    void rejectsRegisterWithoutTargets() throws Exception {
        mockMvc.perform(post("/admin/requesters").contentType(MediaType.APPLICATION_JSON).content("""
                        {"requesterId": "card", "reason": "테스트"}
                        """))
                .andExpect(status().isBadRequest());
        verify(management, never()).register(anyString(), any(), any());
    }

    @Test
    @DisplayName("이미 있는 요청자를 등록하면 409 다")
    void registerDuplicate() throws Exception {
        when(management.register(eq("loan"), any(), any())).thenThrow(new RequesterAlreadyExistsException("loan"));

        mockMvc.perform(post("/admin/requesters").contentType(MediaType.APPLICATION_JSON).content("""
                        {"requesterId": "loan", "targetSystemIds": ["target-a"], "reason": "테스트"}
                        """))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("끄기와 외부 서버 더하기, 빼기를 보낸 버전으로 요청한다")
    void changes() throws Exception {
        when(management.changeEnabled("loan", false, 2, NOTE)).thenReturn(LOAN.withEnabled(false).withVersion(3));
        when(management.addTarget("loan", "target-c", 2, NOTE)).thenReturn(LOAN.withTarget("target-c").withVersion(3));
        when(management.removeTarget("loan", "target-a", 2, NOTE))
                .thenReturn(LOAN.withoutTarget("target-a").withVersion(3));

        mockMvc.perform(put("/admin/requesters/loan/enabled").contentType(MediaType.APPLICATION_JSON).content("""
                        {"enabled": false, "expectedVersion": 2, "reason": "테스트"}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));
        mockMvc.perform(put("/admin/requesters/loan/targets/target-c").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedVersion": 2, "reason": "테스트"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.targetSystemIds[2]").value("target-c"));
        mockMvc.perform(delete("/admin/requesters/loan/targets/target-a").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedVersion": 2, "reason": "테스트"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.targetSystemIds.length()").value(1));
    }

    @Test
    @DisplayName("버전 없이 바꾸면 400, 버전이 맞지 않으면 409 다")
    void rejectsMissingOrStaleVersion() throws Exception {
        when(management.changeEnabled(eq("loan"), eq(false), eq(1L), any()))
                .thenThrow(new RequesterVersionConflictException("loan", 1));

        mockMvc.perform(put("/admin/requesters/loan/enabled").contentType(MediaType.APPLICATION_JSON).content("""
                        {"enabled": false, "reason": "테스트"}
                        """))
                .andExpect(status().isBadRequest());
        mockMvc.perform(put("/admin/requesters/loan/enabled").contentType(MediaType.APPLICATION_JSON).content("""
                        {"enabled": false, "expectedVersion": 1, "reason": "테스트"}
                        """))
                .andExpect(status().isConflict());
        verify(management, never()).addTarget(anyString(), anyString(), anyLong(), any());
    }

    @Test
    @DisplayName("이력을 최신 버전부터 보여준다")
    void history() throws Exception {
        Instant at = Instant.parse("2026-11-01T00:00:00Z");
        when(management.history("loan")).thenReturn(List.of(
                new RequesterHistoryEntry(LOAN, at, NOTE),
                new RequesterHistoryEntry(LOAN.withVersion(1), at, NOTE)));

        mockMvc.perform(get("/admin/requesters/loan/history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].version").value(2))
                .andExpect(jsonPath("$[0].changedBy").value("hyuk"))
                .andExpect(jsonPath("$[1].version").value(1));
    }
}
