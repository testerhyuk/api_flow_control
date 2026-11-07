package com.hyuk.flow_control.application.settings;

import java.time.Duration;

/** dry-run 설정 값. */
public record DryRunSettings(Duration maxDuration) {
}
