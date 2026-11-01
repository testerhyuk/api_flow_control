package com.hyuk.flow_control.domain.request;

import java.time.Instant;

public sealed interface IntakeResult {
    record Called(OutboundResult result) implements IntakeResult {
        public Called {
            if (result == null) {
                throw new IllegalArgumentException("OutboundResult는 null일 수 없습니다");
            }
        }
    }

    record Expired(Instant at) implements IntakeResult {
        public Expired {
            if (at == null) {
                throw new IllegalArgumentException("만료 시각은 null일 수 없습니다");
            }
        }
    }
}
