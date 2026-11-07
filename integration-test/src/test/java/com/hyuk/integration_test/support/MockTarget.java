package com.hyuk.integration_test.support;

import java.util.List;

public enum MockTarget {
    A("target-a", "mock-target-a", 9101),
    B("target-b", "mock-target-b", 9102),
    C("target-c", "mock-target-c", 9103);

    private final String id;
    private final String service;
    private final int adminPort;

    MockTarget(String id, String service, int adminPort) {
        this.id = id;
        this.service = service;
        this.adminPort = adminPort;
    }

    public static List<MockTarget> all() {
        return List.of(values());
    }

    public String id() {
        return id;
    }

    public int servicePort() {
        return adminPort - 100;
    }

    public String internalUrl() {
        return "http://" + service + ":" + servicePort();
    }

    public String instance() {
        return service + ":" + adminPort;
    }

    public String healthUrl() {
        return "http://localhost:" + adminPort + "/actuator/health";
    }

    public void applyFault(String type, double value) {
        Http.post(faultsUrl() + "/" + type, "{\"value\":" + value + "}");
    }

    public void clearFault(String type) {
        Http.delete(faultsUrl() + "/" + type);
    }

    public void clearAllFaults() {
        Http.delete(faultsUrl());
    }

    private String faultsUrl() {
        return "http://localhost:" + adminPort + "/actuator/faults";
    }
}
