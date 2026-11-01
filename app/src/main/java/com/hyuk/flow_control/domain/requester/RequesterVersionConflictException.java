package com.hyuk.flow_control.domain.requester;

public class RequesterVersionConflictException extends RuntimeException {
    public RequesterVersionConflictException(String requesterId, long expectedVersion) {
        super("요청자가 그사이 바뀌었습니다. 다시 조회한 뒤 바꿔 주세요 : " + requesterId + " (보낸 버전 " + expectedVersion + ")");
    }
}
