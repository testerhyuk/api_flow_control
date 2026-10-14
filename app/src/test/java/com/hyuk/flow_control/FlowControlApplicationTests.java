package com.hyuk.flow_control;

import com.hyuk.flow_control.support.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest(properties = {
        "flow-control.intake.auto-startup=false",
        "spring.kafka.admin.auto-create=false"
})
@Import(TestcontainersConfiguration.class)
class FlowControlApplicationTests {

	@Test
	void contextLoads() {
	}

}
