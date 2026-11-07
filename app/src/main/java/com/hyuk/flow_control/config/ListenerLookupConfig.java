package com.hyuk.flow_control.config;

import com.hyuk.flow_control.application.port.out.PolicyChangeListener;
import com.hyuk.flow_control.application.port.out.PolicyChangeListeners;
import com.hyuk.flow_control.application.port.out.RequestIntakeControl;
import com.hyuk.flow_control.application.port.out.RequestIntakeControlLookup;
import com.hyuk.flow_control.application.port.out.RequesterChangeListener;
import com.hyuk.flow_control.application.port.out.RequesterChangeListeners;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.ClassUtils;

import java.util.List;
import java.util.Optional;

/** 서로 기대는 빈을 쓸 때 꺼내도록 Spring 의 ObjectProvider 로 감싼다. */
@Configuration
public class ListenerLookupConfig {

    @Bean
    PolicyChangeListeners policyChangeListeners(ObjectProvider<PolicyChangeListener> listeners) {
        return new PolicyChangeListeners() {
            @Override
            public List<PolicyChangeListener> all() {
                return listeners.orderedStream().toList();
            }

            @Override
            public String nameOf(PolicyChangeListener listener) {
                return ClassUtils.getUserClass(listener).getSimpleName();
            }
        };
    }

    @Bean
    RequesterChangeListeners requesterChangeListeners(ObjectProvider<RequesterChangeListener> listeners) {
        return () -> listeners.orderedStream().toList();
    }

    @Bean
    RequestIntakeControlLookup requestIntakeControlLookup(ObjectProvider<RequestIntakeControl> intake) {
        return () -> Optional.ofNullable(intake.getIfAvailable());
    }
}
