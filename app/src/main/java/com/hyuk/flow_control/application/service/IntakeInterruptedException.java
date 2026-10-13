package com.hyuk.flow_control.application.service;

public class IntakeInterruptedException extends RuntimeException {
    public IntakeInterruptedException(InterruptedException cause) {
        super("요청이 한도 대기 중에 중단되었습니다", cause);
    }
}
