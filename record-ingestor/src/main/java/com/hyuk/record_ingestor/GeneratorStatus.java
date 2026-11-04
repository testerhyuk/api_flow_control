package com.hyuk.record_ingestor;

import java.util.Map;

public record GeneratorStatus(
        State state,
        boolean running,
        long seed,
        Map<String, Target> targets
) {
    public GeneratorStatus {
        targets = Map.copyOf(targets);
    }

    public GeneratorStatus(State state, long seed, Map<String, Target> targets) {
        this(state, state == State.RUNNING, seed, targets);
    }

    public enum State {
        RUNNING,
        STOPPING,
        STOPPED
    }

    public record Target(double rate, long sent, long failed, long maxLagMillis, long catchUpSent) {
    }
}
