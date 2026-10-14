package com.hyuk.mock_target;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class MockTargetApplication {

    public static void main(String[] args) {
        SpringApplication.run(MockTargetApplication.class, args);
    }
}
