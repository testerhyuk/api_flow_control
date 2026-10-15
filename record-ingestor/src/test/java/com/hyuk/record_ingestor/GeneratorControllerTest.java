package com.hyuk.record_ingestor;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.any;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class GeneratorControllerTest {
    private static final GeneratorStatus RUNNING = new GeneratorStatus(true, 42,
            Map.of("target-a", new GeneratorStatus.Target(80, 10, 0)));

    private RequestGenerator generator;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        generator = mock(RequestGenerator.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new GeneratorController(generator)).build();
    }

    @Test
    @DisplayName("seed 와 대상별 요청량으로 요청 생성을 시작한다")
    void starts() throws Exception {
        when(generator.start(42, Map.of("target-a", 80.0))).thenReturn(RUNNING);

        mockMvc.perform(post("/generator/start").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"seed\": 42, \"rates\": {\"target-a\": 80}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.running").value(true))
                .andExpect(jsonPath("$.targets.target-a.rate").value(80.0));
    }

    @Test
    @DisplayName("대상의 요청량을 바꾼다")
    void changesRate() throws Exception {
        when(generator.changeRate("target-a", 300)).thenReturn(RUNNING);

        mockMvc.perform(put("/generator/rates/target-a").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rate\": 300}"))
                .andExpect(status().isOk());

        verify(generator).changeRate("target-a", 300);
    }

    @Test
    @DisplayName("멈추고 지금 상태를 돌려준다")
    void stops() throws Exception {
        when(generator.stopGenerating()).thenReturn(new GeneratorStatus(false, 42, Map.of()));

        mockMvc.perform(post("/generator/stop"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.running").value(false));
    }

    @Test
    @DisplayName("지금 상태와 대상별 보낸 건수를 돌려준다")
    void returnsStatus() throws Exception {
        when(generator.status()).thenReturn(RUNNING);

        mockMvc.perform(get("/generator"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.seed").value(42))
                .andExpect(jsonPath("$.targets.target-a.sent").value(10));
    }

    @Test
    @DisplayName("seed 나 요청량이 없거나 잘못되면 400 이다")
    void rejectsInvalidRequests() throws Exception {
        mockMvc.perform(post("/generator/start").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rates\": {\"target-a\": 80}}"))
                .andExpect(status().isBadRequest());
        verify(generator, never()).start(anyLong(), any());

        when(generator.changeRate("target-a", -1)).thenThrow(new IllegalArgumentException("rate 는 0 이상이어야 합니다"));
        mockMvc.perform(put("/generator/rates/target-a").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rate\": -1}"))
                .andExpect(status().isBadRequest());

        when(generator.changeRate("target-a", 10)).thenThrow(new IllegalStateException("요청 생성이 시작되지 않았습니다"));
        mockMvc.perform(put("/generator/rates/target-a").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rate\": 10}"))
                .andExpect(status().isBadRequest());
    }
}
