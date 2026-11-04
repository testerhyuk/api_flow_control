package com.hyuk.flow_control.domain.health;

public record LowTrafficRules(
        int enterAfter,
        int consecutiveFailures,
        int consecutiveTimeouts,
        int recoveryCalls
) {
    public LowTrafficRules {
        if (enterAfter < 1) {
            throw new IllegalArgumentException("저트래픽 모드에 들어가는 판정 횟수는 1 이상이어야 합니다");
        }
        if (consecutiveFailures < 1 || consecutiveTimeouts < 1) {
            throw new IllegalArgumentException("저트래픽 모드의 사건 횟수는 1 이상이어야 합니다");
        }
        if (recoveryCalls < 1) {
            throw new IllegalArgumentException("저트래픽 모드의 위험 해제 호출 수는 1 이상이어야 합니다");
        }
    }
}
