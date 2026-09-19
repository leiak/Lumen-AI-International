package com.lumen.extension.state;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(StateMachineProperties.class)
public class StateMachineAutoConfiguration {}