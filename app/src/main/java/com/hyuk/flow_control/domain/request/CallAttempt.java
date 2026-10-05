package com.hyuk.flow_control.domain.request;

import java.time.Duration;

public sealed interface CallAttempt {
    record Called(OutboundResult result) implements CallAttempt {
        public Called {
            if (result == null) {
                throw new IllegalArgumentException("OutboundResult는 null일 수 없습니다");
            }
        }
    }

    record BlockedBySemaphore() implements CallAttempt {}

    record BlockedByTokenBucket(Duration retryAfter) implements CallAttempt {
        public BlockedByTokenBucket {
            if (retryAfter == null || retryAfter.isNegative()) {
                throw new IllegalArgumentException("retryAfter는 0 이상이어야 합니다");
            }
        }
    }
}
