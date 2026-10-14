package com.hyuk.mock_target.fault;

import org.apache.catalina.connector.Connector;
import org.springframework.boot.tomcat.TomcatConnectorCustomizer;
import org.springframework.stereotype.Component;

@Component
public class UnbindOnStopCustomizer implements TomcatConnectorCustomizer {

    @Override
    public void customize(Connector connector) {
        connector.setProperty("bindOnInit", "false");
    }
}
