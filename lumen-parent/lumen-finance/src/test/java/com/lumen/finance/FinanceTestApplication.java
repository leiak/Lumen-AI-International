package com.lumen.finance;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.context.annotation.ComponentScan;

/**
 * lumen-finance 模块的最小 Spring Boot 测试入口.
 *
 * <p>主模块 lumen-finance 没有 {@code @SpringBootApplication}（它是个纯库模块，
 * 由 lumen-bootstrap 启动），因此测试需要自己声明一个 SpringBoot 入口.
 * 这里仅扫描 finance 自身的 bean 与 lumen-common 提供的
 * {@code MybatisPlusConfig}/{@code MyMetaObjectHandler}.
 *
 * <p>排除了 Redis 与 Spring Security 自动配置：
 * <ul>
 *   <li>本组测试不需要 Redis 容器</li>
 *   <li>{@code @PreAuthorize} 拦截在 service 层测试中不起作用，且 lumen-finance 没引入
 *       spring-security-starter，会因为缺少 SecurityFilterChain bean 启动失败</li>
 * </ul>
 *
 * <p>{@code @ComponentScan} 显式指定 {@code com.lumen.finance} 与 {@code com.lumen.common}，
 * 覆盖默认的"从本类所在包开始向下扫"——因为本类在 {@code com.lumen.finance}，如果不显式
 * 包含 {@code com.lumen.common}，{@code MybatisPlusConfig}/{@code MyMetaObjectHandler}
 * 不会被注入，会导致 {@code created_at/updated_at/tenant_id} 字段不被填充，INSERT 失败.
 *
 * <p>4.5D-1 起额外扫描 {@code com.lumen.extension.state}:生产由
 * {@code LumenApplication} 的 {@code scanBasePackages = "com.lumen"} 扫到它,
 * 测试入口必须与生产对齐 —— 否则 {@code AccrualServiceImpl} 依赖的
 * {@code StateMachineEngine} 找不到,整个上下文起不来。
 * 只扫 {@code .state} 这一层,不带 {@code .approval} / {@code .outbox}
 * (那两个包依赖本模块测试迁移里没有的 {@code t_approval_record} / {@code t_event_outbox})。
 * {@code t_state_transition} 表本模块测试迁移里也没有,但
 * {@code StateMachineRegistry.init()} 只建 Caffeine 缓存、不查库,上下文仍能起。
 */
@SpringBootApplication(exclude = {
        RedisAutoConfiguration.class,
        SecurityAutoConfiguration.class
})
@ComponentScan(basePackages = {"com.lumen.finance", "com.lumen.common", "com.lumen.extension.state"})
@MapperScan(basePackages = {"com.lumen.finance.mapper", "com.lumen.common.audit", "com.lumen.extension.state"})
public class FinanceTestApplication {

    public static void main(String[] args) {
        SpringApplication.run(FinanceTestApplication.class, args);
    }
}