package com.hyuk.flow_control.application.port.out;

public interface RequestIntakeControl {
    void pause(String targetSystemId);

    void resume(String targetSystemId);
}
