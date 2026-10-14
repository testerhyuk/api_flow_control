package com.hyuk.flow_control.domain.policy;

public class PolicyVersionConflictException extends RuntimeException {
    public PolicyVersionConflictException(String targetSystemId, long expectedVersion) {
        super("정책이 그사이 바뀌었습니다. 다시 조회한 뒤 바꿔 주세요 : " + targetSystemId + " (보낸 버전 " + expectedVersion + ")");
    }
}
