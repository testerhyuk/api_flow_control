package com.hyuk.flow_control;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class FlowControlApplication {

	public static void main(String[] args) {
		SpringApplication.run(FlowControlApplication.class, args);
	}

}
