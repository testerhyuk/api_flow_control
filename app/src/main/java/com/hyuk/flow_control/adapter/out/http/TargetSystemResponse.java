package com.hyuk.flow_control.adapter.out.http;

public record TargetSystemResponse(
        String requestId,
        String status,
        String body
) {
}
