package com.hyuk.flow_control.application.settings;

import java.time.Duration;

/** 요청 수신 설정 값. */
public record IntakeSettings(Duration requestTtl) {
}
