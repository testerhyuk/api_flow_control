package com.hyuk.mock_target.simulation;

import java.util.concurrent.ThreadLocalRandom;

public interface RandomSource {
    double nextGaussian();

    double nextDouble();

    static RandomSource threadLocal() {
        return new RandomSource() {
            @Override
            public double nextGaussian() {
                return ThreadLocalRandom.current().nextGaussian();
            }

            @Override
            public double nextDouble() {
                return ThreadLocalRandom.current().nextDouble();
            }
        };
    }
}
