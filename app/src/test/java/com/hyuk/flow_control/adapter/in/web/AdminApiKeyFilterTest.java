package com.hyuk.flow_control.adapter.in.web;

import com.hyuk.flow_control.application.port.in.PolicyManagementUseCase;
import com.hyuk.flow_control.domain.policy.ChangeNote;
import com.hyuk.flow_control.support.AdminKeys;
import com.hyuk.flow_control.support.Policies;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AdminApiKeyFilterTest {
    private PolicyManagementUseCase management;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        management = mock(PolicyManagementUseCase.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new PolicyAdminController(management))
                .setControllerAdvice(new PolicyAdminExceptionHandler())
                .addFilters(AdminKeys.filter())
                .build();
    }

    @Test
    @DisplayName("키가 없거나 틀리면 조회도 401 이고 컨트롤러까지 가지 않는다")
    void rejectsMissingOrWrongKey() throws Exception {
        mockMvc.perform(get("/admin/policies"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/admin/policies").header(HttpHeaders.AUTHORIZATION, "Bearer wrong-key"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/admin/policies").header(HttpHeaders.AUTHORIZATION, AdminKeys.KEY))
                .andExpect(status().isUnauthorized());

        verify(management, never()).list();
    }

    @Test
    @DisplayName("맞는 키면 통과한다")
    void acceptsKey() throws Exception {
        when(management.list()).thenReturn(List.of());

        mockMvc.perform(get("/admin/policies").header(HttpHeaders.AUTHORIZATION, "Bearer " + AdminKeys.KEY))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("바꾼 사람은 본문에 적힌 이름이 아니라 키의 주인으로 남는다")
    void changedByComesFromKey() throws Exception {
        when(management.changeEnabled(anyString(), anyBoolean(), anyLong(), any()))
                .thenReturn(Policies.policy("target-a", 4).withEnabled(false));

        mockMvc.perform(put("/admin/policies/target-a/enabled")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + AdminKeys.KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"enabled": false, "expectedVersion": 3, "changedBy": "someone-else", "reason": "점검"}
                                """))
                .andExpect(status().isOk());

        verify(management).changeEnabled("target-a", false, 3, new ChangeNote(AdminKeys.OPERATOR, "점검"));
    }
}
