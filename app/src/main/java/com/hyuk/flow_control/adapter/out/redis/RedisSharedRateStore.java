package com.hyuk.flow_control.adapter.out.redis;

import com.hyuk.flow_control.application.port.out.SharedRateStore;
import com.hyuk.flow_control.application.port.out.SharedRateStoreUnavailableException;
import com.hyuk.flow_control.config.SharedLimitProperties;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

@Component
public class RedisSharedRateStore implements SharedRateStore {
    private static final String PROPOSAL_PREFIX = "flow-control:rate-proposal:";
    private static final String APPLIED_PREFIX = "flow-control:applied-rate:";

    private final StringRedisTemplate redis;
    private final ServerIdentity server;
    private final RedisAvailability availability;
    private final Duration proposalTtl;

    public RedisSharedRateStore(StringRedisTemplate redis, ServerIdentity server, RedisAvailability availability,
                                SharedLimitProperties properties) {
        this.redis = redis;
        this.server = server;
        this.availability = availability;
        this.proposalTtl = properties.proposalTtl();
    }

    @Override
    public List<Double> exchangeProposals(String targetSystemId, double proposedRate) {
        return call("원하는 rate 를 주고받지 못했습니다 : " + targetSystemId, () -> {
            redis.opsForValue().set(proposalKey(targetSystemId, server.id()), Double.toString(proposedRate), proposalTtl);

            List<String> keys = new ArrayList<>();
            ScanOptions options = ScanOptions.scanOptions().match(proposalKey(targetSystemId, "*")).count(100).build();
            try (Cursor<String> cursor = redis.scan(options)) {
                cursor.forEachRemaining(keys::add);
            }

            List<String> values = redis.opsForValue().multiGet(keys);
            if (values == null) {
                return List.of(proposedRate);
            }

            return values.stream()
                    .filter(Objects::nonNull)
                    .map(Double::valueOf)
                    .toList();
        });
    }

    @Override
    public Optional<Double> appliedRate(String targetSystemId) {
        return call("적용된 rate 를 읽지 못했습니다 : " + targetSystemId, () ->
                Optional.ofNullable(redis.opsForValue().get(APPLIED_PREFIX + targetSystemId)).map(Double::valueOf));
    }

    @Override
    public void saveAppliedRate(String targetSystemId, double rate) {
        call("적용된 rate 를 남기지 못했습니다 : " + targetSystemId, () -> {
            redis.opsForValue().set(APPLIED_PREFIX + targetSystemId, Double.toString(rate));
            return null;
        });
    }

    private static String proposalKey(String targetSystemId, String serverId) {
        return PROPOSAL_PREFIX + targetSystemId + ":" + serverId;
    }

    private <T> T call(String message, Supplier<T> action) {
        if (!availability.isAvailable()) {
            throw new SharedRateStoreUnavailableException(message + " (Redis 를 쓸 수 없는 상태)", false, false, null);
        }

        try {
            return action.get();
        } catch (RuntimeException e) {
            availability.markUnavailable(e);
            throw new SharedRateStoreUnavailableException(message, true, RedisErrors.isTimeout(e), e);
        }
    }
}
