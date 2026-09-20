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
import java.util.TimeZone;
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
 *
 * <p><b>复用已起容器模式</b>：加 JVM 参数
 * {@code -Dlumen.test.reuse-containers=true} 时跳过 testcontainers，直接指向本地
 * docker-compose.test.yml 已起的 {@code international-mysql:3307} + {@code international-redis:6379}。
 * 这样跑 IT 时不再拉新容器（节省 5-10s），但每次跑前需要先
 * {@code docker compose -f docker-compose.test.yml up -d}。
 * 配套要求：{@link com.lumen.integration.IntegrationBase.ReusedContainers#init()}
 * 会 ping 一次 MySQL / Redis，连不上就立刻抛错（不像 testcontainers 那样静默等待）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public abstract class IntegrationBase {

    /**
     * 是否复用本地已起的容器。默认 false（testcontainers）。
     * 通过 -Dlumen.test.reuse-containers=true 打开。
     */
    private static final boolean REUSE_CONTAINERS =
            Boolean.parseBoolean(System.getProperty("lumen.test.reuse-containers", "false"));

    /**
     * 复用模式下的连接信息（与 docker-compose.test.yml 对齐）。
     */
    static final class ReusedContainers {
        static final String MYSQL_HOST = System.getProperty("lumen.test.mysql.host", "localhost");
        static final int    MYSQL_PORT = Integer.parseInt(System.getProperty("lumen.test.mysql.port", "3307"));
        static final String MYSQL_DB   = System.getProperty("lumen.test.mysql.db",   "lumen");
        static final String MYSQL_USER = System.getProperty("lumen.test.mysql.user", "lumen");
        static final String MYSQL_PASS = System.getProperty("lumen.test.mysql.pass", "lumen");
        static final String REDIS_HOST = System.getProperty("lumen.test.redis.host", "localhost");
        static final int    REDIS_PORT = Integer.parseInt(System.getProperty("lumen.test.redis.port", "6379"));

        static String jdbcUrl() {
            return "jdbc:mysql://" + MYSQL_HOST + ":" + MYSQL_PORT + "/" + MYSQL_DB
                    + "?useUnicode=true&characterEncoding=utf8&serverTimezone=UTC&useSSL=false&allowPublicKeyRetrieval=true";
        }

        /**
         * ping 一次复用容器，连不上立刻抛 IllegalStateException 让 IT 早死，
         * 不要静默回退到 testcontainers（那样用户体验会很奇怪 —— 跑了半天才发现容器没起来）。
         */
        static void init() {
            try (java.sql.Connection c = java.sql.DriverManager.getConnection(
                    jdbcUrl(), MYSQL_USER, MYSQL_PASS)) {
                if (!c.isValid(3)) throw new IllegalStateException("MySQL not valid");
            } catch (Exception e) {
                throw new IllegalStateException(
                        "Reuse-containers mode (-Dlumen.test.reuse-containers=true) but MySQL "
                                + jdbcUrl() + " is unreachable. Start it with: "
                                + "docker compose -f lumen-parent/lumen-bootstrap/src/main/docker/docker-compose.test.yml up -d",
                        e);
            }
            try (java.net.Socket s = new java.net.Socket()) {
                s.connect(new java.net.InetSocketAddress(REDIS_HOST, REDIS_PORT), 3000);
            } catch (Exception e) {
                throw new IllegalStateException(
                        "Reuse-containers mode but Redis " + REDIS_HOST + ":" + REDIS_PORT + " is unreachable.",
                        e);
            }
        }
    }

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
        // 把 JVM 默认时区设成 UTC，对齐 testcontainers MySQL 容器（默认 UTC）。
        // 这样 DomainEvent.occurredAt (LocalDateTime.now()) 与 MySQL NOW() 都用 UTC，
        // OutboxDispatcher 的 `next_retry_at <= NOW()` 才能匹配刚插入的行。
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));

        if (REUSE_CONTAINERS) {
            // 复用模式：不创建 testcontainers，直接 ping 已起容器
            ReusedContainers.init();
            MYSQL = null;
            REDIS = null;
        } else {
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
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        if (REUSE_CONTAINERS) {
            r.add("spring.datasource.url", ReusedContainers::jdbcUrl);
            r.add("spring.datasource.username", () -> ReusedContainers.MYSQL_USER);
            r.add("spring.datasource.password", () -> ReusedContainers.MYSQL_PASS);
            r.add("spring.data.redis.host", () -> ReusedContainers.REDIS_HOST);
            r.add("spring.data.redis.port", () -> ReusedContainers.REDIS_PORT);
        } else {
            r.add("spring.datasource.url", MYSQL::getJdbcUrl);
            r.add("spring.datasource.username", MYSQL::getUsername);
            r.add("spring.datasource.password", MYSQL::getPassword);
            r.add("spring.data.redis.host", REDIS::getHost);
            r.add("spring.data.redis.port", () -> REDIS.getFirstMappedPort());
        }
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
