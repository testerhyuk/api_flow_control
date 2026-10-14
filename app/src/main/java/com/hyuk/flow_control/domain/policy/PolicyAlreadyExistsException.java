package com.hyuk.flow_control.domain.policy;

public class PolicyAlreadyExistsException extends RuntimeException {
    public PolicyAlreadyExistsException(String targetSystemId) {
        super("이미 있는 대상 시스템입니다 : " + targetSystemId);
    }
}
