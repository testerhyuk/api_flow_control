package com.hyuk.flow_control.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

@ConfigurationProperties(prefix = "flow-control.intake")
public record IntakeProperties(
        Duration requestTtl,
        Duration lagInterval,
        int topicReplicas
) {
    public IntakeProperties(Duration requestTtl, Duration lagInterval) {
        this(requestTtl, lagInterval, 1);
    }

    @ConstructorBinding
    public IntakeProperties(Duration requestTtl, Duration lagInterval, @DefaultValue("1") int topicReplicas) {
        if (requestTtl == null || !requestTtl.isPositive()) {
            throw new IllegalArgumentException("request-ttl 은 0보다 커야 합니다");
        }
        if (lagInterval == null || !lagInterval.isPositive()) {
            throw new IllegalArgumentException("lag-interval 은 0보다 커야 합니다");
        }
        if (topicReplicas < 1) {
            throw new IllegalArgumentException("topic-replicas 는 1 이상이어야 합니다");
        }
        this.requestTtl = requestTtl;
        this.lagInterval = lagInterval;
        this.topicReplicas = topicReplicas;
    }
}
