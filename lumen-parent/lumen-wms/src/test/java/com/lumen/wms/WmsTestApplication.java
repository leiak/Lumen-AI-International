package com.lumen.wms;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.context.annotation.ComponentScan;

/**
 * lumen-wms 模块的最小 Spring Boot 测试入口.
 *
 * <p>主模块 lumen-wms 没有 {@code @SpringBootApplication}（它是个纯库模块，
 * 由 lumen-bootstrap 启动），因此测试需要自己声明一个 SpringBoot 入口.
 * 这里仅扫描 WMS 主数据相关的 bean 与 lumen-common 提供的
 * {@code MybatisPlusConfig}/{@code MyMetaObjectHandler}，其余模块按需裁剪.
 *
 * <p>排除了 Redis 与 Spring Security 自动配置：
 * <ul>
 *   <li>本组测试不需要 Redis 容器</li>
 *   <li>{@code @PreAuthorize} 拦截在 service 层测试中不起作用，且 lumen-wms 没引入
 *       spring-security-starter，会因为缺少 SecurityFilterChain bean 启动失败</li>
 * </ul>
 *
 * <p>{@code @ComponentScan} 显式指定 {@code com.lumen.wms} 与 {@code com.lumen.common}，
 * 覆盖默认的"从本类所在包开始向下扫"——因为本类在 {@code com.lumen.wms}，如果不显式
 * 包含 {@code com.lumen.common}，{@code MybatisPlusConfig}/{@code MyMetaObjectHandler}
 * 不会被注入，会导致 {@code created_at/updated_at/tenant_id} 字段不被填充，INSERT 失败.
 *
 * <p>{@code com.lumen.wms.enums} 下的枚举（{@code WmsInboundStatus} 等）没标
 * {@code @Component}，默认 component scan 不会把它们当 bean.
 */
@SpringBootApplication(exclude = {
        RedisAutoConfiguration.class,
        SecurityAutoConfiguration.class
})
@ComponentScan(basePackages = {"com.lumen.wms", "com.lumen.common"})
@MapperScan(basePackages = {"com.lumen.wms.mapper", "com.lumen.common.audit"})
public class WmsTestApplication {

    public static void main(String[] args) {
        SpringApplication.run(WmsTestApplication.class, args);
    }
}