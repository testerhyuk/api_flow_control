package com.hyuk.flow_control.application.settings;

import java.time.Duration;

/** 평소 응답 시간을 배울 때 쓰는 설정 값. */
public record BaselineSettings(
        Duration window,
        int minimumSamples,
        double lowerRatio,
        double upperRatio
) {
}
