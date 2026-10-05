package com.hyuk.flow_control.application.port.out;

import com.hyuk.flow_control.domain.policy.TargetSystemPolicy;

import java.util.Map;

public interface TargetSystemPolicyReader {
    TargetSystemPolicy read(String targetSystemId);
    Map<String, TargetSystemPolicy> readAll();
}
