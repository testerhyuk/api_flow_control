package com.hyuk.flow_control.application.port.out;

import com.hyuk.flow_control.domain.policy.ManagedPolicy;

public interface PolicyChangeListener {
    void onPolicyAdded(ManagedPolicy policy);

    void onPolicyChanged(ManagedPolicy before, ManagedPolicy after);
}
