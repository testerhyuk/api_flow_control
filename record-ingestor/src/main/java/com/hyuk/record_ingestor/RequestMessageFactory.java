package com.hyuk.record_ingestor;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

@Component
public class RequestMessageFactory {

    public RequestMessage create(String targetSystemId) {
        return new RequestMessage(
                UUID.randomUUID().toString(),
                targetSystemId,
                randomPayload(),
                Instant.now());
    }

    private static String randomPayload() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        long accountNo = random.nextLong(1_000_000_000L, 10_000_000_000L);
        long amount = random.nextLong(1, 1_001) * 1_000;

        return "{\"accountNo\":\"" + accountNo + "\",\"amount\":" + amount + "}";
    }
}
