package com.hyuk.flow_control.config;

import com.hyuk.flow_control.application.settings.ControlSettings;
import com.hyuk.flow_control.application.settings.DryRunSettings;
import com.hyuk.flow_control.application.settings.HealthSettings;
import com.hyuk.flow_control.application.settings.IntakeSettings;
import com.hyuk.flow_control.application.settings.LimitAdjustmentSettings;
import com.hyuk.flow_control.application.settings.MaintenanceSettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 설정 파일에서 읽은 값을 application 이 쓰는 설정 값으로 바꿔 넘긴다. */
@Configuration
public class ApplicationSettingsConfig {

    @Bean
    HealthSettings healthSettings(HealthProperties properties) {
        return properties.settings();
    }

    @Bean
    ControlSettings controlSettings(ControlProperties properties) {
        return properties.settings();
    }

    @Bean
    MaintenanceSettings maintenanceSettings(MaintenanceProperties properties) {
        return properties.settings();
    }

    @Bean
    IntakeSettings intakeSettings(IntakeProperties properties) {
        return properties.settings();
    }

    @Bean
    DryRunSettings dryRunSettings(PolicyProperties properties) {
        return properties.dryRunSettings();
    }

    @Bean
    LimitAdjustmentSettings limitAdjustmentSettings(LimitAdjustmentProperties properties) {
        return properties.settings();
    }
}
