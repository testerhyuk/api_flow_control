package com.hyuk.flow_control.adapter.out.redis;

import io.lettuce.core.RedisCommandTimeoutException;
import org.springframework.dao.QueryTimeoutException;

import java.util.concurrent.TimeoutException;

public final class RedisErrors {
    private RedisErrors() {
    }

    public static boolean isTimeout(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof QueryTimeoutException
                    || cause instanceof RedisCommandTimeoutException
                    || cause instanceof TimeoutException) {
                return true;
            }
        }
        return false;
    }
}
