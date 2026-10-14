package com.hyuk.flow_control.config;

import com.hyuk.flow_control.domain.limit.ControlMode;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "flow-control")
public record ControlProperties(
        @DefaultValue("adaptive") ControlMode controlMode
) {
    public ControlProperties {
        if (controlMode == null) {
            controlMode = ControlMode.ADAPTIVE;
        }
    }
}
