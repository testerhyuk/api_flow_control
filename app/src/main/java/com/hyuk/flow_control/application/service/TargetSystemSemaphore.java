package com.hyuk.flow_control.application.service;

import com.hyuk.flow_control.application.port.out.PolicyChangeListener;
import com.hyuk.flow_control.application.port.out.TargetSystemPolicyReader;
import com.hyuk.flow_control.domain.policy.ManagedPolicy;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

@Component
@Order(0)
public class TargetSystemSemaphore implements PolicyChangeListener {
    private final Map<String, Permits> permits = new ConcurrentHashMap<>();

    public TargetSystemSemaphore(TargetSystemPolicyReader policyReader) {
        policyReader.readAll().forEach((targetSystemId, policy) ->
                permits.put(targetSystemId, new Permits(policy.concurrencyLimit())));
    }

    @Override
    public void onPolicyAdded(ManagedPolicy policy) {
        permits.putIfAbsent(policy.targetSystemId(), new Permits(policy.limits().concurrencyLimit()));
    }

    @Override
    public void onPolicyChanged(ManagedPolicy before, ManagedPolicy after) {
    }

    public boolean tryAcquire(String targetSystemId) {
        return permitsOf(targetSystemId).tryAcquire();
    }

    public void release(String targetSystemId) {
        permitsOf(targetSystemId).release();
    }

    public boolean awaitAvailable(String targetSystemId, Instant deadline) throws InterruptedException {
        return permitsOf(targetSystemId).awaitAvailable(deadline);
    }

    public void changeLimit(String targetSystemId, int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("동시 요청 한도는 1 이상이어야 합니다 : " + limit);
        }
        permitsOf(targetSystemId).changeLimit(limit);
    }

    public int limitOf(String targetSystemId) {
        return permitsOf(targetSystemId).limit();
    }

    public int inFlightOf(String targetSystemId) {
        return permitsOf(targetSystemId).inFlight();
    }

    private Permits permitsOf(String targetSystemId) {
        Permits found = permits.get(targetSystemId);

        if (found == null) {
            throw new IllegalArgumentException("설정에 없는 대상 시스템입니다 : " + targetSystemId);
        }

        return found;
    }

    private static final class Permits {
        private final AtomicInteger inFlight = new AtomicInteger();
        private final AtomicInteger waiting = new AtomicInteger();
        private final ReentrantLock lock = new ReentrantLock();
        private final Condition available = lock.newCondition();
        private volatile int limit;

        private Permits(int limit) {
            this.limit = limit;
        }

        private boolean tryAcquire() {
            while (true) {
                int current = inFlight.get();

                if (current >= limit) {
                    return false;
                }
                if (inFlight.compareAndSet(current, current + 1)) {
                    return true;
                }
            }
        }

        private void release() {
            inFlight.decrementAndGet();
            if (waiting.get() > 0) {
                wake(false);
            }
        }

        private boolean awaitAvailable(Instant deadline) throws InterruptedException {
            lock.lock();
            waiting.incrementAndGet();
            try {
                while (inFlight.get() >= limit) {
                    if (deadline == null) {
                        available.await();
                        continue;
                    }

                    long remaining = Duration.between(Instant.now(), deadline).toNanos();
                    if (remaining <= 0) {
                        return false;
                    }
                    available.awaitNanos(remaining);
                }
                return true;
            } finally {
                waiting.decrementAndGet();
                lock.unlock();
            }
        }

        private void changeLimit(int limit) {
            this.limit = limit;
            if (waiting.get() > 0) {
                wake(true);
            }
        }

        private void wake(boolean all) {
            lock.lock();
            try {
                if (all) {
                    available.signalAll();
                } else {
                    available.signal();
                }
            } finally {
                lock.unlock();
            }
        }

        private int limit() {
            return limit;
        }

        private int inFlight() {
            return inFlight.get();
        }
    }
}
