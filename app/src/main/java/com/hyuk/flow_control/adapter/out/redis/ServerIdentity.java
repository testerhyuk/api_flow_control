package com.hyuk.flow_control.adapter.out.redis;

import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.UUID;

@Component
public class ServerIdentity {
    private final String id;

    public ServerIdentity() {
        this.id = hostName() + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    public String id() {
        return id;
    }

    private static String hostName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            return "unknown";
        }
    }
}
