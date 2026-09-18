package com.lumen.masterdata.config;

import com.lumen.common.state.StateMachine;
import com.lumen.common.state.StateMachineRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CountryStateMachineConfig {
    @Bean
    public StateMachine<?, ?, ?> countrySm(StateMachineRegistry reg) {
        StateMachine<String, String, Void> sm = new StateMachine<>("country", "ACTIVE");
        sm.addTransition("ACTIVE", "DISABLE", "DISABLED", null, "禁用");
        sm.addTransition("DISABLED", "ENABLE", "ACTIVE", null, "启用");
        reg.register(sm);
        return sm;
    }
}