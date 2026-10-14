package com.hyuk.flow_control.domain.dryrun;

public class DryRunAlreadyRunningException extends RuntimeException {
    public DryRunAlreadyRunningException(String targetSystemId) {
        super("이미 진행 중인 dry-run 이 있습니다. 취소하거나 끝난 뒤 다시 시작해 주세요 : " + targetSystemId);
    }
}
