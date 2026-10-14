package com.hyuk.mock_target.fault;

import org.apache.catalina.LifecycleException;
import org.apache.catalina.connector.Connector;
import org.springframework.boot.tomcat.TomcatWebServer;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.stereotype.Component;

@Component
public class TomcatCallPort implements CallPort {
    private final WebServerApplicationContext context;

    public TomcatCallPort(WebServerApplicationContext context) {
        this.context = context;
    }

    @Override
    public synchronized void open() {
        try {
            connector().start();
        } catch (LifecycleException e) {
            throw new IllegalStateException("/call 포트를 열지 못했습니다", e);
        }
    }

    @Override
    public synchronized void close() {
        Connector connector = connector();
        int boundPort = connector.getLocalPort();
        if (connector.getPort() == 0 && boundPort > 0) {
            connector.setPort(boundPort);
        }

        try {
            connector.stop();
        } catch (LifecycleException e) {
            throw new IllegalStateException("/call 포트를 닫지 못했습니다", e);
        }
    }

    private Connector connector() {
        return ((TomcatWebServer) context.getWebServer()).getTomcat().getConnector();
    }
}
