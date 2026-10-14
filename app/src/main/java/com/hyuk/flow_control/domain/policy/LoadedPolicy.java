package com.hyuk.flow_control.domain.policy;

public sealed interface LoadedPolicy {
    String targetSystemId();

    long version();

    record Valid(ManagedPolicy policy) implements LoadedPolicy {
        @Override
        public String targetSystemId() {
            return policy.targetSystemId();
        }

        @Override
        public long version() {
            return policy.version();
        }
    }

    record Invalid(String targetSystemId, long version, String error) implements LoadedPolicy {
    }
}
