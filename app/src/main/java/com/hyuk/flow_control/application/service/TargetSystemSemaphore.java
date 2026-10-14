package com.hyuk.flow_control.application.service;

import com.hyuk.flow_control.application.port.out.PolicyChangeListener;
import com.hyuk.flow_control.application.port.out.TargetSystemPolicyReader;
import com.hyuk.flow_control.domain.policy.ManagedPolicy;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

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

        private int inFlight() {
            return inFlight.get();
        }
    }
}
