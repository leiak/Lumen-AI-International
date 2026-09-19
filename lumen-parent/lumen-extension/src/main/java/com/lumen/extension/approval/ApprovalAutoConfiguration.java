package com.lumen.extension.approval;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(ApprovalProperties.class)
public class ApprovalAutoConfiguration {}
