package com.hyuk.mock_target.config;

import com.hyuk.mock_target.fault.FaultTimeline;
import com.hyuk.mock_target.fault.FaultTimelineController;
import org.springframework.boot.actuate.autoconfigure.web.ManagementContextConfiguration;
import org.springframework.boot.actuate.autoconfigure.web.ManagementContextType;
import org.springframework.context.annotation.Bean;

// actuator 쓰기 API 는 본문을 Map<String, String> 으로만 받아 시간표 목록을 받을 수 없어서, 관리 포트에 컨트롤러를 둔다.
@ManagementContextConfiguration(value = ManagementContextType.CHILD, proxyBeanMethods = false)
public class ManagementPortConfig {

    @Bean
    public FaultTimelineController faultTimelineController(FaultTimeline timeline) {
        return new FaultTimelineController(timeline);
    }
}
