package com.hyuk.record_ingestor;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class GeneratorControllerTest {
    private static final GeneratorStatus RUNNING = new GeneratorStatus(GeneratorStatus.State.RUNNING, 42,
            Map.of("target-a", new GeneratorStatus.Target(80, 10, 0, 12, 3)));

    private RequestGenerator generator;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        generator = mock(RequestGenerator.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new GeneratorController(generator)).build();
    }

    @Test
    @DisplayName("seed, 대상별 요청량, 시간표, 실험 길이로 요청 생성을 시작한다")
    void starts() throws Exception {
        ExperimentPlan plan = new ExperimentPlan(42, Map.of("target-a", 80.0),
                List.of(new ExperimentPlan.RateChange(60L, "target-a", 300.0)), 600);
        when(generator.start(plan)).thenReturn(RUNNING);

        mockMvc.perform(post("/generator/start").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"seed": 42, "rates": {"target-a": 80}, "durationSeconds": 600,
                                 "timeline": [{"atSeconds": 60, "target": "target-a", "rate": 300}]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("RUNNING"))
                .andExpect(jsonPath("$.running").value(true))
                .andExpect(jsonPath("$.targets.target-a.rate").value(80.0));
    }

    @Test
    @DisplayName("시간표 없이도 시작한다")
    void startsWithoutTimeline() throws Exception {
        when(generator.start(new ExperimentPlan(42, Map.of("target-a", 80.0), List.of(), 600))).thenReturn(RUNNING);

        mockMvc.perform(post("/generator/start").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"seed\": 42, \"rates\": {\"target-a\": 80}, \"durationSeconds\": 600}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.running").value(true));
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
        when(generator.stopGenerating()).thenReturn(new GeneratorStatus(GeneratorStatus.State.STOPPED, 42, Map.of()));

        mockMvc.perform(post("/generator/stop"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("STOPPED"))
                .andExpect(jsonPath("$.running").value(false));
    }

    @Test
    @DisplayName("지금 상태와 대상별 보낸 건수, 늦어진 시간, 연달아 보낸 건수를 돌려준다")
    void returnsStatus() throws Exception {
        when(generator.status()).thenReturn(RUNNING);

        mockMvc.perform(get("/generator"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.seed").value(42))
                .andExpect(jsonPath("$.targets.target-a.sent").value(10))
                .andExpect(jsonPath("$.targets.target-a.maxLagMillis").value(12))
                .andExpect(jsonPath("$.targets.target-a.catchUpSent").value(3));
    }

    @Test
    @DisplayName("seed, 요청량, 실험 길이가 없거나 시간표가 잘못되면 400 이고 시작하지 않는다")
    void rejectsInvalidStart() throws Exception {
        for (String body : List.of(
                "{\"rates\": {\"target-a\": 80}, \"durationSeconds\": 600}",
                "{\"seed\": 42, \"durationSeconds\": 600}",
                "{\"seed\": 42, \"rates\": {\"target-a\": 80}}",
                "{\"seed\": 42, \"rates\": {\"target-a\": 80}, \"durationSeconds\": 0}",
                """
                {"seed": 42, "rates": {"target-a": 80}, "durationSeconds": 60,
                 "timeline": [{"atSeconds": 60, "target": "target-a", "rate": 300}]}
                """)) {
            mockMvc.perform(post("/generator/start").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }

        verify(generator, never()).start(any());
    }

    @Test
    @DisplayName("토픽이 없어 시작이 거부되면 400 과 이유를 돌려준다")
    void rejectsMissingTopic() throws Exception {
        when(generator.start(any())).thenThrow(new IllegalStateException("토픽이 없습니다 : [x]"));

        mockMvc.perform(post("/generator/start").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"seed\": 42, \"rates\": {\"target-a\": 80}, \"durationSeconds\": 600}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("토픽이 없습니다 : [x]"));
    }

    @Test
    @DisplayName("요청량이 없거나 잘못되면 400 이다")
    void rejectsInvalidRateChange() throws Exception {
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
