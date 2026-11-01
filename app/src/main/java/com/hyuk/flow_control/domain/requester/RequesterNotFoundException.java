package com.hyuk.flow_control.domain.requester;

public class RequesterNotFoundException extends RuntimeException {
    public RequesterNotFoundException(String requesterId) {
        super("등록되지 않은 요청자입니다 : " + requesterId);
    }
}
