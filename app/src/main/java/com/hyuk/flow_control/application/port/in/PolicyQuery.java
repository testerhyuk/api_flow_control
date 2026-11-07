package com.hyuk.flow_control.application.port.in;

import com.hyuk.flow_control.domain.policy.ManagedPolicy;
import com.hyuk.flow_control.domain.policy.TargetSystemPolicy;

import java.util.Collection;
import java.util.Map;

/** 지금 쓰고 있는 외부 서버 정책을 읽는다. */
public interface PolicyQuery {
    ManagedPolicy policyOf(String targetSystemId);

    Collection<ManagedPolicy> policies();

    TargetSystemPolicy read(String targetSystemId);

    Map<String, TargetSystemPolicy> readAll();
}
