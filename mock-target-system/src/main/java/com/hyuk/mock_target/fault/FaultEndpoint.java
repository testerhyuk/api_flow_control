package com.hyuk.mock_target.fault;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.actuate.endpoint.InvalidEndpointRequestException;
import org.springframework.boot.actuate.endpoint.annotation.DeleteOperation;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.Selector;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.function.Supplier;

@Component
@Endpoint(id = "faults")
public class FaultEndpoint {
    private final FaultService faultService;

    public FaultEndpoint(FaultService faultService) {
        this.faultService = faultService;
    }

    @ReadOperation
    public Map<String, Object> faults() {
        return faultService.describe();
    }

    @WriteOperation
    public Map<String, Object> apply(@Selector String type, @Nullable Double value) {
        return invalidAsBadRequest(() -> faultService.apply(FaultType.fromId(type), value));
    }

    @DeleteOperation
    public Map<String, Object> clear(@Selector String type) {
        return invalidAsBadRequest(() -> faultService.clear(FaultType.fromId(type)));
    }

    @DeleteOperation
    public Map<String, Object> clearAll() {
        return faultService.clearAll();
    }

    private static Map<String, Object> invalidAsBadRequest(Supplier<Map<String, Object>> action) {
        try {
            return action.get();
        } catch (IllegalArgumentException e) {
            throw new InvalidEndpointRequestException(e.getMessage(), e.getMessage());
        }
    }
}
