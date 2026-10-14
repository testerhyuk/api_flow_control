package com.hyuk.flow_control.domain.policy;

public class PolicyNotFoundException extends RuntimeException {
    public PolicyNotFoundException(String targetSystemId) {
        super("정책이 없는 대상 시스템입니다 : " + targetSystemId);
    }

    public PolicyNotFoundException(String targetSystemId, long version) {
        super("이력에 없는 버전입니다 : " + targetSystemId + " 버전 " + version);
    }
}
