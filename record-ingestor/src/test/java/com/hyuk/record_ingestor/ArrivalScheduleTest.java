package com.hyuk.record_ingestor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class ArrivalScheduleTest {

    @Test
    @DisplayName("seed 와 대상이 같으면 간격이 매번 똑같이 나온다")
    void sameSeedSameDelays() {
        ArrivalSchedule first = new ArrivalSchedule(42, "target-a");
        ArrivalSchedule second = new ArrivalSchedule(42, "target-a");

        assertThat(delays(first, 100, 20)).containsExactly(delays(second, 100, 20));
    }

    @Test
    @DisplayName("seed 나 대상이 다르면 간격도 다르게 나온다")
    void differentSeedDifferentDelays() {
        long[] base = delays(new ArrivalSchedule(42, "target-a"), 100, 20);

        assertThat(delays(new ArrivalSchedule(43, "target-a"), 100, 20)).isNotEqualTo(base);
        assertThat(delays(new ArrivalSchedule(42, "target-b"), 100, 20)).isNotEqualTo(base);
    }

    @Test
    @DisplayName("간격은 들쭉날쭉하지만 평균은 1 을 초당 건수로 나눈 값이다")
    void averageMatchesRate() {
        long[] delays = delays(new ArrivalSchedule(1, "target-a"), 100, 20_000);
        double averageMillis = LongStream.of(delays).average().orElseThrow() / 1_000_000.0;

        assertThat(averageMillis).isCloseTo(10.0, within(0.5));
        assertThat(LongStream.of(delays).distinct().count()).isGreaterThan(1000);
    }

    @Test
    @DisplayName("초당 건수가 0 이하면 간격을 만들 수 없다")
    void rejectsNonPositiveRate() {
        ArrivalSchedule schedule = new ArrivalSchedule(1, "target-a");

        assertThatThrownBy(() -> schedule.nextDelayNanos(0)).isInstanceOf(IllegalArgumentException.class);
    }

    private static long[] delays(ArrivalSchedule schedule, double rate, int count) {
        long[] delays = new long[count];
        for (int i = 0; i < count; i++) {
            delays[i] = schedule.nextDelayNanos(rate);
        }
        return delays;
    }
}
