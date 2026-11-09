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
 * <p>4.5D-1 起额外扫描 {@code com.lumen.extension.state}：生产由
 * {@code LumenApplication} 的 {@code scanBasePackages = "com.lumen"} 扫到它，
 * 测试入口必须与生产对齐 —— 否则 {@code AccrualServiceImpl} 依赖的
 * {@code StateMachineEngine} 找不到，整个上下文起不来.
 *
 * <p>下面两个注解都要列出 {@code com.lumen.extension.state}，缺一不可，但原因不同：
 * <ul>
 *   <li>{@code @ComponentScan}：{@code StateMachineProperties} 不是 {@code @Component}，
 *       靠同包的 {@code StateMachineAutoConfiguration} 上的
 *       {@code @EnableConfigurationProperties(StateMachineProperties.class)} 注册，
 *       不扫这个包就带不上</li>
 *   <li>{@code @MapperScan}：本类显式声明了 {@code @MapperScan}，MyBatis-Plus 的
 *       {@code AutoConfiguredMapperScannerRegistrar} 会退避、不再自动扫描，
 *       {@code StateTransitionMapper} 只能靠显式 {@code @MapperScan} 注册</li>
 * </ul>
 *
 * <p>只扫 {@code com.lumen.extension.state} 一个包，不带 {@code .approval} 与
 * {@code .outbox}，原因有两层：其一，这两包的 mapper（{@code ApprovalRecordMapper}/
 * {@code EventOutboxMapper}）不在本类的 {@code @MapperScan} 名单里，组件一旦扫进来，
 * {@code ApprovalService}/{@code ApprovalController} 构造时就
 * {@code NoSuchBeanDefinitionException}；其二，即便把 mapper 也补上，
 * {@code OutboxDispatcher} 上的 {@code @Scheduled} 轮询（间隔由
 * {@code lumen.outbox.poll-interval-ms} 控制，默认 2000 毫秒）仍会每 2 秒查一次
 * {@code t_event_outbox} 并持续刷 SQL 错日志.
 * 这两包对应的 {@code t_approval_record}/{@code t_event_outbox} 表，本模块测试迁移里本来也没有.
 *
 * <p>{@code t_state_transition} 表本模块测试迁移里也没有，但
 * {@code StateMachineRegistry.init()} 只建 Caffeine 缓存、不查库，所以上下文仍能起.
 * 不过只要测试真正触发一次状态迁移（未 mock {@code StateMachineEngine}），
 * {@code loadFromDb()} 就会 {@code SELECT t_state_transition} —— 届时需在
 * {@code db/migration} 补一张建表迁移（或把 {@code StateMachineEngine} mock 掉），
 * 否则会以 {@code BadSqlGrammarException} 炸在业务代码里.
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