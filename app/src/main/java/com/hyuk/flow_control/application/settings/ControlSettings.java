package com.hyuk.flow_control.application.settings;

import com.hyuk.flow_control.domain.limit.ControlMode;

/** 제어 방식 설정 값. */
public record ControlSettings(ControlMode controlMode) {
}
