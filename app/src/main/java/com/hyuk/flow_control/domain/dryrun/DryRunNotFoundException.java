package com.hyuk.flow_control.domain.dryrun;

public class DryRunNotFoundException extends RuntimeException {
    public DryRunNotFoundException(String targetSystemId) {
        super("진행 중인 dry-run 이 없습니다 : " + targetSystemId);
    }
}
