package com.hyuk.record_ingestor;

import com.hyuk.record_ingestor.ExperimentPlan.RateChange;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExperimentPlanTest {
    private static final Map<String, Double> RATES = Map.of("target-a", 100.0);

    private static ExperimentPlan planWith(RateChange... changes) {
        return new ExperimentPlan(1, RATES, List.of(changes), 600);
    }

    @Test
    @DisplayName("시간표가 없어도 되고, 시간표는 시각 순서로 정렬한다")
    void sortsTimeline() {
        assertThat(new ExperimentPlan(1, RATES, null, 600).timeline()).isEmpty();

        ExperimentPlan plan = planWith(new RateChange(120L, "target-a", 100.0), new RateChange(60L, "target-a", 300.0));

        assertThat(plan.changesOf("target-a")).extracting(RateChange::atSeconds).containsExactly(60L, 120L);
    }

    @Test
    @DisplayName("요청량과 시간표에 있는 대상을 모두 돌려준다")
    void collectsTargets() {
        ExperimentPlan plan = planWith(new RateChange(60L, "target-c", 5.0));

        assertThat(plan.targets()).containsExactlyInAnyOrder("target-a", "target-c");
        assertThat(plan.changesOf("target-a")).isEmpty();
    }

    @Test
    @DisplayName("요청량이 없거나 0 이하면 거부한다")
    void rejectsInvalidRates() {
        assertThatThrownBy(() -> new ExperimentPlan(1, null, List.of(), 600))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ExperimentPlan(1, Map.of(), List.of(), 600))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ExperimentPlan(1, Map.of("target-a", 0.0), List.of(), 600))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("실험 길이가 0 이하면 거부한다")
    void rejectsInvalidDuration() {
        assertThatThrownBy(() -> new ExperimentPlan(1, RATES, List.of(), 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("시간표의 시각이 0 이하거나 실험 길이 이상이면 거부한다")
    void rejectsInvalidAtSeconds() {
        assertThatThrownBy(() -> planWith(new RateChange(0L, "target-a", 1.0)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> planWith(new RateChange(600L, "target-a", 1.0)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> planWith(new RateChange(null, "target-a", 1.0)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("시간표의 rate 는 0 은 되지만 음수나 빈 값은 거부한다")
    void rejectsInvalidChangeRate() {
        assertThat(planWith(new RateChange(60L, "target-a", 0.0)).timeline()).hasSize(1);
        assertThatThrownBy(() -> planWith(new RateChange(60L, "target-a", -1.0)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> planWith(new RateChange(60L, "target-a", null)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("시간표의 target 이 없으면 거부한다")
    void rejectsBlankTarget() {
        assertThatThrownBy(() -> planWith(new RateChange(60L, " ", 1.0)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("같은 대상에 같은 시각이 두 번 있으면 거부하고, 다른 대상이면 받는다")
    void rejectsDuplicateTime() {
        assertThatThrownBy(() -> planWith(new RateChange(60L, "target-a", 1.0), new RateChange(60L, "target-a", 2.0)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(planWith(new RateChange(60L, "target-a", 1.0), new RateChange(60L, "target-c", 2.0)).timeline())
                .hasSize(2);
    }
}
