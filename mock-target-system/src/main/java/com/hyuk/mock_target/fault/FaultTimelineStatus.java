package com.hyuk.mock_target.fault;

public record FaultTimelineStatus(
        State state,
        long elapsedSeconds,
        int completedSteps,
        int totalSteps,
        int failedSteps,
        Next next) {

    public enum State {
        RUNNING,
        FINISHED,
        STOPPED
    }

    public record Next(long atSeconds, String action, String type, Double value) {
    }
}
