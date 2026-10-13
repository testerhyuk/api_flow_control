package com.hyuk.flow_control;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
        "flow-control.intake.auto-startup=false",
        "spring.kafka.admin.auto-create=false"
})
class FlowControlApplicationTests {

	@Test
	void contextLoads() {
	}

}
