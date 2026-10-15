package com.hyuk.record_ingestor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

@Component
public class RequestGenerator implements SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(RequestGenerator.class);
    private static final long MAX_PARK_NANOS = 50_000_000L;

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final RequestMessageFactory messageFactory;
    private final JsonMapper jsonMapper;
    private final IngestorProperties properties;
    private final Map<String, TargetLoop> loops = new ConcurrentHashMap<>();
    private final Map<String, TargetLoop> finished = new ConcurrentHashMap<>();
    private volatile long seed;
    private volatile boolean running;

    public RequestGenerator(
            KafkaTemplate<String, String> kafkaTemplate,
            RequestMessageFactory messageFactory,
            JsonMapper jsonMapper,
            IngestorProperties properties) {
        this.kafkaTemplate = kafkaTemplate;
        this.messageFactory = messageFactory;
        this.jsonMapper = jsonMapper;
        this.properties = properties;
        this.seed = properties.seed();
    }

    @Override
    public void start() {
        Map<String, Double> rates = new HashMap<>();
        properties.targets().forEach((targetSystemId, target) -> rates.put(targetSystemId, target.rate()));
        start(properties.seed(), rates);
    }

    public synchronized GeneratorStatus start(long seed, Map<String, Double> rates) {
        if (rates == null || rates.isEmpty()) {
            throw new IllegalArgumentException("요청을 보낼 대상이 하나 이상 있어야 합니다");
        }
        rates.forEach(RequestGenerator::requirePositive);

        stopLoops();
        finished.clear();
        this.seed = seed;
        rates.forEach(this::startLoop);
        running = true;

        log.info("요청 생성 시작 : seed {} {}", seed, rates);
        return status();
    }

    public synchronized GeneratorStatus changeRate(String targetSystemId, double rate) {
        if (!running) {
            throw new IllegalStateException("요청 생성이 시작되지 않았습니다");
        }
        if (rate < 0) {
            throw new IllegalArgumentException("rate 는 0 이상이어야 합니다");
        }

        TargetLoop loop = loops.get(targetSystemId);
        if (rate == 0) {
            if (loop != null) {
                loop.stop();
                loops.remove(targetSystemId);
                finished.put(targetSystemId, loop);
            }
        } else if (loop == null) {
            startLoop(targetSystemId, rate);
        } else {
            loop.changeRate(rate);
        }

        log.info("요청량 변경 : {} 초당 {}건", targetSystemId, rate);
        return status();
    }

    @Override
    public synchronized void stop() {
        if (!running) {
            return;
        }
        loops.forEach(finished::put);
        stopLoops();
        running = false;
        log.info("요청 생성 중지");
    }

    public synchronized GeneratorStatus stopGenerating() {
        stop();
        return status();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public boolean isAutoStartup() {
        return properties.autoStart();
    }

    public GeneratorStatus status() {
        Map<String, GeneratorStatus.Target> targets = new LinkedHashMap<>();
        finished.forEach((targetSystemId, loop) -> targets.put(targetSystemId, loop.status(0)));
        loops.forEach((targetSystemId, loop) -> targets.put(targetSystemId, loop.status(loop.rate)));
        return new GeneratorStatus(running, seed, targets);
    }

    private void startLoop(String targetSystemId, double rate) {
        TargetLoop previous = finished.remove(targetSystemId);
        TargetLoop loop = new TargetLoop(targetSystemId, rate, new ArrivalSchedule(seed, targetSystemId), previous);
        loops.put(targetSystemId, loop);
        loop.start();
    }

    private void stopLoops() {
        loops.values().forEach(TargetLoop::stop);
        loops.clear();
    }

    private static void requirePositive(String targetSystemId, Double rate) {
        if (rate == null || rate <= 0) {
            throw new IllegalArgumentException("rate 는 0보다 커야 합니다 : " + targetSystemId);
        }
    }

    private final class TargetLoop implements Runnable {
        private final String targetSystemId;
        private final ArrivalSchedule schedule;
        private final AtomicLong sent;
        private final AtomicLong failed;
        private final Thread thread;
        private volatile double rate;
        private volatile boolean rateChanged;
        private volatile boolean stopped;

        private TargetLoop(String targetSystemId, double rate, ArrivalSchedule schedule, TargetLoop previous) {
            this.targetSystemId = targetSystemId;
            this.rate = rate;
            this.schedule = schedule;
            this.sent = new AtomicLong(previous == null ? 0 : previous.sent.get());
            this.failed = new AtomicLong(previous == null ? 0 : previous.failed.get());
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

        private void stop() {
            stopped = true;
            LockSupport.unpark(thread);
            try {
                thread.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        private GeneratorStatus.Target status(double currentRate) {
            return new GeneratorStatus.Target(currentRate, sent.get(), failed.get());
        }

        @Override
        public void run() {
            long due = System.nanoTime() + schedule.nextDelayNanos(rate);

            while (!stopped) {
                if (rateChanged) {
                    rateChanged = false;
                    due = System.nanoTime() + schedule.nextDelayNanos(rate);
                }

                long wait = due - System.nanoTime();
                if (wait > 0) {
                    LockSupport.parkNanos(Math.min(wait, MAX_PARK_NANOS));
                    continue;
                }

                sendOne();
                due += schedule.nextDelayNanos(rate);
            }
        }

        private void sendOne() {
            try {
                RequestMessage message = messageFactory.create(targetSystemId);
                String topic = properties.topicPrefix() + targetSystemId;

                kafkaTemplate.send(topic, message.requestId(), jsonMapper.writeValueAsString(message))
                        .whenComplete((result, error) -> {
                            if (error != null) {
                                failed.incrementAndGet();
                                log.warn("요청 메시지 전송 실패 : {}", topic, error);
                            } else {
                                sent.incrementAndGet();
                            }
                        });
            } catch (RuntimeException e) {
                failed.incrementAndGet();
                log.warn("요청 메시지 생성 실패 : {}", targetSystemId, e);
            }
        }
    }
}
