package com.lumen.extension;

import com.lumen.extension.approval.ApprovalAutoConfiguration;
import com.lumen.extension.outbox.OutboxAutoConfiguration;
import com.lumen.extension.state.StateMachineAutoConfiguration;
import org.springframework.context.annotation.Import;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Import({
    OutboxAutoConfiguration.class,
    StateMachineAutoConfiguration.class,
    ApprovalAutoConfiguration.class
})
public @interface EnableExtension {}