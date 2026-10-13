package com.hyuk.record_ingestor;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class RecordIngestorApplication {

    public static void main(String[] args) {
        SpringApplication.run(RecordIngestorApplication.class, args);
    }
}
