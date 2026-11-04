package com.hyuk.record_ingestor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAccumulator;
import java.util.concurrent.locks.LockSupport;

@Component
public class RequestGenerator implements DisposableBean {
    private static final Logger log = LoggerFactory.getLogger(RequestGenerator.class);
    private static final long MAX_PARK_NANOS = 50_000_000L;
    private static final long PENDING_POLL_MILLIS = 10;
    // 임시 기준이다. 실험에서 평소 수준을 본 뒤 정한다.
    private static final long CATCH_UP_LAG_NANOS = 50_000_000L;
    private static final long NEVER = Long.MAX_VALUE;

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final RequestMessageFactory messageFactory;
    private final JsonMapper jsonMapper;
    private final IngestorProperties properties;
    private final TopicChecker topicChecker;
    private final Map<String, TargetLoop> loops = new ConcurrentHashMap<>();
    private final AtomicLong pending = new AtomicLong();
    private volatile GeneratorStatus.State state = GeneratorStatus.State.STOPPED;
    private volatile long seed;
    private long startedNanos;
    private long endNanos;
    private long runId;
    private Thread endWatcher;

    public RequestGenerator(
            KafkaTemplate<String, String> kafkaTemplate,
            RequestMessageFactory messageFactory,
            JsonMapper jsonMapper,
            IngestorProperties properties,
            TopicChecker topicChecker) {
        this.kafkaTemplate = kafkaTemplate;
        this.messageFactory = messageFactory;
        this.jsonMapper = jsonMapper;
        this.properties = properties;
        this.topicChecker = topicChecker;
    }

    public synchronized GeneratorStatus start(ExperimentPlan plan) {
        topicChecker.requireExisting(plan.targets().stream().map(properties::topicOf).toList());

        finish(true);
        loops.clear();
        seed = plan.seed();
        runId++;
        startedNanos = System.nanoTime();
        endNanos = TimeUnit.SECONDS.toNanos(plan.durationSeconds());
        state = GeneratorStatus.State.RUNNING;

        plan.targets().forEach(targetSystemId -> startLoop(targetSystemId,
                plan.rates().getOrDefault(targetSystemId, 0.0), plan.changesOf(targetSystemId), 0));
        startEndWatcher(runId);

        log.info("요청 생성 시작 : {}", plan);
        return status();
    }

    public synchronized GeneratorStatus changeRate(String targetSystemId, double rate) {
        if (state != GeneratorStatus.State.RUNNING) {
            throw new IllegalStateException("요청 생성이 시작되지 않았습니다");
        }
        if (rate < 0) {
            throw new IllegalArgumentException("rate 는 0 이상이어야 합니다");
        }

        TargetLoop loop = loops.get(targetSystemId);
        if (loop != null) {
            loop.changeRate(rate);
        } else if (rate > 0) {
            topicChecker.requireExisting(List.of(properties.topicOf(targetSystemId)));
            startLoop(targetSystemId, rate, List.of(), System.nanoTime() - startedNanos);
        }

        log.info("요청량 변경 : {} 초당 {}건", targetSystemId, rate);
        return status();
    }

    public synchronized GeneratorStatus stopGenerating() {
        finish(true);
        return status();
    }

    @Override
    public void destroy() {
        stopGenerating();
    }

    public GeneratorStatus status() {
        Map<String, GeneratorStatus.Target> targets = new LinkedHashMap<>();
        boolean running = state == GeneratorStatus.State.RUNNING;
        loops.forEach((targetSystemId, loop) -> targets.put(targetSystemId, loop.status(running)));
        return new GeneratorStatus(state, seed, targets);
    }

    private synchronized void finishAtEnd(long finishedRunId) {
        if (finishedRunId == runId) {
            finish(false);
        }
    }

    private void finish(boolean stopSending) {
        if (state != GeneratorStatus.State.RUNNING) {
            return;
        }

        state = GeneratorStatus.State.STOPPING;
        if (Thread.currentThread() != endWatcher) {
            endWatcher.interrupt();
        }
        if (stopSending) {
            loops.values().forEach(TargetLoop::requestStop);
        }
        loops.values().forEach(TargetLoop::awaitEnd);
        awaitPendingResults();
        state = GeneratorStatus.State.STOPPED;
        log.info("요청 생성 중지 : {}", status());
    }

