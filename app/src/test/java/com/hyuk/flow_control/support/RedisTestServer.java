package com.hyuk.flow_control.support;

import io.lettuce.core.ClientOptions;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;

public final class RedisTestServer {
    public static final String IMAGE = "redis:8.10.2";
    public static final int PORT = 6379;

    private static final GenericContainer<?> SHARED = newContainer();

    static {
        SHARED.start();
    }

    private RedisTestServer() {
    }

    public static GenericContainer<?> newContainer() {
        return new GenericContainer<>(DockerImageName.parse(IMAGE)).withExposedPorts(PORT);
    }

    public static LettuceConnectionFactory connectionFactory(Duration timeout) {
        return connectionFactory(SHARED, timeout);
    }

    public static LettuceConnectionFactory connectionFactory(GenericContainer<?> container, Duration timeout) {
        LettuceClientConfiguration clientConfiguration = LettuceClientConfiguration.builder()
                .commandTimeout(timeout)
                .clientOptions(ClientOptions.builder()
                        .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                        .build())
                .build();
        LettuceConnectionFactory factory = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration(container.getHost(), container.getMappedPort(PORT)),
                clientConfiguration);
        factory.afterPropertiesSet();
        factory.start();
        return factory;
    }
}
