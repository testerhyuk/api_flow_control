package com.hyuk.flow_control.domain.requester;

public class RequesterAlreadyExistsException extends RuntimeException {
    public RequesterAlreadyExistsException(String requesterId) {
        super("이미 등록된 요청자입니다 : " + requesterId);
    }
}
