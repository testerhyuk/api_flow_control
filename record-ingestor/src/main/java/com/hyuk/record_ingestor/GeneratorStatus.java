package com.hyuk.record_ingestor;

import java.util.Map;

public record GeneratorStatus(
        boolean running,
        long seed,
        Map<String, Target> targets
) {
    public GeneratorStatus {
        targets = Map.copyOf(targets);
    }

    public record Target(double rate, long sent, long failed) {
    }
}
