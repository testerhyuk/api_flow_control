package com.hyuk.flow_control.adapter.out.policy;

import com.hyuk.flow_control.application.port.out.TargetSystemPolicyReader;
import com.hyuk.flow_control.config.TargetSystemProperties;
import com.hyuk.flow_control.domain.policy.TargetSystemPolicy;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Component
public class YamlTargetSystemPolicyReader implements TargetSystemPolicyReader {
    private final TargetSystemProperties properties;

    public YamlTargetSystemPolicyReader(TargetSystemProperties properties) {
        this.properties = properties;
    }

    @Override
    public TargetSystemPolicy read(String targetSystemId) {
        TargetSystemProperties.TargetSystem targetSystem = properties.targetSystems().get(targetSystemId);

        if (targetSystem == null) {
            throw new IllegalArgumentException("설정에 없는 대상 시스템입니다 : " + targetSystemId);
        }

        return toPolicy(targetSystem);
    }

    @Override
    public Map<String, TargetSystemPolicy> readAll() {
        Map<String, TargetSystemPolicy> policies = new HashMap<>();

        properties.targetSystems().forEach((targetSystemId, targetSystem) ->
            policies.put(targetSystemId, toPolicy(targetSystem)));

        return Map.copyOf(policies);
    }

    private TargetSystemPolicy toPolicy(TargetSystemProperties.TargetSystem targetSystem) {
        return new TargetSystemPolicy(
                targetSystem.maxTps(),
                targetSystem.baselineRtt(),
                targetSystem.safetyMargin(),
                targetSystem.burstWindow()
        );
    }
}
