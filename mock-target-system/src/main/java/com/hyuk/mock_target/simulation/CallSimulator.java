package com.hyuk.mock_target.simulation;

import com.hyuk.mock_target.config.MockTargetProperties;
import com.hyuk.mock_target.fault.Faults;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;

public class CallSimulator {
    private final MockTargetProperties.TargetSystem spec;
    private final PerSecondLimiter limiter;
    private final WorkSlots slots;
    private final DuplicateRegistry duplicates;
    private final Faults faults;
    private final RandomSource random;
    private final Sleeper sleeper;
    private final JsonMapper jsonMapper;
    private final Runnable onDuplicate;

    public CallSimulator(MockTargetProperties.TargetSystem spec,
                         PerSecondLimiter limiter,
                         WorkSlots slots,
                         DuplicateRegistry duplicates,
                         Faults faults,
                         RandomSource random,
                         Sleeper sleeper,
                         JsonMapper jsonMapper,
                         Runnable onDuplicate) {
        this.spec = spec;
        this.limiter = limiter;
        this.slots = slots;
        this.duplicates = duplicates;
        this.faults = faults;
        this.random = random;
        this.sleeper = sleeper;
        this.jsonMapper = jsonMapper;
        this.onDuplicate = onDuplicate;
    }

    public CallResult handle(String requestId, String payload) throws InterruptedException {
        Long maintenanceRetryAfter = faults.maintenanceRetryAfterSeconds();
        if (maintenanceRetryAfter != null) {
            return new CallResult(Outcome.MAINTENANCE, maintenanceRetryAfter);
        }

        if (!limiter.tryAcquire()) {
            return new CallResult(Outcome.RATE_LIMITED, retryAfterSeconds(spec.rateLimitRetryAfter()));
        }

        if (requestId == null || requestId.isBlank() || !isJson(payload)) {
            return CallResult.of(Outcome.INVALID_REQUEST);
        }

        DuplicateRegistry.Claim claim = duplicates.claim(requestId);
        if (!claim.owner()) {
            onDuplicate.run();
            return claim.awaitResult();
        }

        CallResult result = null;
        try {
            result = process();
            return result;
        } finally {
            duplicates.complete(claim, result);
        }
    }

    private CallResult process() throws InterruptedException {
        if (!slots.enter()) {
            return CallResult.of(Outcome.OVERLOADED);
        }

        try {
            Duration hold = faults.noResponseHold();
            if (hold != null) {
                sleeper.sleep(hold);
            }

            double loadFactor = ProcessingTime.loadFactor(slots.inUse(), slots.capacity(), spec.fullLoadMultiplier());
            double jitter = ProcessingTime.jitter(random.nextGaussian(), spec.p95Ratio());
            sleeper.sleep(ProcessingTime.of(spec.baseProcessingMillis(), loadFactor, jitter, faults.slowMultiplier()));

            return CallResult.of(decideOutcome());
        } finally {
            slots.leave();
        }
    }

    private Outcome decideOutcome() {
        Double faultRate = faults.failureRate();
        double serverErrorRate = faultRate == null ? spec.serverErrorRate() : faultRate;
        double draw = random.nextDouble();

        if (draw < serverErrorRate) {
            return Outcome.SERVER_ERROR;
        }
        if (draw < serverErrorRate + spec.businessRejectRate()) {
            return Outcome.BUSINESS_REJECTED;
        }
        return Outcome.SUCCESS;
    }

    private boolean isJson(String payload) {
        if (payload == null || payload.isBlank()) {
            return false;
        }

        try {
            jsonMapper.readTree(payload);
            return true;
        } catch (JacksonException e) {
            return false;
        }
    }

    private static long retryAfterSeconds(Duration retryAfter) {
        long seconds = retryAfter.toSeconds();
        return retryAfter.toNanosPart() > 0 ? seconds + 1 : seconds;
    }
}
