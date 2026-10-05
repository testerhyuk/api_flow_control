package com.hyuk.flow_control.application.service;

import com.hyuk.flow_control.application.port.out.TargetSystemPolicyReader;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Semaphore;

@Component
public class TargetSystemSemaphore {
    private final Map<String, Semaphore> semaphores;

    public TargetSystemSemaphore(TargetSystemPolicyReader policyReader) {
        Map<String, Semaphore> created = new HashMap<>();

        policyReader.readAll().forEach((targetSystemId, policy) ->
                created.put(targetSystemId, new Semaphore(policy.concurrencyLimit())));

        this.semaphores = Map.copyOf(created);
    }

    public boolean tryAcquire(String targetSystemId) {
        return semaphoreOf(targetSystemId).tryAcquire();
    }

    public void release(String targetSystemId) {
        semaphoreOf(targetSystemId).release();
    }

    private Semaphore semaphoreOf(String targetSystemId) {
        Semaphore semaphore = semaphores.get(targetSystemId);

        if (semaphore == null) {
            throw new IllegalArgumentException("설정에 없는 대상 시스템입니다 : " + targetSystemId);
        }

        return semaphore;
    }
}
