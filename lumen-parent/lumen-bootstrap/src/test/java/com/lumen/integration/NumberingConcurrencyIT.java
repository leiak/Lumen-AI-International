package com.lumen.integration;

import com.lumen.common.api.R;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 场景 6：并发调用 POST /api/v1/number-rules/ORDER/preview 10 次，
 *         必须得到 10 个互不相同的单号（Redis INCR 原子自增保证唯一性）。
 *
 * 测试要点：
 *  - NumberGenerator 优先使用 Redis INCR（在容器内运行），同进程 + 同 Redis 下 INCR 天然唯一。
 *  - 若 Redis 不可达则降级到 DB + 乐观锁（version 字段）。
 *  - 10 个并发请求应当全部成功且不重复。
 */
class NumberingConcurrencyIT extends IntegrationBase {

    private String token;

    @BeforeEach
    void loginAsAdmin() {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        String body = "{\"tenantId\":1,\"username\":\"admin\",\"password\":\"admin123\"}";
        ResponseEntity<R> resp = rest.exchange(
                "/api/v1/auth/login", HttpMethod.POST, new HttpEntity<>(body, h), R.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) resp.getBody().getData();
        token = (String) data.get("accessToken");
    }

    @Test
    void scenario6_concurrentPreview_producesUniqueNumbers() throws Exception {
        final HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.setBearerAuth(token);

        int N = 10;
        ExecutorService exec = Executors.newFixedThreadPool(N);

        Callable<String> task = () -> {
            ResponseEntity<R> r = rest.exchange(
                    "/api/v1/number-rules/ORDER/preview",
                    HttpMethod.POST,
                    new HttpEntity<>("{}", h),
                    R.class);
            assertThat(r.getStatusCode().value()).isEqualTo(200);
            assertThat(r.getBody()).isNotNull();
            assertThat(r.getBody().getCode()).isEqualTo(0);
            // R.data 是 String（NumberGenerator.generate() 直接返回 String）
            Object data = r.getBody().getData();
            return String.valueOf(data);
        };

        Set<String> seen = new HashSet<>();
        java.util.List<Future<String>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < N; i++) {
            futures.add(exec.submit(task));
        }
        for (Future<String> f : futures) {
            String n = f.get(30, TimeUnit.SECONDS);
            assertThat(n).isNotBlank();
            // 单号格式：ORD-yyyyMMdd-NNNNNN（seqLength=6）
            assertThat(n).startsWith("ORD-");
            assertThat(seen.add(n))
                    .as("duplicate number generated: " + n)
                    .isTrue();
        }
        exec.shutdown();
        assertThat(seen).hasSize(N);
    }
}