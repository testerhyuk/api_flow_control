package com.hyuk.flow_control.application.service;

import com.hyuk.flow_control.application.port.out.TargetSystemPolicyReader;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class TargetSystemSemaphore {
    private final Map<String, Permits> permits;

    public TargetSystemSemaphore(TargetSystemPolicyReader policyReader) {
        Map<String, Permits> created = new HashMap<>();

        policyReader.readAll().forEach((targetSystemId, policy) ->
                created.put(targetSystemId, new Permits(policy.concurrencyLimit())));

        this.permits = Map.copyOf(created);
    }

    public boolean tryAcquire(String targetSystemId) {
        return permitsOf(targetSystemId).tryAcquire();
    }

    public void release(String targetSystemId) {
        permitsOf(targetSystemId).release();
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

    private Permits permitsOf(String targetSystemId) {
        Permits found = permits.get(targetSystemId);

        if (found == null) {
            throw new IllegalArgumentException("설정에 없는 대상 시스템입니다 : " + targetSystemId);
        }

        return found;
    }

    private static final class Permits {
        private final AtomicInteger inFlight = new AtomicInteger();
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
        }

        private void changeLimit(int limit) {
            this.limit = limit;
        }

        private int limit() {
            return limit;
        }
    }
}
