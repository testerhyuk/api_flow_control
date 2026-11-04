package com.hyuk.flow_control.domain.dryrun;

public class DryRunNotReadyException extends RuntimeException {
    public DryRunNotReadyException(String targetSystemId, long judgedCalls, int requiredCalls) {
        super("dry-run 으로 판정에 쓴 호출이 " + judgedCalls + "건이라 판정 개수 " + requiredCalls
                + "건보다 적어서 적용할 수 없습니다. 더 기다리거나, 급하면 정책 수정 API 를 써 주세요 : " + targetSystemId);
    }
}
