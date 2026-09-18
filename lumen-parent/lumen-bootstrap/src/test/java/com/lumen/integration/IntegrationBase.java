package com.lumen.integration;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.startupcheck.MinimumDurationRunningStartupCheckStrategy;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.stream.Stream;

/**
 * 集成测试基类。
 *
 * 启动真实 MySQL 8.0 + Redis 7-alpine 容器（testcontainers），通过 @DynamicPropertySource
 * 把容器动态端口注入到 Spring 数据源 / Redis 配置中。Flyway 会在容器里执行
 * src/main/resources/db/migration 下的所有 V*__*.sql，自动建表 + 灌种子数据。
 *
 * 子类用 *IT.java 命名，触发 maven-failsafe-plugin（integration-test phase），
 * 不会干扰 maven-surefire-plugin（unit test phase，*Test.java）。
 *
 * 容器生命周期：这里不使用 {@code @Container} 注解，避免 JUnit5 testcontainers 扩展
 * 在每个 IT 类上独立 BeforeAll 启动一个新容器。改为在类初始化块里通过 Startables.deepStart()
 * 启动一次，并利用 JDK 类加载机制确保整个 JVM 内只有一份实例（同一个 ClassLoader 加载一次 IntegrationBase），
 * 从而在 failsafe-plugin（forkCount=1, reuseForks=true）单 JVM 模式下跨所有 IT 类共享容器。
 *
 * 注意：{@link TestRestTemplate} 由 Spring Boot 自动配置（TestRestTemplateContextCustomizer），
 * 默认使用 HttpComponentsClientHttpRequestFactory（依赖 classpath 上的 httpclient5）+ NoOpResponseErrorHandler，
 * 因此 401/403 等错误响应不会抛异常，base URL 也已经指向随机端口。子类直接用注入的 {@code rest} 即可。
 *
 * 不要在 @TestConfiguration 里再注册一个名为 testRestTemplate 的 @Bean，那样会在 bean 工厂
 * 初始化阶段就解析 ${local.server.port} 失败（端口是在 web server bind 之后才注入到环境里的），
 * 进而导致 ApplicationContext 启动失败 —— 这是经验教训。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public abstract class IntegrationBase {

    /**
     * 静态容器：仅在 IntegrationBase 第一次被加载（JVM 内）时启动一次，
     * 后续所有继承此基类的 IT 类都会复用同一对容器。
     *
     * JDK 类加载语义保证了 static 字段的"一次初始化"特性：
     *   - 同一个 ClassLoader 内，类初始化（class init）只会执行一次
     *   - 即使 AuditLoggingIT / AuthFlowIT / ... 这些子类各自被加载，
     *     它们加载 IntegrationBase 这个父类时不会触发父类的重新初始化。
     */
    static final MySQLContainer<?> MYSQL;
    @SuppressWarnings("resource")
    static final GenericContainer<?> REDIS;
    static {
        MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.0"))
                .withDatabaseName("lumen")
                .withUsername("lumen")
                .withPassword("lumen")
                .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_unicode_ci")
                // 跳过 testcontainers 默认的 60s startup check，统一在 deepStart 里等待
                .withStartupCheckStrategy(new MinimumDurationRunningStartupCheckStrategy(Duration.ofSeconds(1)));
        REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                .withExposedPorts(6379)
                .waitingFor(Wait.forListeningPort())
                .withStartupCheckStrategy(new MinimumDurationRunningStartupCheckStrategy(Duration.ofSeconds(1)));

        try {
            Startables.deepStart(Stream.of(MYSQL, REDIS)).join();
        } catch (RuntimeException e) {
            throw new IllegalStateException("Failed to start testcontainers MySQL/Redis: " + e.getMessage(), e);
        }
        // 启动后 JVM 退出时由 Ryuk（testcontainers 自带的 reaper 容器）自动回收
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try { REDIS.stop(); } catch (Exception ignored) {}
            try { MYSQL.stop(); } catch (Exception ignored) {}
        }));
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", MYSQL::getJdbcUrl);
        r.add("spring.datasource.username", MYSQL::getUsername);
        r.add("spring.datasource.password", MYSQL::getPassword);
        r.add("spring.data.redis.host", REDIS::getHost);
        r.add("spring.data.redis.port", () -> REDIS.getFirstMappedPort());
    }

    @LocalServerPort
    protected int port;

    /**
     * Spring Boot 自动配置的 TestRestTemplate：
     *  - requestFactory 走 Apache HttpClient5（classpath 上有 httpclient5）
     *  - 已设置 rootUri=http://localhost:{port}
     *  - 默认 errorHandler 是 NoOpResponseErrorHandler（不会把 4xx/5xx 抛成异常）
     *  - 401/403 等响应不会被 JDK HttpURLConnection 的 streaming 限制阻塞
     */
    @Autowired
    protected TestRestTemplate rest;
}