    private void awaitPendingResults() {
        try {
            while (pending.get() > 0) {
                Thread.sleep(PENDING_POLL_MILLIS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void startLoop(String targetSystemId, double rate, List<ExperimentPlan.RateChange> changes,
                           long startOffsetNanos) {
        TargetLoop loop = new TargetLoop(targetSystemId, rate, new ArrivalSchedule(seed, targetSystemId), changes,
                startedNanos, startOffsetNanos, endNanos);
        loops.put(targetSystemId, loop);
        loop.start();
    }

    private void startEndWatcher(long watchedRunId) {
        long deadline = startedNanos + endNanos;
        endWatcher = new Thread(() -> {
            try {
                TimeUnit.NANOSECONDS.sleep(Math.max(0, deadline - System.nanoTime()));
                finishAtEnd(watchedRunId);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "request-generator-end");
        endWatcher.setDaemon(true);
        endWatcher.start();
    }

    private final class TargetLoop implements Runnable {
        private final String targetSystemId;
        private final ArrivalSchedule schedule;
        private final Deque<ExperimentPlan.RateChange> changes;
        private final long startedNanos;
        private final long startOffsetNanos;
        private final long endNanos;
        private final AtomicLong sent = new AtomicLong();
        private final AtomicLong failed = new AtomicLong();
        private final LongAccumulator maxLagNanos = new LongAccumulator(Math::max, 0);
        private final AtomicLong catchUpSent = new AtomicLong();
        private final Thread thread;
        private volatile double rate;
        private volatile boolean rateChanged;
        private volatile boolean stopped;

        private TargetLoop(String targetSystemId, double rate, ArrivalSchedule schedule,
                           List<ExperimentPlan.RateChange> changes, long startedNanos, long startOffsetNanos,
                           long endNanos) {
            this.targetSystemId = targetSystemId;
            this.rate = rate;
            this.schedule = schedule;
            this.changes = new ArrayDeque<>(changes);
            this.startedNanos = startedNanos;
            this.startOffsetNanos = startOffsetNanos;
            this.endNanos = endNanos;
            this.thread = new Thread(this, "request-generator-" + targetSystemId);
            this.thread.setDaemon(true);
        }

        private void start() {
            thread.start();
        }

        private void changeRate(double newRate) {
            rate = newRate;
            rateChanged = true;
            LockSupport.unpark(thread);
        }

        private void requestStop() {
            stopped = true;
            LockSupport.unpark(thread);
        }

        private void awaitEnd() {
            try {
                thread.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        private GeneratorStatus.Target status(boolean running) {
            return new GeneratorStatus.Target(running ? rate : 0, sent.get(), failed.get(),
                    TimeUnit.NANOSECONDS.toMillis(maxLagNanos.get()), catchUpSent.get());
        }

        @Override
        public void run() {
            long due = nextDue(startOffsetNanos);

            while (!stopped) {
                if (rateChanged) {
                    rateChanged = false;
                    due = nextDue(elapsedNanos());
                }

                ExperimentPlan.RateChange change = changes.peekFirst();
                long changeAt = change == null ? NEVER : TimeUnit.SECONDS.toNanos(change.atSeconds());
                long nextEvent = Math.min(Math.min(due, changeAt), endNanos);

                long now = elapsedNanos();
                if (nextEvent > now) {
                    LockSupport.parkNanos(Math.min(nextEvent - now, MAX_PARK_NANOS));
                    continue;
                }

                if (nextEvent == endNanos) {
                    return;
                }
                if (nextEvent == changeAt) {
                    changes.removeFirst();
                    rate = change.rate();
                    rateChanged = false;
                    due = nextDue(changeAt);
                    continue;
                }

                recordLag(now - due);
                sendOne();
                due = nextDue(due);
            }
        }

        private long nextDue(long from) {
            double current = rate;
            return current > 0 ? from + schedule.nextDelayNanos(current) : NEVER;
        }

        private long elapsedNanos() {
            return System.nanoTime() - startedNanos;
        }

        private void recordLag(long lagNanos) {
            maxLagNanos.accumulate(lagNanos);
            if (lagNanos >= CATCH_UP_LAG_NANOS) {
                catchUpSent.incrementAndGet();
            }
        }

        private void sendOne() {
            pending.incrementAndGet();
            try {
                RequestMessage message = messageFactory.create(targetSystemId);
                String topic = properties.topicOf(targetSystemId);

                kafkaTemplate.send(topic, message.requestId(), jsonMapper.writeValueAsString(message))
                        .whenComplete((result, error) -> {
                            if (error != null) {
                                failed.incrementAndGet();
                                log.warn("요청 메시지 전송 실패 : {}", topic, error);
                            } else {
                                sent.incrementAndGet();
                            }
                            pending.decrementAndGet();
                        });
            } catch (RuntimeException e) {
                failed.incrementAndGet();
                pending.decrementAndGet();
                log.warn("요청 메시지 생성 실패 : {}", targetSystemId, e);
            }
        }
    }
}
