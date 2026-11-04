package com.hyuk.mock_target.fault;

import com.hyuk.mock_target.simulation.Sleeper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

public class FaultTimeline {
    private static final Logger log = LoggerFactory.getLogger(FaultTimeline.class);

    private final FaultService faults;
    private final Sleeper sleeper;
    private final LongSupplier nanoClock;
    private Run current;

    public FaultTimeline(FaultService faults, Sleeper sleeper, LongSupplier nanoClock) {
        this.faults = faults;
        this.sleeper = sleeper;
        this.nanoClock = nanoClock;
    }

    public synchronized FaultTimelineStatus start(FaultTimelinePlan plan) {
        cancelCurrent();
        faults.clearAll();

        current = new Run(plan.steps());
        current.start();
        log.info("장애 시간표 시작 : {}", plan);
        return current.status();
    }

    public synchronized FaultTimelineStatus stop() {
        cancelCurrent();
        faults.clearAll();
        log.info("장애 시간표 취소");
        return status();
    }

    public synchronized FaultTimelineStatus status() {
        return current == null
                ? new FaultTimelineStatus(FaultTimelineStatus.State.STOPPED, 0, 0, 0, 0, null)
                : current.status();
    }

    private void cancelCurrent() {
        if (current != null) {
            current.cancel();
        }
    }

    private final class Run implements Runnable {
        private final List<FaultTimelinePlan.Step> steps;
        private final long startedNanos = nanoClock.getAsLong();
        private final Thread thread;
        private volatile FaultTimelineStatus.State state = FaultTimelineStatus.State.RUNNING;
        private volatile int completed;
        private volatile int failed;
        private volatile long endedNanos;
        private volatile boolean cancelled;

        private Run(List<FaultTimelinePlan.Step> steps) {
            this.steps = steps;
            this.thread = new Thread(this, "fault-timeline");
            this.thread.setDaemon(true);
        }

        private void start() {
            thread.start();
        }

        @Override
        public void run() {
            try {
                for (FaultTimelinePlan.Step step : steps) {
                    sleeper.sleep(Duration.ofNanos(
                            startedNanos + TimeUnit.SECONDS.toNanos(step.atSeconds()) - nanoClock.getAsLong()));
                    if (cancelled) {
                        return;
                    }
                    execute(step);
                }
                endedNanos = nanoClock.getAsLong();
                state = FaultTimelineStatus.State.FINISHED;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        private void execute(FaultTimelinePlan.Step step) {
            try {
                if (step.action() == FaultTimelinePlan.Action.APPLY) {
                    faults.apply(step.type(), step.value());
                } else {
                    faults.clear(step.type());
                }
            } catch (RuntimeException e) {
                failed++;
                log.error("장애 시간표 실행 실패 : {}", step, e);
            }
            completed++;
        }

        private void cancel() {
            cancelled = true;
            thread.interrupt();
            try {
                thread.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            if (state == FaultTimelineStatus.State.RUNNING) {
                endedNanos = nanoClock.getAsLong();
                state = FaultTimelineStatus.State.STOPPED;
            }
        }

        private FaultTimelineStatus status() {
            FaultTimelineStatus.State currentState = state;
            int done = completed;
            long until = currentState == FaultTimelineStatus.State.RUNNING ? nanoClock.getAsLong() : endedNanos;
            return new FaultTimelineStatus(
                    currentState,
                    TimeUnit.NANOSECONDS.toSeconds(until - startedNanos),
                    done,
                    steps.size(),
                    failed,
                    currentState == FaultTimelineStatus.State.RUNNING && done < steps.size() ? next(steps.get(done)) : null);
        }

        private FaultTimelineStatus.Next next(FaultTimelinePlan.Step step) {
            return new FaultTimelineStatus.Next(step.atSeconds(), step.action().name().toLowerCase(Locale.ROOT),
                    step.type().id(), step.value());
        }
    }
}
