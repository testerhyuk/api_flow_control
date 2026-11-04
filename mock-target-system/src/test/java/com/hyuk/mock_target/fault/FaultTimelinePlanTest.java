package com.hyuk.mock_target.fault;

import com.hyuk.mock_target.fault.FaultTimelinePlan.Action;
import com.hyuk.mock_target.fault.FaultTimelinePlan.Entry;
import com.hyuk.mock_target.fault.FaultTimelinePlan.Step;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

class FaultTimelinePlanTest {

    private static void assertRejected(Entry... entries) {
        assertThatThrownBy(() -> new FaultTimelinePlan(Arrays.asList(entries)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("장애를 거는 단계와 푸는 단계로 나눠 시각 순서로 돌려준다")
    void buildsSteps() {
        FaultTimelinePlan plan = new FaultTimelinePlan(List.of(
                new Entry(60L, "slow", 3.0, 120L),
                new Entry(10L, "down", null, 20L)));

        assertThat(plan.steps()).containsExactly(
                new Step(10, Action.APPLY, FaultType.DOWN, null),
                new Step(20, Action.CLEAR, FaultType.DOWN, null),
                new Step(60, Action.APPLY, FaultType.SLOW, 3.0),
                new Step(120, Action.CLEAR, FaultType.SLOW, null));
    }

    @Test
    @DisplayName("같은 시각에는 푸는 단계가 먼저다. 그래서 앞 구간이 끝나는 시각에 다음 구간을 시작할 수 있다")
    void clearsBeforeApplyingAtSameTime() {
        FaultTimelinePlan plan = new FaultTimelinePlan(List.of(
                new Entry(60L, "slow", 2.0, 120L),
                new Entry(0L, "slow", 3.0, 60L)));

        assertThat(plan.steps()).extracting(Step::atSeconds, Step::action).containsExactly(
                tuple(0L, Action.APPLY),
                tuple(60L, Action.CLEAR),
                tuple(60L, Action.APPLY),
                tuple(120L, Action.CLEAR));
    }

    @Test
    @DisplayName("목록이 없거나 비어 있으면 거부한다")
    void rejectsEmpty() {
        assertThatThrownBy(() -> new FaultTimelinePlan(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FaultTimelinePlan(List.of())).isInstanceOf(IllegalArgumentException.class);
        assertRejected((Entry) null);
    }

    @Test
    @DisplayName("모르는 장애나 잘못된 값이면 거부한다")
    void rejectsInvalidFaults() {
        assertRejected(new Entry(0L, "boom", 1.0, 10L));
        assertRejected(new Entry(0L, null, 1.0, 10L));
        assertRejected(new Entry(0L, "slow", 0.5, 10L));
        assertRejected(new Entry(0L, "slow", null, 10L));
    }

    @Test
    @DisplayName("거는 시각이 없거나 음수거나, 푸는 시각이 없거나 거는 시각 이하면 거부한다")
    void rejectsInvalidTimes() {
        assertRejected(new Entry(null, "slow", 2.0, 10L));
        assertRejected(new Entry(-1L, "slow", 2.0, 10L));
        assertRejected(new Entry(0L, "slow", 2.0, null));
        assertRejected(new Entry(10L, "slow", 2.0, 10L));
    }

    @Test
    @DisplayName("같은 장애의 구간이 겹치면 거부하고, 다른 장애면 겹쳐도 받는다")
    void rejectsOverlaps() {
        assertRejected(new Entry(0L, "slow", 2.0, 60L), new Entry(59L, "slow", 3.0, 120L));

        assertThat(new FaultTimelinePlan(List.of(
                new Entry(0L, "slow", 2.0, 60L),
                new Entry(30L, "failure-rate", 0.5, 90L))).steps()).hasSize(4);
    }
}
