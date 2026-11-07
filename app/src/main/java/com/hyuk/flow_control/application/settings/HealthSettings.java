package com.hyuk.flow_control.application.settings;

import com.hyuk.flow_control.domain.health.HealthRules;
import com.hyuk.flow_control.domain.health.LowTrafficRules;

import java.time.Duration;

/** 상태 판정에 쓰는 설정 값. */
public record HealthSettings(
        Duration window,
        int minimumCalls,
        HealthRules rules,
        LowTrafficRules lowTraffic,
        boolean baselineAuto,
        BaselineSettings baseline
) {
}
