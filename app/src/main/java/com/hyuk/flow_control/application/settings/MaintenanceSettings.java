package com.hyuk.flow_control.application.settings;

import java.time.Duration;

/** 점검 확인 설정 값. */
public record MaintenanceSettings(Duration probeInterval) {
}
