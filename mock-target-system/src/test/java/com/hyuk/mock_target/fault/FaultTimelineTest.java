package com.hyuk.mock_target.fault;

import com.hyuk.mock_target.simulation.Sleeper;
import com.hyuk.mock_target.simulation.WorkSlots;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

class FaultTimelineTest {
    private final AtomicLong clock = new AtomicLong(1_000);
    private final Faults faults = new Faults();
    private final CallPort callPort = mock(CallPort.class);
    private final FaultService service = new FaultService(faults, new WorkSlots(10, 0), callPort, 10);
    private final List<Duration> sleeps = Collections.synchronizedList(new ArrayList<>());
    private final List<String> seenFaults = Collections.synchronizedList(new ArrayList<>());
    private FaultTimeline timeline;

    @AfterEach
    void tearDown() {
        if (timeline != null) {
            timeline.stop();
        }
    }

    private FaultTimeline withAdvancingClock() {
        Sleeper sleeper = duration -> {
            seenFaults.add(faults.describe().toString());
            sleeps.add(duration);
            clock.addAndGet(duration.toNanos());
        };
        return timeline = new FaultTimeline(service, sleeper, clock::get);
    }

    private FaultTimeline withBlockingSleep(CountDownLatch sleeping, CountDownLatch release) {
        Sleeper sleeper = duration -> {
            sleeping.countDown();
            release.await();
        };
        return timeline = new FaultTimeline(service, sleeper, clock::get);
    }

    private static FaultTimelinePlan plan(FaultTimelinePlan.Entry... entries) {
        return new FaultTimelinePlan(List.of(entries));
    }

    private void awaitState(FaultTimelineStatus.State state) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (timeline.status().state() != state) {
            assertThat(System.nanoTime()).as("%s 가 되지 않았습니다", state).isLessThan(deadline);
            Thread.sleep(5);
        }
    }

    @Test
    @DisplayName("시작 시각을 기준으로 정한 시각에 장애를 걸고 풀고, 끝나면 FINISHED 다")
    void runsStepsAtPlannedTimes() throws Exception {
        withAdvancingClock().start(plan(
                new FaultTimelinePlan.Entry(60L, "slow", 3.0, 180L),
                new FaultTimelinePlan.Entry(70L, "failure-rate", 0.5, 80L)));

        awaitState(FaultTimelineStatus.State.FINISHED);

        assertThat(sleeps).containsExactly(Duration.ofSeconds(60), Duration.ofSeconds(10), Duration.ofSeconds(10),
                Duration.ofSeconds(100));
        assertThat(seenFaults).containsExactly("{}", "{slow=3.0}", "{slow=3.0, failure-rate=0.5}", "{slow=3.0}");
        assertThat(faults.describe()).isEmpty();

        FaultTimelineStatus status = timeline.status();
        assertThat(status.elapsedSeconds()).isEqualTo(180);
        assertThat(status.completedSteps()).isEqualTo(4);
        assertThat(status.totalSteps()).isEqualTo(4);
        assertThat(status.next()).isNull();
    }

    @Test
    @DisplayName("한 번도 시작하지 않았으면 STOPPED 다")
    void stoppedBeforeStart() {
        FaultTimelineStatus status = withAdvancingClock().status();

        assertThat(status.state()).isEqualTo(FaultTimelineStatus.State.STOPPED);
        assertThat(status.next()).isNull();
    }

    @Test
    @DisplayName("진행 중에는 경과 초와 다음 예정을 보여준다")
    void reportsProgress() throws Exception {
        CountDownLatch sleeping = new CountDownLatch(1);
        withBlockingSleep(sleeping, new CountDownLatch(1)).start(plan(new FaultTimelinePlan.Entry(60L, "slow", 3.0, 180L)));
        sleeping.await();
        clock.addAndGet(TimeUnit.SECONDS.toNanos(5));

        FaultTimelineStatus status = timeline.status();

        assertThat(status.state()).isEqualTo(FaultTimelineStatus.State.RUNNING);
        assertThat(status.elapsedSeconds()).isEqualTo(5);
        assertThat(status.next()).isEqualTo(new FaultTimelineStatus.Next(60, "apply", "slow", 3.0));
    }

    @Test
    @DisplayName("취소하면 시간표를 멈추고 모든 장애를 푼다")
    void stopCancelsAndClearsFaults() throws Exception {
        CountDownLatch sleeping = new CountDownLatch(1);
        withBlockingSleep(sleeping, new CountDownLatch(1)).start(plan(new FaultTimelinePlan.Entry(60L, "slow", 3.0, 180L)));
        sleeping.await();
        service.apply(FaultType.FAILURE_RATE, 0.5);

        FaultTimelineStatus status = timeline.stop();

        assertThat(status.state()).isEqualTo(FaultTimelineStatus.State.STOPPED);
        assertThat(status.next()).isNull();
        assertThat(faults.describe()).isEmpty();
    }

    @Test
    @DisplayName("시작할 때 걸려 있던 장애와 돌던 시간표를 정리한다")
    void startClearsPreviousState() throws Exception {
        CountDownLatch sleeping = new CountDownLatch(1);
        withBlockingSleep(sleeping, new CountDownLatch(1)).start(plan(new FaultTimelinePlan.Entry(60L, "slow", 3.0, 180L)));
        sleeping.await();
        service.apply(FaultType.DOWN, null);
        service.apply(FaultType.FAILURE_RATE, 0.5);

        FaultTimelineStatus status = timeline.start(plan(new FaultTimelinePlan.Entry(30L, "capacity", 2.0, 40L)));

        assertThat(status.state()).isEqualTo(FaultTimelineStatus.State.RUNNING);
        assertThat(status.totalSteps()).isEqualTo(2);
        assertThat(faults.describe()).isEmpty();
    }

    @Test
    @DisplayName("손으로 건 값은 다음 시간표 시각에 덮어써진다")
    void manualValueIsOverwritten() throws Exception {
        CountDownLatch sleeping = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        withBlockingSleep(sleeping, release).start(plan(new FaultTimelinePlan.Entry(60L, "slow", 3.0, 180L)));
        sleeping.await();

        service.apply(FaultType.SLOW, 9.0);
        release.countDown();
        awaitState(FaultTimelineStatus.State.FINISHED);

        assertThat(faults.isActive(FaultType.SLOW)).isFalse();
    }

    @Test
    @DisplayName("단계 실행이 실패하면 실패 수에 세고 다음 단계를 계속한다")
    void countsFailedSteps() throws Exception {
        doThrow(new IllegalStateException("포트를 닫지 못했습니다")).when(callPort).close();

        withAdvancingClock().start(plan(
                new FaultTimelinePlan.Entry(10L, "down", null, 20L),
                new FaultTimelinePlan.Entry(30L, "slow", 2.0, 40L)));
        awaitState(FaultTimelineStatus.State.FINISHED);

        FaultTimelineStatus status = timeline.status();
        assertThat(status.failedSteps()).isEqualTo(1);
        assertThat(status.completedSteps()).isEqualTo(4);
        assertThat(faults.describe()).isEmpty();
    }
}
