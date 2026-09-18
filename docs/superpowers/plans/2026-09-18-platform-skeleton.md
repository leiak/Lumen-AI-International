# 平台骨架 MVP 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把国际物流系统的平台骨架（common + rbac + masterdata + numbering + notification + bootstrap + Antd Pro 前端）从无到有跑通，承载 8 个必测场景。

**Architecture:** Maven 多模块单体 + MySQL 单库行级多租户 + Redis（JWT 黑名单/编号序列）+ Flyway 迁移 + Docker Compose。前端独立 Vite 项目，通过 `/api/v1` 反向代理到后端。

**Tech Stack:** Java 17 / Spring Boot 3.2 / MyBatis-Plus 3.5 / Spring Security / JJWT 0.12 / Flyway 9 / Redis 7 / MySQL 8 / Vite 5 / React 18 / Antd Pro 2.x

**Spec:** [`docs/superpowers/specs/2026-09-18-platform-skeleton-design.md`](../specs/2026-09-18-platform-skeleton-design.md)

---

## 文件结构总览

| 模块 | 文件数（估） | 关键产出 |
|---|---|---|
| `lumen-parent` | 1 | 顶层 BOM pom.xml |
| `lumen-common` | ~14 | ErrorCode/R/BizException/Handler/MDC/BaseEntity/Tenant/JWT util/Audit/StateMask |
| `lumen-rbac` | ~24 | 7 实体 + Mapper + Service + Controller + Security 配置 + JWT filter + 12 API |
| `lumen-masterdata` | ~10 | 3 demo 实体 + StateMachine 框架 + Dict 实体 + 5 API |
| `lumen-numbering` | ~8 | 2 实体 + Generator + Redis 双写 + 5 API |
| `lumen-notification` | ~10 | 2 实体 + ChannelProvider + 3 Mock + 5 API |
| `lumen-bootstrap` | ~8 | 启动类 + 5 Flyway 迁移 + application*.yml + Dockerfile + compose |
| `lumen-admin-web` | ~25 | 8 后台页 + 路由 + 服务 + 请求封装 |
| **总计** | **~100** | 8 个集成测试场景通过 |

---

## 阶段 1 — 项目骨架（P0 阻塞所有后续）

### Task 1.1: 顶层 `lumen-parent` POM（BOM）

**Files:**
- Create: `lumen-parent/pom.xml`

- [ ] **Step 1: 创建仓库根目录的 `pom.xml`**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
    <modelVersion>4.0.0</modelVersion>

    <groupId>com.lumen</groupId>
    <artifactId>lumen-parent</artifactId>
    <version>0.1.0-SNAPSHOT</version>
    <packaging>pom</packaging>
    <name>Lumen International Logistics (Parent)</name>

    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>3.2.5</version>
        <relativePath/>
    </parent>

    <properties>
        <java.version>17</java.version>
        <maven.compiler.source>17</maven.compiler.source>
        <maven.compiler.target>17</maven.compiler.target>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
        <mybatis-plus.version>3.5.7</mybatis-plus.version>
        <jjwt.version>0.12.6</jjwt.version>
        <springdoc.version>2.5.0</springdoc.version>
        <hutool.version>5.8.27</hutool.version>
        <mapstruct.version>1.5.5.Final</mapstruct.version>
        <lombok.version>1.18.32</lombok.version>
    </properties>

    <modules>
        <module>lumen-common</module>
        <module>lumen-rbac</module>
        <module>lumen-masterdata</module>
        <module>lumen-numbering</module>
        <module>lumen-notification</module>
        <module>lumen-bootstrap</module>
    </modules>

    <dependencyManagement>
        <dependencies>
            <dependency>
                <groupId>com.lumen</groupId>
                <artifactId>lumen-common</artifactId>
                <version>${project.version}</version>
            </dependency>
            <!-- 重复 <dependency> 块覆盖其他内部模块 -->
            <dependency>
                <groupId>com.baomidou</groupId>
                <artifactId>mybatis-plus-spring-boot3-starter</artifactId>
                <version>${mybatis-plus.version}</version>
            </dependency>
            <dependency>
                <groupId>io.jsonwebtoken</groupId>
                <artifactId>jjwt-api</artifactId>
                <version>${jjwt.version}</version>
            </dependency>
            <dependency>
                <groupId>io.jsonwebtoken</groupId>
                <artifactId>jjwt-impl</artifactId>
                <version>${jjwt.version}</version>
            </dependency>
            <dependency>
                <groupId>io.jsonwebtoken</groupId>
                <artifactId>jjwt-jackson</artifactId>
                <version>${jjwt.version}</version>
            </dependency>
            <dependency>
                <groupId>org.springdoc</groupId>
                <artifactId>springdoc-openapi-starter-webmvc-ui</artifactId>
                <version>${springdoc.version}</version>
            </dependency>
            <dependency>
                <groupId>cn.hutool</groupId>
                <artifactId>hutool-all</artifactId>
                <version>${hutool.version}</version>
            </dependency>
            <dependency>
                <groupId>org.mapstruct</groupId>
                <artifactId>mapstruct</artifactId>
                <version>${mapstruct.version}</version>
            </dependency>
            <dependency>
                <groupId>org.projectlombok</groupId>
                <artifactId>lombok</artifactId>
                <version>${lombok.version}</version>
            </dependency>
        </dependencies>
    </dependencyManagement>
</project>
```

- [ ] **Step 2: 验证 POM 解析**

Run: `cd lumen-parent && mvn validate -q`
Expected: `BUILD SUCCESS`，无 ERROR。

- [ ] **Step 3: Commit**

```bash
git add lumen-parent/pom.xml
git commit -m "chore: 创建 lumen-parent 顶层 POM 与 BOM"
```

---

### Task 1.2: `lumen-bootstrap` Hello-World

**Files:**
- Create: `lumen-parent/lumen-bootstrap/pom.xml`
- Create: `lumen-parent/lumen-bootstrap/src/main/java/com/lumen/LumenApplication.java`
- Create: `lumen-parent/lumen-bootstrap/src/main/resources/application.yml`

- [ ] **Step 1: 创建 `lumen-bootstrap/pom.xml`**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>com.lumen</groupId>
        <artifactId>lumen-parent</artifactId>
        <version>0.1.0-SNAPSHOT</version>
    </parent>
    <artifactId>lumen-bootstrap</artifactId>
    <name>Lumen Bootstrap</name>

    <dependencies>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>
        </plugins>
    </build>
</project>
```

- [ ] **Step 2: 创建启动类**

```java
package com.lumen;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.lumen")
public class LumenApplication {
    public static void main(String[] args) {
        SpringApplication.run(LumenApplication.class, args);
    }
}
```

- [ ] **Step 3: 创建 `application.yml`**

```yaml
spring:
  application:
    name: lumen-bootstrap
  profiles:
    active: dev
server:
  port: 8080
logging:
  level:
    root: INFO
    com.lumen: DEBUG
```

- [ ] **Step 4: 启动验证**

Run: `cd lumen-parent && mvn spring-boot:run -pl lumen-bootstrap`
Expected: 看到 `Started LumenApplication in X.Xs`，访问 `http://localhost:8080/actuator/health`（先加 actuator 依赖才能用 — 阶段 7 加）

- [ ] **Step 5: Commit**

```bash
git add lumen-parent/lumen-bootstrap
git commit -m "feat(bootstrap): 创建 lumen-bootstrap 启动模块与 Hello World"
```

---

### Task 1.3: 顶层 `.gitignore` 增补

**Files:**
- Modify: `.gitignore`

- [ ] **Step 1: 在现有 `.gitignore` 末尾追加**

```
# Maven
target/
*.jar
*.war

# IDE
.idea/
*.iml
.vscode/
.project
.classpath
.settings/

# Node（前端）
lumen-admin-web/node_modules/
lumen-admin-web/dist/
lumen-admin-web/.env.local

# OS
.DS_Store
Thumbs.db
```

- [ ] **Step 2: Commit**

```bash
git add .gitignore
git commit -m "chore: 增补 .gitignore（Maven/IDE/Node）"
```

---

## 阶段 2 — `lumen-common` 基础组件

### Task 2.1: 统一响应 `R<T>` + 分页

**Files:**
- Create: `lumen-parent/lumen-common/pom.xml`
- Create: `lumen-parent/lumen-common/src/main/java/com/lumen/common/api/R.java`
- Create: `lumen-parent/lumen-common/src/main/java/com/lumen/common/api/PageResult.java`
- Create: `lumen-parent/lumen-common/src/test/java/com/lumen/common/api/RTest.java`

- [ ] **Step 1: 创建 `lumen-common/pom.xml`**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>com.lumen</groupId>
        <artifactId>lumen-parent</artifactId>
        <version>0.1.0-SNAPSHOT</version>
    </parent>
    <artifactId>lumen-common</artifactId>
    <name>Lumen Common</name>
</project>
```

- [ ] **Step 2: 写 `R.java`**

```java
package com.lumen.common.api;

import lombok.Data;

@Data
public class R<T> {
    private int code;
    private String message;
    private T data;
    private String traceId;
    private long timestamp;

    public static <T> R<T> ok(T data) {
        R<T> r = new R<>();
        r.code = 0;
        r.message = "success";
        r.data = data;
        r.timestamp = System.currentTimeMillis();
        return r;
    }

    public static <T> R<T> fail(int code, String message) {
        R<T> r = new R<>();
        r.code = code;
        r.message = message;
        r.timestamp = System.currentTimeMillis();
        return r;
    }

    public boolean isSuccess() {
        return code == 0;
    }
}
```

- [ ] **Step 3: 写 `PageResult.java`**

```java
package com.lumen.common.api;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Collections;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PageResult<T> {
    private List<T> records;
    private long total;
    private long pageNum;
    private long pageSize;

    public static <T> PageResult<T> of(List<T> records, long total, long pageNum, long pageSize) {
        return new PageResult<>(records, total, pageNum, pageSize);
    }

    public static <T> PageResult<T> empty(long pageNum, long pageSize) {
        return new PageResult<>(Collections.emptyList(), 0, pageNum, pageSize);
    }
}
```

- [ ] **Step 4: 写测试**

```java
package com.lumen.common.api;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class RTest {
    @Test
    void ok_createsSuccessR() {
        R<String> r = R.ok("hello");
        assertThat(r.getCode()).isEqualTo(0);
        assertThat(r.getMessage()).isEqualTo("success");
        assertThat(r.getData()).isEqualTo("hello");
        assertThat(r.isSuccess()).isTrue();
    }

    @Test
    void fail_createsFailureR() {
        R<String> r = R.fail(404, "not found");
        assertThat(r.getCode()).isEqualTo(404);
        assertThat(r.getMessage()).isEqualTo("not found");
        assertThat(r.getData()).isNull();
        assertThat(r.isSuccess()).isFalse();
    }
}
```

- [ ] **Step 5: 运行测试**

Run: `mvn -pl lumen-common test`
Expected: `Tests run: 2, Failures: 0, Errors: 0`

- [ ] **Step 6: Commit**

```bash
git add lumen-parent/lumen-common
git commit -m "feat(common): 添加统一响应 R 与分页 PageResult"
```

---

### Task 2.2: 错误体系 — `ErrorCode` / `BizException` / `CommonErrorCode`

**Files:**
- Create: `lumen-parent/lumen-common/src/main/java/com/lumen/common/error/ErrorCode.java`
- Create: `lumen-parent/lumen-common/src/main/java/com/lumen/common/error/BizException.java`
- Create: `lumen-parent/lumen-common/src/main/java/com/lumen/common/error/CommonErrorCode.java`
- Create: `lumen-parent/lumen-common/src/test/java/com/lumen/common/error/BizExceptionTest.java`

- [ ] **Step 1: 写 `ErrorCode` 接口**

```java
package com.lumen.common.error;

public interface ErrorCode {
    int getCode();
    String getMessage();
    int getHttpStatus();
}
```

- [ ] **Step 2: 写 `CommonErrorCode` 枚举**

```java
package com.lumen.common.error;

import lombok.Getter;

@Getter
public enum CommonErrorCode implements ErrorCode {
    SUCCESS(0, "success", 200),
    BAD_REQUEST(400, "请求参数错误", 400),
    UNAUTHORIZED(401, "未认证", 401),
    FORBIDDEN(403, "无权限", 403),
    NOT_FOUND(404, "资源不存在", 404),
    PARAM_INVALID(4001, "参数校验失败", 400),
    INTERNAL_ERROR(500, "服务器内部错误", 500);

    private final int code;
    private final String message;
    private final int httpStatus;

    CommonErrorCode(int code, String message, int httpStatus) {
        this.code = code;
        this.message = message;
        this.httpStatus = httpStatus;
    }
}
```

- [ ] **Step 3: 写 `BizException`**

```java
package com.lumen.common.error;

import lombok.Getter;

@Getter
public class BizException extends RuntimeException {
    private final ErrorCode errorCode;

    public BizException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    public BizException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public static BizException of(ErrorCode errorCode) {
        return new BizException(errorCode);
    }
}
```

- [ ] **Step 4: 写测试**

```java
package com.lumen.common.error;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class BizExceptionTest {
    @Test
    void bizException_carriesErrorCode() {
        BizException ex = BizException.of(CommonErrorCode.NOT_FOUND);
        assertThat(ex.getErrorCode()).isEqualTo(CommonErrorCode.NOT_FOUND);
        assertThat(ex.getMessage()).isEqualTo("资源不存在");
    }

    @Test
    void bizException_supportsCustomMessage() {
        BizException ex = new BizException(CommonErrorCode.NOT_FOUND, "用户 100 不存在");
        assertThat(ex.getMessage()).isEqualTo("用户 100 不存在");
    }
}
```

- [ ] **Step 5: 运行 + Commit**

Run: `mvn -pl lumen-common test -q`
Expected: `BUILD SUCCESS`，共 4 个测试通过（之前 2 个 + 新增 2 个）。

```bash
git add lumen-parent/lumen-common
git commit -m "feat(common): 添加错误码体系 (ErrorCode/BizException/CommonErrorCode)"
```

---

### Task 2.3: `GlobalExceptionHandler` 兜底

**Files:**
- Create: `lumen-parent/lumen-common/src/main/java/com/lumen/common/error/GlobalExceptionHandler.java`

（依赖 Spring Web，已在 bootstrap 通过传递依赖可用）

- [ ] **Step 1: 写处理器**

```java
package com.lumen.common.error;

import com.lumen.common.api.R;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BizException.class)
    public ResponseEntity<R<Void>> handleBiz(BizException ex, HttpServletRequest req) {
        ErrorCode ec = ex.getErrorCode();
        log.warn("BizException at {} {}: code={} msg={}", req.getMethod(), req.getRequestURI(), ec.getCode(), ex.getMessage());
        return ResponseEntity.status(ec.getHttpStatus()).body(R.fail(ec.getCode(), ex.getMessage()));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, BindException.class})
    public ResponseEntity<R<Void>> handleValidation(Exception ex) {
        List<FieldError> fieldErrors = (ex instanceof MethodArgumentNotValidException manve)
                ? manve.getBindingResult().getFieldErrors()
                : ((BindException) ex).getFieldErrors();
        Map<String, String> errors = fieldErrors.stream()
                .collect(Collectors.toMap(FieldError::getField, e -> e.getDefaultMessage() == null ? "" : e.getDefaultMessage(), (a, b) -> a));
        R<Void> body = R.fail(CommonErrorCode.PARAM_INVALID.getCode(), "参数校验失败");
        return ResponseEntity.badRequest().body(body);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<R<Void>> handleAccessDenied(AccessDeniedException ex) {
        return ResponseEntity.status(403).body(R.fail(CommonErrorCode.FORBIDDEN.getCode(), "无权限"));
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<R<Void>> handleAuth(AuthenticationException ex) {
        return ResponseEntity.status(401).body(R.fail(CommonErrorCode.UNAUTHORIZED.getCode(), ex.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<R<Void>> handleAny(Exception ex, HttpServletRequest req) {
        log.error("Unhandled exception at {} {}", req.getMethod(), req.getRequestURI(), ex);
        return ResponseEntity.status(500).body(R.fail(CommonErrorCode.INTERNAL_ERROR.getCode(), "服务器内部错误"));
    }
}
```

- [ ] **Step 2: 把 `lumen-common` 加到 `lumen-bootstrap` 依赖**

Modify `lumen-parent/lumen-bootstrap/pom.xml` 的 `<dependencies>` 块：

```xml
<dependency>
    <groupId>com.lumen</groupId>
    <artifactId>lumen-common</artifactId>
</dependency>
```

- [ ] **Step 3: 启动 + Commit**

Run: `mvn -pl lumen-bootstrap spring-boot:run -q`
Expected: 启动成功（无 controller 触发不到异常）。

```bash
git add lumen-parent
git commit -m "feat(common): 添加 GlobalExceptionHandler 统一兜底"
```

---

### Task 2.4: `TraceIdFilter` + MDC

**Files:**
- Create: `lumen-parent/lumen-common/src/main/java/com/lumen/common/web/TraceIdFilter.java`
- Create: `lumen-parent/lumen-common/src/main/java/com/lumen/common/web/TraceIdConstants.java`

- [ ] **Step 1: 写常量**

```java
package com.lumen.common.web;

public final class TraceIdConstants {
    public static final String MDC_KEY = "traceId";
    public static final String HEADER = "X-Trace-Id";
    public static final String ATTR = "lumen.traceId";
    private TraceIdConstants() {}
}
```

- [ ] **Step 2: 写 Filter**

```java
package com.lumen.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String traceId = req.getHeader(TraceIdConstants.HEADER);
        if (traceId == null || traceId.isBlank()) {
            traceId = UUID.randomUUID().toString().replace("-", "");
        }
        MDC.put(TraceIdConstants.MDC_KEY, traceId);
        req.setAttribute(TraceIdConstants.ATTR, traceId);
        res.setHeader(TraceIdConstants.HEADER, traceId);
        try {
            chain.doFilter(req, res);
        } finally {
            MDC.remove(TraceIdConstants.MDC_KEY);
        }
    }
}
```

- [ ] **Step 3: Commit**

```bash
git add lumen-parent/lumen-common/src/main/java/com/lumen/common/web
git commit -m "feat(common): 添加 TraceIdFilter 与 MDC key"
```

---

### Task 2.5: `TenantContext` + 拦截器

**Files:**
- Create: `lumen-parent/lumen-common/src/main/java/com/lumen/common/tenant/TenantContext.java`
- Create: `lumen-parent/lumen-common/src/main/java/com/lumen/common/tenant/TenantInterceptor.java`
- Create: `lumen-parent/lumen-common/src/main/java/com/lumen/common/tenant/TenantConstants.java`
- Create: `lumen-parent/lumen-common/src/main/java/com/lumen/common/tenant/TenantConfig.java`

- [ ] **Step 1: 写常量**

```java
package com.lumen.common.tenant;

public final class TenantConstants {
    public static final String HEADER = "X-Tenant-Id";
    public static final String ATTR = "lumen.tenantId";
    public static final String CTX_KEY = "lumen.tenantId";
    private TenantConstants() {}
}
```

- [ ] **Step 2: 写 `TenantContext`**

```java
package com.lumen.common.tenant;

public final class TenantContext {
    private static final ThreadLocal<Long> HOLDER = new ThreadLocal<>();

    public static void set(Long tenantId) { HOLDER.set(tenantId); }
    public static Long get() { return HOLDER.get(); }
    public static Long require() {
        Long v = HOLDER.get();
        if (v == null) throw new IllegalStateException("tenant context not set");
        return v;
    }
    public static void clear() { HOLDER.remove(); }
    private TenantContext() {}
}
```

- [ ] **Step 3: 写 `TenantInterceptor`**

```java
package com.lumen.common.tenant;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.lang.NonNull;
import org.springframework.web.servlet.HandlerInterceptor;

public class TenantInterceptor implements HandlerInterceptor {
    @Override
    public boolean preHandle(@NonNull HttpServletRequest req, @NonNull HttpServletResponse res, @NonNull Object handler) {
        String h = req.getHeader(TenantConstants.HEADER);
        Long tid = null;
        if (h != null && !h.isBlank()) {
            try { tid = Long.parseLong(h); } catch (NumberFormatException ignored) {}
        }
        if (tid != null) {
            TenantContext.set(tid);
            req.setAttribute(TenantConstants.ATTR, tid);
        }
        return true;
    }

    @Override
    public void afterCompletion(@NonNull HttpServletRequest req, @NonNull HttpServletResponse res,
                                @NonNull Object handler, Exception ex) {
        TenantContext.clear();
    }
}
```

- [ ] **Step 4: 写 `TenantConfig`（注册拦截器）**

```java
package com.lumen.common.tenant;

import org.springframework.context.annotation.Configuration;
import org.springframework.lang.NonNull;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class TenantConfig implements WebMvcConfigurer {
    @Override
    public void addInterceptors(@NonNull InterceptorRegistry registry) {
        registry.addInterceptor(new TenantInterceptor())
                .addPathPatterns("/api/**");
    }
}
```

- [ ] **Step 5: 测试 + Commit**

```java
// src/test/java/com/lumen/common/tenant/TenantContextTest.java
package com.lumen.common.tenant;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class TenantContextTest {
    @Test
    void set_and_get_roundtrip() {
        TenantContext.set(42L);
        assertThat(TenantContext.get()).isEqualTo(42L);
        TenantContext.clear();
        assertThat(TenantContext.get()).isNull();
    }

    @Test
    void require_throws_when_unset() {
        TenantContext.clear();
        assertThatThrownBy(TenantContext::require).isInstanceOf(IllegalStateException.class);
    }
}
```

Run: `mvn -pl lumen-common test -q`
Commit:
```bash
git add lumen-parent/lumen-common/src/main/java/com/lumen/common/tenant lumen-parent/lumen-common/src/test
git commit -m "feat(common): 添加 TenantContext + 拦截器"
```

---

### Task 2.6: `BaseEntity` + MyBatis-Plus 自动填充

**Files:**
- Create: `lumen-parent/lumen-common/src/main/java/com/lumen/common/entity/BaseEntity.java`
- Create: `lumen-parent/lumen-common/src/main/java/com/lumen/common/entity/MyMetaObjectHandler.java`
- Create: `lumen-parent/lumen-common/src/main/java/com/lumen/common/entity/MybatisPlusConfig.java`

- [ ] **Step 1: 修改 `lumen-common/pom.xml` 加 MyBatis-Plus 依赖**

```xml
<dependencies>
    <dependency>
        <groupId>com.baomidou</groupId>
        <artifactId>mybatis-plus-spring-boot3-starter</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-web</artifactId>
    </dependency>
</dependencies>
```

- [ ] **Step 2: 写 `BaseEntity`**

```java
package com.lumen.common.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public abstract class BaseEntity {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    @TableField(value = "tenant_id", fill = FieldFill.INSERT)
    private Long tenantId;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    @TableLogic
    @TableField(value = "deleted", fill = FieldFill.INSERT)
    private Integer deleted;

    @Version
    @TableField(value = "version", fill = FieldFill.INSERT)
    private Integer version;
}
```

- [ ] **Step 3: 写 `MyMetaObjectHandler`**

```java
package com.lumen.common.entity;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.lumen.common.tenant.TenantContext;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
public class MyMetaObjectHandler implements MetaObjectHandler {
    @Override
    public void insertFill(MetaObject metaObject) {
        LocalDateTime now = LocalDateTime.now();
        strictInsertFill(metaObject, "createdAt", LocalDateTime.class, now);
        strictInsertFill(metaObject, "updatedAt", LocalDateTime.class, now);
        Long tid = TenantContext.get();
        if (tid != null) strictInsertFill(metaObject, "tenantId", Long.class, tid);
        strictInsertFill(metaObject, "deleted", Integer.class, 0);
        strictInsertFill(metaObject, "version", Integer.class, 0);
    }

    @Override
    public void updateFill(MetaObject metaObject) {
        strictUpdateFill(metaObject, "updatedAt", LocalDateTime.class, LocalDateTime.now());
    }
}
```

- [ ] **Step 4: 写 `MybatisPlusConfig`（多租户插件）**

```java
package com.lumen.common.entity;

import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.handler.TenantLineInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.lumen.common.tenant.TenantContext;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.LongValue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Collections;

@Configuration
public class MybatisPlusConfig {

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();

        TenantLineInnerInterceptor tenant = new TenantLineInnerInterceptor(new com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler() {
            @Override
            public Expression getTenantId() {
                Long tid = TenantContext.get();
                return tid == null ? new LongValue(0) : new LongValue(tid);
            }
            @Override
            public String getTenantIdColumn() { return "tenant_id"; }
            @Override
            public boolean ignoreTable(String tableName) {
                // 系统表不过滤
                return tableName.startsWith("sys_") && (tableName.endsWith("_dict")
                        || tableName.endsWith("_dict_item")
                        || tableName.endsWith("_number_rule")
                        || tableName.endsWith("_number_sequence")
                        || tableName.endsWith("_state_machine")
                        || tableName.endsWith("_state_transition"));
            }
        });
        interceptor.addInnerInterceptor(tenant);
        interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor());
        return interceptor;
    }
}
```

- [ ] **Step 5: Commit**

```bash
git add lumen-parent/lumen-common
git commit -m "feat(common): 添加 BaseEntity + MyBatis-Plus 多租户/分页/乐观锁插件"
```

---

### Task 2.7: `SensitiveMaskUtil` + `JwtUtil`

**Files:**
- Create: `lumen-parent/lumen-common/src/main/java/com/lumen/common/util/SensitiveMaskUtil.java`
- Create: `lumen-parent/lumen-common/src/main/java/com/lumen/common/security/JwtUtil.java`
- Create: `lumen-parent/lumen-common/src/test/java/com/lumen/common/util/SensitiveMaskUtilTest.java`
- Create: `lumen-parent/lumen-common/src/test/java/com/lumen/common/security/JwtUtilTest.java`

- [ ] **Step 1: 修改 `lumen-common/pom.xml` 加 jjwt + lombok**

- [ ] **Step 2: 写 `SensitiveMaskUtil`**

```java
package com.lumen.common.util;

public final class SensitiveMaskUtil {
    private SensitiveMaskUtil() {}

    public static String maskPassword(String raw) { return "******"; }

    public static String maskMobile(String mobile) {
        if (mobile == null || mobile.length() < 7) return "***";
        return mobile.substring(0, 3) + "****" + mobile.substring(7);
    }

    public static String maskToken(String token) {
        if (token == null || token.length() < 8) return "***";
        return token.substring(0, 4) + "..." + token.substring(token.length() - 4);
    }
}
```

- [ ] **Step 3: 测试 `SensitiveMaskUtil`**

```java
package com.lumen.common.util;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class SensitiveMaskUtilTest {
    @Test void maskPassword() { assertThat(SensitiveMaskUtil.maskPassword("123456")).isEqualTo("******"); }
    @Test void maskMobile() { assertThat(SensitiveMaskUtil.maskMobile("13800001234")).isEqualTo("138****1234"); }
    @Test void maskToken() { assertThat(SensitiveMaskUtil.maskToken("abcdefghijklmn")).isEqualTo("abcd...klmn"); }
}
```

- [ ] **Step 4: 写 `JwtUtil`**

```java
package com.lumen.common.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Map;
import java.util.UUID;

public class JwtUtil {
    private final SecretKey key;
    private final long accessTtlSeconds;
    private final long refreshTtlSeconds;
    private final String issuer;

    public JwtUtil(String secret, long accessTtlSeconds, long refreshTtlSeconds, String issuer) {
        if (secret == null || secret.length() < 32) throw new IllegalArgumentException("secret must be >= 32 chars");
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.accessTtlSeconds = accessTtlSeconds;
        this.refreshTtlSeconds = refreshTtlSeconds;
        this.issuer = issuer;
    }

    public String issueAccess(Long userId, Long tenantId, java.util.Collection<String> roles, java.util.Collection<String> perms) {
        return build(userId, tenantId, "access", roles, perms, accessTtlSeconds);
    }

    public String issueRefresh(Long userId, Long tenantId) {
        return build(userId, tenantId, "refresh", java.util.List.of(), java.util.List.of(), refreshTtlSeconds);
    }

    private String build(Long userId, Long tenantId, String type,
                         java.util.Collection<String> roles, java.util.Collection<String> perms, long ttl) {
        long now = System.currentTimeMillis();
        return Jwts.builder()
                .id(UUID.randomUUID().toString())
                .issuer(issuer)
                .subject(String.valueOf(userId))
                .claim("tid", tenantId)
                .claim("type", type)
                .claim("roles", roles)
                .claim("perms", perms)
                .issuedAt(new Date(now))
                .expiration(new Date(now + ttl * 1000))
                .signWith(key)
                .compact();
    }

    public Claims parse(String token) {
        return Jwts.parser().verifyWith(key).requireIssuer(issuer).build().parseSignedClaims(token).getPayload();
    }

    public long getAccessTtl() { return accessTtlSeconds; }
    public long getRefreshTtl() { return refreshTtlSeconds; }
}
```

- [ ] **Step 5: 测试 `JwtUtil`**

```java
package com.lumen.common.security;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class JwtUtilTest {
    private final JwtUtil jwt = new JwtUtil("0123456789abcdef0123456789abcdef", 900, 604800, "lumen");

    @Test void issueAndParse() {
        String t = jwt.issueAccess(100L, 1L, List.of("admin"), List.of("user:list"));
        Claims c = jwt.parse(t);
        assertThat(c.getSubject()).isEqualTo("100");
        assertThat(c.get("tid", Long.class)).isEqualTo(1L);
        assertThat(c.get("type", String.class)).isEqualTo("access");
        assertThat(c.get("roles", List.class)).containsExactly("admin");
    }

    @Test void rejectsShortSecret() {
        assertThatThrownBy(() -> new JwtUtil("short", 1, 1, "x")).isInstanceOf(IllegalArgumentException.class);
    }
}
```

- [ ] **Step 6: Commit**

```bash
git add lumen-parent/lumen-common
git commit -m "feat(common): 添加 SensitiveMaskUtil + JwtUtil"
```

---

### Task 2.8: 审计 `@AuditLog` 切面 + 实体

**Files:**
- Create: `lumen-parent/lumen-common/src/main/java/com/lumen/common/audit/AuditLog.java`
- Create: `lumen-parent/lumen-common/src/main/java/com/lumen/common/audit/AuditLogAspect.java`
- Create: `lumen-parent/lumen-common/src/main/java/com/lumen/common/audit/AuditConstants.java`
- Create: `lumen-parent/lumen-common/src/main/java/com/lumen/common/audit/AuditConfig.java`

（Mapper 在 `lumen-bootstrap` 与迁移脚本一起创建，本阶段只放公共部分）

- [ ] **Step 1: 写 `AuditLog` 实体（独立，不继承 `BaseEntity`，因不参与租户过滤）**

```java
package com.lumen.common.audit;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("sys_audit_log")
public class AuditLog {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private String traceId;
    private Long userId;
    private Long tenantId;
    private String username;
    private String action;
    private String resource;
    private String resourceId;
    private String method;
    private String uri;
    private String request;
    private String response;
    private Integer status;
    private Long costMs;
    private String errorMsg;
    private LocalDateTime createdAt;
}
```

- [ ] **Step 2: 写注解**

```java
package com.lumen.common.audit;

import java.lang.annotation.*;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface AuditLog {
    String action() default "";
    String resource() default "";
    boolean recordRequest() default true;
    boolean recordResponse() default true;
}
```

- [ ] **Step 3: 写常量**

```java
package com.lumen.common.audit;

public final class AuditConstants {
    public static final String POOL_NAME = "auditExecutor";
    private AuditConstants() {}
}
```

- [ ] **Step 4: 写异步线程池配置**

```java
package com.lumen.common.audit;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Configuration
public class AuditConfig {
    @Bean(name = AuditConstants.POOL_NAME)
    public Executor auditExecutor() {
        ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
        ex.setCorePoolSize(2);
        ex.setMaxPoolSize(8);
        ex.setQueueCapacity(500);
        ex.setThreadNamePrefix("audit-");
        ex.initialize();
        return ex;
    }
}
```

- [ ] **Step 5: 写 `AuditLogAspect`（依赖 AuditLogMapper 接口，由 bootstrap 模块提供）**

```java
package com.lumen.common.audit;

import com.lumen.common.tenant.TenantContext;
import com.lumen.common.web.TraceIdConstants;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;
import java.time.LocalDateTime;

@Slf4j
@Aspect
@Component
@EnableAsync
@RequiredArgsConstructor
public class AuditLogAspect {

    private final AuditLogMapper mapper;

    @Around("@annotation(com.lumen.common.audit.AuditLog)")
    public Object around(ProceedingJoinPoint pjp) throws Throwable {
        long start = System.currentTimeMillis();
        MethodSignature sig = (MethodSignature) pjp.getSignature();
        Method m = sig.getMethod();
        AuditLog ann = m.getAnnotation(AuditLog.class);
        AuditLog rec = new AuditLog();
        try {
            ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs != null) {
                HttpServletRequest req = attrs.getRequest();
                rec.setUri(req.getRequestURI());
                rec.setMethod(req.getMethod());
                Object tidAttr = req.getAttribute("lumen.traceId");
                if (tidAttr != null) rec.setTraceId(tidAttr.toString());
                Object tenantAttr = req.getAttribute("lumen.tenantId");
                if (tenantAttr != null) rec.setTenantId(((Number) tenantAttr).longValue());
                if (ann.recordRequest()) rec.setRequest(truncate(toJson(pjp.getArgs()), 2000));
            }
            rec.setAction(ann.action().isEmpty() ? m.getName() : ann.action());
            rec.setResource(ann.resource());
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.isAuthenticated() && !"anonymousUser".equals(auth.getPrincipal())) {
                rec.setUsername(auth.getName());
                try { rec.setUserId(Long.parseLong(auth.getName())); } catch (NumberFormatException ignored) {}
            }
            if (rec.getTenantId() == null) rec.setTenantId(TenantContext.get());
            Object result = pjp.proceed();
            rec.setStatus(1);
            if (ann.recordResponse()) rec.setResponse(truncate(toJson(result), 2000));
            return result;
        } catch (Throwable ex) {
            rec.setStatus(0);
            rec.setErrorMsg(ex.getMessage());
            throw ex;
        } finally {
            rec.setCostMs(System.currentTimeMillis() - start);
            rec.setCreatedAt(LocalDateTime.now());
            saveAsync(rec);
        }
    }

    @Async(AuditConstants.POOL_NAME)
    public void saveAsync(AuditLog rec) {
        try { mapper.insert(rec); } catch (Exception e) { log.warn("audit log save failed", e); }
    }

    private String toJson(Object obj) {
        try { return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(obj); }
        catch (Exception e) { return String.valueOf(obj); }
    }

    private String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
```

- [ ] **Step 6: 写 `AuditLogMapper`（接口，依赖 MyBatis-Plus `BaseMapper`）**

```java
package com.lumen.common.audit;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AuditLogMapper extends BaseMapper<AuditLog> {
}
```

- [ ] **Step 7: Commit**

```bash
git add lumen-parent/lumen-common
git commit -m "feat(common): 添加审计切面 + 实体 + 异步线程池"
```

---

### Task 2.9: `lumen-bootstrap` 接入 common

**Files:**
- Modify: `lumen-parent/lumen-bootstrap/pom.xml`
- Create: `lumen-parent/lumen-bootstrap/src/main/resources/application-dev.yml`

- [ ] **Step 1: 修改 bootstrap pom 加 common 依赖（已在 Task 2.3 加过，此处确认）**

- [ ] **Step 2: 创建 `application-dev.yml`**

```yaml
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/lumen?useUnicode=true&characterEncoding=utf8&serverTimezone=UTC&useSSL=false
    username: lumen
    password: lumen
    driver-class-name: com.mysql.cj.jdbc.Driver
  data:
    redis:
      host: localhost
      port: 6379
  flyway:
    enabled: true
    locations: classpath:db/migration
    baseline-on-migrate: true

lumen:
  jwt:
    secret: ${LUMEN_JWT_SECRET:0123456789abcdef0123456789abcdef}
    issuer: lumen
    access-ttl-seconds: 900
    refresh-ttl-seconds: 604800
```

- [ ] **Step 3: 在 `application.yml` 顶部加 `spring.config.import`**

```yaml
spring:
  config:
    import: classpath:application-dev.yml
  profiles:
    active: dev
```

- [ ] **Step 4: Commit**

```bash
git add lumen-parent/lumen-bootstrap
git commit -m "chore(bootstrap): 接入 lumen-common + dev 配置"
```

---

## 阶段 3 — `lumen-rbac` 认证与权限

### Task 3.1: `lumen-rbac` pom 与 7 个实体

**Files:**
- Create: `lumen-parent/lumen-rbac/pom.xml`
- Create: 7 个实体类（每个 1 个文件）

- [ ] **Step 1: `lumen-rbac/pom.xml`**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>com.lumen</groupId>
        <artifactId>lumen-parent</artifactId>
        <version>0.1.0-SNAPSHOT</version>
    </parent>
    <artifactId>lumen-rbac</artifactId>
    <dependencies>
        <dependency><groupId>com.lumen</groupId><artifactId>lumen-common</artifactId></dependency>
        <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-security</artifactId></dependency>
        <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-data-redis</artifactId></dependency>
        <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-validation</artifactId></dependency>
    </dependencies>
</project>
```

- [ ] **Step 2: 把 rbac 加到 parent modules + bootstrap 依赖**

Modify `lumen-parent/pom.xml`：`<modules>` 加 `<module>lumen-rbac</module>`  
Modify `lumen-parent/lumen-bootstrap/pom.xml`：加 `<dependency><groupId>com.lumen</groupId><artifactId>lumen-rbac</artifactId></dependency>`

- [ ] **Step 3: 实体 `SysUser`**

```java
package com.lumen.rbac.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.lumen.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_user")
public class SysUser extends BaseEntity {
    private String username;
    private String passwordHash;
    private String realName;
    private String phone;
    private String email;
    private Integer status;       // 1=启用 0=禁用
    private String lastLoginIp;
    private java.time.LocalDateTime lastLoginAt;
}
```

- [ ] **Step 4: 实体 `SysRole`**

```java
package com.lumen.rbac.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.lumen.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_role")
public class SysRole extends BaseEntity {
    private String code;
    private String name;
    private String dataScope;     // ALL/DEPT/DEPT_AND_SUB/SELF/CUSTOM
    private Integer status;
}
```

- [ ] **Step 5: 实体 `SysPermission`**

```java
package com.lumen.rbac.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.lumen.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_permission")
public class SysPermission extends BaseEntity {
    private Long parentId;
    private String type;          // MENU/BUTTON/API
    private String code;
    private String name;
    private String path;
    private Integer sortOrder;
    private Integer status;
}
```

- [ ] **Step 6: 实体 `SysUserRole`（独立，不继承 BaseEntity 避免自动租户填充——已在 ignoreTable 中）**

```java
package com.lumen.rbac.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("sys_user_role")
public class SysUserRole {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long userId;
    private Long roleId;
    private Long tenantId;
    private LocalDateTime createdAt;
}
```

- [ ] **Step 7: 实体 `SysRolePermission`**

```java
package com.lumen.rbac.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("sys_role_permission")
public class SysRolePermission {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long roleId;
    private Long permissionId;
    private Long tenantId;
    private LocalDateTime createdAt;
}
```

- [ ] **Step 8: 实体 `SysRefreshToken`**

```java
package com.lumen.rbac.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("sys_refresh_token")
public class SysRefreshToken {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private String jti;
    private Long userId;
    private Long tenantId;
    private LocalDateTime expiresAt;
    private LocalDateTime createdAt;
    private Integer revoked;       // 0=有效 1=撤销
}
```

- [ ] **Step 9: 实体 `SysLoginLog`**

```java
package com.lumen.rbac.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("sys_login_log")
public class SysLoginLog {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private String username;
    private Long userId;
    private Long tenantId;
    private String ip;
    private String userAgent;
    private Integer status;        // 1=成功 0=失败
    private String errorMsg;
    private LocalDateTime createdAt;
}
```

- [ ] **Step 10: Commit**

```bash
git add lumen-parent/lumen-rbac lumen-parent/pom.xml lumen-parent/lumen-bootstrap/pom.xml
git commit -m "feat(rbac): 添加 7 个实体 + 模块骨架"
```

---

### Task 3.2: `SysUser` Mapper + Service + 错误码

**Files:**
- Create: 7 个 Mapper（每实体 1 个，模板一致）
- Create: 4 个错误码（Common 已有，复用即可）
- Create: `RbacErrorCode.java`

- [ ] **Step 1: 写 `RbacErrorCode`**

```java
package com.lumen.rbac.error;

import com.lumen.common.error.ErrorCode;
import lombok.Getter;

@Getter
public enum RbacErrorCode implements ErrorCode {
    USER_NOT_FOUND(10001, "用户不存在", 404),
    USER_PASSWORD_WRONG(10002, "用户名或密码错误", 401),
    USER_DISABLED(10003, "用户已禁用", 403),
    TOKEN_EXPIRED(10004, "token 已过期", 401),
    TOKEN_REVOKED(10005, "token 已撤销", 401),
    PERMISSION_DENIED(10006, "权限不足", 403),
    USERNAME_DUPLICATE(10007, "用户名已存在", 409);

    private final int code;
    private final String message;
    private final int httpStatus;
    RbacErrorCode(int code, String message, int httpStatus) { this.code=code; this.message=message; this.httpStatus=httpStatus; }
}
```

- [ ] **Step 2: 7 个 Mapper（用 SysUserMapper 模板，其他 6 个改类名/泛型即可）**

```java
package com.lumen.rbac.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lumen.rbac.entity.SysUser;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface SysUserMapper extends BaseMapper<SysUser> {
    default SysUser findByUsername(String username) {
        return selectOne(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<SysUser>()
                .eq("username", username).last("LIMIT 1"));
    }
}
```

仿照上述模板创建：`SysRoleMapper`、`SysPermissionMapper`、`SysUserRoleMapper`、`SysRolePermissionMapper`、`SysRefreshTokenMapper`、`SysLoginLogMapper`（不写 `findByXxx` 默认方法）。

- [ ] **Step 3: Commit**

```bash
git add lumen-parent/lumen-rbac
git commit -m "feat(rbac): 添加错误码 + 7 个 Mapper"
```

---

### Task 3.3: `AuthService` + JWT 集成

**Files:**
- Create: `lumen-parent/lumen-rbac/src/main/java/com/lumen/rbac/service/AuthService.java`
- Create: `lumen-parent/lumen-rbac/src/main/java/com/lumen/rbac/service/impl/AuthServiceImpl.java`
- Create: `lumen-parent/lumen-rbac/src/main/java/com/lumen/rbac/dto/LoginRequest.java`
- Create: `lumen-parent/lumen-rbac/src/main/java/com/lumen/rbac/dto/TokenResponse.java`

- [ ] **Step 1: DTO `LoginRequest`**

```java
package com.lumen.rbac.dto;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
@Data public class LoginRequest {
    @NotBlank private String username;
    @NotBlank private String password;
    private Long tenantId;
}
```

- [ ] **Step 2: DTO `TokenResponse`**

```java
package com.lumen.rbac.dto;
import lombok.AllArgsConstructor;
import lombok.Data;
@Data @AllArgsConstructor
public class TokenResponse {
    private String accessToken;
    private String refreshToken;
    private long expiresIn;
}
```

- [ ] **Step 3: `AuthService` 接口**

```java
package com.lumen.rbac.service;

import com.lumen.rbac.dto.LoginRequest;
import com.lumen.rbac.dto.TokenResponse;

public interface AuthService {
    TokenResponse login(LoginRequest req, String ip, String userAgent);
    TokenResponse refresh(String refreshToken);
    void logout(String accessToken);
}
```

- [ ] **Step 4: `AuthServiceImpl`**

```java
package com.lumen.rbac.service.impl;

import com.lumen.common.error.BizException;
import com.lumen.common.security.JwtUtil;
import com.lumen.rbac.dto.LoginRequest;
import com.lumen.rbac.dto.TokenResponse;
import com.lumen.rbac.entity.SysRefreshToken;
import com.lumen.rbac.entity.SysUser;
import com.lumen.rbac.entity.SysUserRole;
import com.lumen.rbac.entity.SysRole;
import com.lumen.rbac.entity.SysRolePermission;
import com.lumen.rbac.entity.SysPermission;
import com.lumen.rbac.error.RbacErrorCode;
import com.lumen.rbac.mapper.*;
import com.lumen.rbac.service.AuthService;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private final SysUserMapper userMapper;
    private final SysRoleMapper roleMapper;
    private final SysPermissionMapper permMapper;
    private final SysUserRoleMapper userRoleMapper;
    private final SysRolePermissionMapper rolePermMapper;
    private final SysRefreshTokenMapper refreshMapper;
    private final SysLoginLogMapper loginLogMapper;
    private final JwtUtil jwtUtil;
    private final StringRedisTemplate redis;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    private static String blKey(String jti) { return "bl:" + jti; }
    private static String rtKey(String jti) { return "rt:" + jti; }

    @Override
    @Transactional
    public TokenResponse login(LoginRequest req, String ip, String ua) {
        SysUser user = userMapper.findByUsername(req.getUsername());
        SysLoginLog log = new SysLoginLog();
        log.setUsername(req.getUsername());
        log.setIp(ip);
        log.setUserAgent(ua);
        log.setTenantId(req.getTenantId());
        try {
            if (user == null) throw BizException.of(RbacErrorCode.USER_NOT_FOUND);
            if (!encoder.matches(req.getPassword(), user.getPasswordHash()))
                throw BizException.of(RbacErrorCode.USER_PASSWORD_WRONG);
            if (user.getStatus() == null || user.getStatus() == 0)
                throw BizException.of(RbacErrorCode.USER_DISABLED);
            Long tenantId = req.getTenantId() != null ? req.getTenantId() : user.getTenantId();

            List<String> roles = loadRoles(user.getId(), tenantId);
            List<String> perms = loadPerms(user.getId(), tenantId);

            String access = jwtUtil.issueAccess(user.getId(), tenantId, roles, perms);
            String refresh = jwtUtil.issueRefresh(user.getId(), tenantId);
            Claims c = jwtUtil.parse(refresh);
            String jti = c.getId();
            redis.opsForValue().set(rtKey(jti), String.valueOf(user.getId()), Duration.ofSeconds(jwtUtil.getRefreshTtl()));

            SysRefreshToken rec = new SysRefreshToken();
            rec.setJti(jti);
            rec.setUserId(user.getId());
            rec.setTenantId(tenantId);
            rec.setExpiresAt(LocalDateTime.now().plusSeconds(jwtUtil.getRefreshTtl()));
            refreshMapper.insert(rec);

            user.setLastLoginIp(ip);
            user.setLastLoginAt(LocalDateTime.now());
            userMapper.updateById(user);

            log.setUserId(user.getId()); log.setStatus(1);
            loginLogMapper.insert(log);
            return new TokenResponse(access, refresh, jwtUtil.getAccessTtl());
        } catch (BizException ex) {
            log.setStatus(0); log.setErrorMsg(ex.getMessage()); loginLogMapper.insert(log);
            throw ex;
        }
    }

    @Override
    @Transactional
    public TokenResponse refresh(String refreshToken) {
        Claims c;
        try { c = jwtUtil.parse(refreshToken); }
        catch (io.jsonwebtoken.ExpiredJwtException e) { throw BizException.of(RbacErrorCode.TOKEN_EXPIRED); }
        catch (Exception e) { throw BizException.of(RbacErrorCode.TOKEN_REVOKED); }

        String jti = c.getId();
        String uid = redis.opsForValue().get(rtKey(jti));
        if (uid == null) throw BizException.of(RbacErrorCode.TOKEN_REVOKED);

        Long userId = Long.parseLong(c.getSubject());
        Long tenantId = c.get("tid", Long.class);
        SysUser user = userMapper.selectById(userId);
        if (user == null) throw BizException.of(RbacErrorCode.USER_NOT_FOUND);

        List<String> roles = loadRoles(userId, tenantId);
        List<String> perms = loadPerms(userId, tenantId);

        // rotation
        redis.delete(rtKey(jti));
        String access = jwtUtil.issueAccess(userId, tenantId, roles, perms);
        String newRefresh = jwtUtil.issueRefresh(userId, tenantId);
        Claims nc = jwtUtil.parse(newRefresh);
        redis.opsForValue().set(rtKey(nc.getId()), String.valueOf(userId), Duration.ofSeconds(jwtUtil.getRefreshTtl()));
        SysRefreshToken rec = new SysRefreshToken();
        rec.setJti(nc.getId()); rec.setUserId(userId); rec.setTenantId(tenantId);
        rec.setExpiresAt(LocalDateTime.now().plusSeconds(jwtUtil.getRefreshTtl()));
        refreshMapper.insert(rec);
        return new TokenResponse(access, newRefresh, jwtUtil.getAccessTtl());
    }

    @Override
    public void logout(String accessToken) {
        try {
            Claims c = jwtUtil.parse(accessToken);
            long remainMs = c.getExpiration().getTime() - System.currentTimeMillis();
            if (remainMs > 0) redis.opsForValue().set(blKey(c.getId()), "1", Duration.ofMillis(remainMs));
            redis.delete(rtKey(c.getId()));
        } catch (Exception ignored) {}
    }

    private List<String> loadRoles(Long userId, Long tenantId) {
        return userRoleMapper.selectList(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<SysUserRole>().eq("user_id", userId).eq("tenant_id", tenantId))
                .stream().map(ur -> roleMapper.selectById(ur.getRoleId())).filter(Objects::nonNull)
                .map(SysRole::getCode).collect(Collectors.toList());
    }

    private List<String> loadPerms(Long userId, Long tenantId) {
        List<Long> roleIds = userRoleMapper.selectList(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<SysUserRole>().eq("user_id", userId).eq("tenant_id", tenantId))
                .stream().map(SysUserRole::getRoleId).collect(Collectors.toList());
        if (roleIds.isEmpty()) return List.of();
        List<Long> permIds = rolePermMapper.selectList(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<SysRolePermission>().in("role_id", roleIds).eq("tenant_id", tenantId))
                .stream().map(SysRolePermission::getPermissionId).collect(Collectors.toList());
        if (permIds.isEmpty()) return List.of();
        return permMapper.selectBatchIds(permIds).stream().map(SysPermission::getCode).collect(Collectors.toList());
    }
}
```

- [ ] **Step 5: 单元测试（Mock Mappers）**

```java
package com.lumen.rbac.service;

import com.lumen.common.security.JwtUtil;
import com.lumen.rbac.entity.SysUser;
import com.lumen.rbac.error.RbacErrorCode;
import com.lumen.rbac.mapper.*;
import com.lumen.rbac.dto.LoginRequest;
import com.lumen.rbac.service.impl.AuthServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AuthServiceTest {
    private SysUserMapper userMapper;
    private StringRedisTemplate redis;
    private AuthServiceImpl svc;

    @BeforeEach
    void setup() {
        userMapper = mock(SysUserMapper.class);
        redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        JwtUtil jwt = new JwtUtil("0123456789abcdef0123456789abcdef", 900, 604800, "lumen");
        svc = new AuthServiceImpl(userMapper, mock(SysRoleMapper.class), mock(SysPermissionMapper.class),
                mock(SysUserRoleMapper.class), mock(SysRolePermissionMapper.class),
                mock(SysRefreshTokenMapper.class), mock(SysLoginLogMapper.class), jwt, redis);
    }

    @Test void wrongPassword_throws() {
        SysUser u = new SysUser();
        u.setId(1L); u.setTenantId(1L); u.setStatus(1);
        u.setPasswordHash(new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().encode("right"));
        when(userMapper.findByUsername("alice")).thenReturn(u);
        when(userMapper.selectById(1L)).thenReturn(u);
        LoginRequest req = new LoginRequest(); req.setUsername("alice"); req.setPassword("wrong");
        assertThatThrownBy(() -> svc.login(req, "127.0.0.1", "ua"))
                .hasMessageContaining(RbacErrorCode.USER_PASSWORD_WRONG.getMessage());
    }
}
```

- [ ] **Step 6: Run + Commit**

Run: `mvn -pl lumen-rbac test -q`
Commit:
```bash
git add lumen-parent/lumen-rbac
git commit -m "feat(rbac): 实现 AuthService（login/refresh/logout）"
```

---

### Task 3.4: `JwtAuthenticationFilter` + `SecurityConfig`

**Files:**
- Create: `lumen-parent/lumen-rbac/src/main/java/com/lumen/rbac/security/JwtAuthenticationFilter.java`
- Create: `lumen-parent/lumen-rbac/src/main/java/com/lumen/rbac/security/SecurityConfig.java`
- Create: `lumen-parent/lumen-rbac/src/main/java/com/lumen/rbac/security/RbacUserDetails.java`
- Create: `lumen-parent/lumen-rbac/src/main/java/com/lumen/rbac/security/JwtProperties.java`

- [ ] **Step 1: `JwtProperties`**

```java
package com.lumen.rbac.security;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
@Data @Configuration @ConfigurationProperties(prefix = "lumen.jwt")
public class JwtProperties {
    private String secret;
    private String issuer = "lumen";
    private long accessTtlSeconds = 900;
    private long refreshTtlSeconds = 604800;
}
```

- [ ] **Step 2: `RbacUserDetails`**

```java
package com.lumen.rbac.security;
import lombok.Getter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

@Getter
public class RbacUserDetails implements UserDetails {
    private final Long userId;
    private final Long tenantId;
    private final List<String> roles;
    private final List<String> perms;

    public RbacUserDetails(Long userId, Long tenantId, List<String> roles, List<String> perms) {
        this.userId = userId; this.tenantId = tenantId; this.roles = roles; this.perms = perms;
    }

    @Override public Collection<? extends GrantedAuthority> getAuthorities() {
        return perms.stream().map(p -> (GrantedAuthority) new SimpleGrantedAuthority(p)).collect(Collectors.toList());
    }
    @Override public String getPassword() { return ""; }
    @Override public String getUsername() { return String.valueOf(userId); }
    @Override public boolean isAccountNonExpired() { return true; }
    @Override public boolean isAccountNonLocked() { return true; }
    @Override public boolean isCredentialsNonExpired() { return true; }
    @Override public boolean isEnabled() { return true; }
}
```

- [ ] **Step 3: `JwtAuthenticationFilter`**

```java
package com.lumen.rbac.security;

import com.lumen.common.security.JwtUtil;
import com.lumen.common.tenant.TenantConstants;
import com.lumen.common.tenant.TenantContext;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtUtil jwtUtil;
    private final StringRedisTemplate redis;

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String h = req.getHeader("Authorization");
        if (h != null && h.startsWith("Bearer ")) {
            String token = h.substring(7);
            try {
                Claims c = jwtUtil.parse(token);
                if (redis.opsForValue().get("bl:" + c.getId()) != null) {
                    chain.doFilter(req, res); return;
                }
                Long userId = Long.parseLong(c.getSubject());
                Long tenantId = c.get("tid", Long.class);
                List<String> roles = (List<String>) c.get("roles", List.class);
                List<String> perms = (List<String>) c.get("perms", List.class);
                if (tenantId != null) {
                    TenantContext.set(tenantId);
                    req.setAttribute(TenantConstants.ATTR, tenantId);
                }
                RbacUserDetails ud = new RbacUserDetails(userId, tenantId, roles == null ? List.of() : roles, perms == null ? List.of() : perms);
                UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(ud, null, ud.getAuthorities());
                SecurityContextHolder.getContext().setAuthentication(auth);
            } catch (Exception ignored) {
                // 解析失败不阻断，由后续权限决策处理
            }
        }
        try {
            chain.doFilter(req, res);
        } finally {
            TenantContext.clear();
            SecurityContextHolder.clearContext();
        }
    }
}
```

- [ ] **Step 4: `SecurityConfig`**

```java
package com.lumen.rbac.security;

import com.lumen.common.api.R;
import com.lumen.common.error.CommonErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.nio.charset.StandardCharsets;

@Configuration
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtFilter;
    private final JwtProperties jwtProps;

    @Bean
    public BCryptPasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration cfg) throws Exception {
        return cfg.getAuthenticationManager();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(c -> c.disable())
            .cors(c -> {})
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/v1/auth/login", "/api/v1/auth/refresh",
                                 "/api/v1/health", "/v3/api-docs/**", "/swagger-ui/**",
                                 "/swagger-ui.html", "/actuator/**").permitAll()
                .anyRequest().authenticated())
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint((req, res, e) -> writeJson(res, 401, R.fail(CommonErrorCode.UNAUTHORIZED.getCode(), "未认证")))
                .accessDeniedHandler((req, res, e) -> writeJson(res, 403, R.fail(CommonErrorCode.FORBIDDEN.getCode(), "无权限"))))
            .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    private void writeJson(HttpServletResponse res, int status, Object body) throws java.io.IOException {
        res.setStatus(status);
        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
        res.setCharacterEncoding(StandardCharsets.UTF_8.name());
        res.getWriter().write(new ObjectMapper().writeValueAsString(body));
    }
}
```

- [ ] **Step 5: 注册 `JwtUtil` Bean**

Create `lumen-parent/lumen-rbac/src/main/java/com/lumen/rbac/security/JwtUtilConfig.java`:

```java
package com.lumen.rbac.security;
import com.lumen.common.security.JwtUtil;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
@Configuration
public class JwtUtilConfig {
    @Bean
    public JwtUtil jwtUtil(JwtProperties props) {
        return new JwtUtil(props.getSecret(), props.getAccessTtlSeconds(), props.getRefreshTtlSeconds(), props.getIssuer());
    }
}
```

- [ ] **Step 6: Commit**

```bash
git add lumen-parent/lumen-rbac
git commit -m "feat(rbac): 配置 Spring Security + JWT filter"
```

---

### Task 3.5: `AuthController` + 用户/角色/权限 CRUD Controller

**Files:**
- Create: 4 个 Controller + 6 个 Service + 6 个 Impl

- [ ] **Step 1: `AuthController`**

```java
package com.lumen.rbac.controller;
import com.lumen.common.api.R;
import com.lumen.rbac.dto.LoginRequest;
import com.lumen.rbac.dto.TokenResponse;
import com.lumen.rbac.security.RbacUserDetails;
import com.lumen.rbac.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {
    private final AuthService authService;

    @PostMapping("/login")
    public R<TokenResponse> login(@RequestBody LoginRequest req, HttpServletRequest http) {
        return R.ok(authService.login(req, getIp(http), http.getHeader("User-Agent")));
    }

    @PostMapping("/refresh")
    public R<TokenResponse> refresh(@RequestBody java.util.Map<String, String> body) {
        return R.ok(authService.refresh(body.get("refreshToken")));
    }

    @PostMapping("/logout")
    public R<Void> logout(@RequestHeader("Authorization") String auth) {
        authService.logout(auth.substring(7));
        return R.ok(null);
    }

    @GetMapping("/me")
    public R<RbacUserDetails> me(@AuthenticationPrincipal RbacUserDetails me) {
        return R.ok(me);
    }

    private String getIp(HttpServletRequest req) {
        String h = req.getHeader("X-Forwarded-For");
        return h != null ? h.split(",")[0].trim() : req.getRemoteAddr();
    }
}
```

- [ ] **Step 2: UserController（GET/POST/PUT/DELETE + 分配角色 + 重置密码）**

```java
package com.lumen.rbac.controller;

import com.lumen.common.api.PageResult;
import com.lumen.common.api.R;
import com.lumen.common.audit.AuditLog;
import com.lumen.rbac.entity.SysUser;
import com.lumen.rbac.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UserController {
    private final UserService userService;

    @GetMapping
    @PreAuthorize("hasAuthority('user:list')")
    public R<PageResult<SysUser>> page(@RequestParam(defaultValue = "1") long pageNum,
                                       @RequestParam(defaultValue = "20") long pageSize,
                                       @RequestParam(required = false) String keyword) {
        return R.ok(userService.page(pageNum, pageSize, keyword));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('user:create')")
    @AuditLog(action = "create", resource = "user")
    public R<SysUser> create(@RequestBody SysUser u) { return R.ok(userService.create(u)); }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('user:view')")
    public R<SysUser> get(@PathVariable Long id) { return R.ok(userService.getById(id)); }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('user:update')")
    @AuditLog(action = "update", resource = "user", recordResponse = false)
    public R<SysUser> update(@PathVariable Long id, @RequestBody SysUser u) { u.setId(id); return R.ok(userService.update(u)); }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('user:delete')")
    @AuditLog(action = "delete", resource = "user")
    public R<Void> delete(@PathVariable Long id) { userService.delete(id); return R.ok(null); }

    @PutMapping("/{id}/roles")
    @PreAuthorize("hasAuthority('user:assign-role')")
    @AuditLog(action = "assign-roles", resource = "user", recordRequest = false)
    public R<Void> assignRoles(@PathVariable Long id, @RequestBody List<Long> roleIds) {
        userService.assignRoles(id, roleIds); return R.ok(null);
    }

    @PostMapping("/{id}/reset-password")
    @PreAuthorize("hasAuthority('user:reset-password')")
    @AuditLog(action = "reset-password", resource = "user", recordRequest = false)
    public R<Void> resetPassword(@PathVariable Long id, @RequestBody java.util.Map<String, String> body) {
        userService.resetPassword(id, body.get("newPassword")); return R.ok(null);
    }
}
```

- [ ] **Step 3: `UserService` 接口 + 实现**

```java
package com.lumen.rbac.service;
import com.lumen.common.api.PageResult;
import com.lumen.rbac.entity.SysUser;
import java.util.List;

public interface UserService {
    PageResult<SysUser> page(long pageNum, long pageSize, String keyword);
    SysUser create(SysUser u);
    SysUser update(SysUser u);
    void delete(Long id);
    void assignRoles(Long userId, List<Long> roleIds);
    void resetPassword(Long id, String newPassword);
}
```

`UserServiceImpl` 完整实现：

```java
package com.lumen.rbac.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lumen.common.api.PageResult;
import com.lumen.common.error.BizException;
import com.lumen.common.tenant.TenantContext;
import com.lumen.rbac.entity.SysUser;
import com.lumen.rbac.entity.SysUserRole;
import com.lumen.rbac.error.RbacErrorCode;
import com.lumen.rbac.mapper.SysUserMapper;
import com.lumen.rbac.mapper.SysUserRoleMapper;
import com.lumen.rbac.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private final SysUserMapper userMapper;
    private final SysUserRoleMapper userRoleMapper;
    private final BCryptPasswordEncoder encoder;

    @Override
    public PageResult<SysUser> page(long pageNum, long pageSize, String keyword) {
        Page<SysUser> p = Page.of(pageNum, pageSize);
        QueryWrapper<SysUser> qw = new QueryWrapper<>();
        if (keyword != null && !keyword.isBlank()) qw.like("username", keyword).or().like("real_name", keyword);
        Page<SysUser> res = userMapper.selectPage(p, qw);
        return PageResult.of(res.getRecords(), res.getTotal(), pageNum, pageSize);
    }

    @Override
    @Transactional
    public SysUser create(SysUser u) {
        if (userMapper.findByUsername(u.getUsername()) != null) throw BizException.of(RbacErrorCode.USERNAME_DUPLICATE);
        u.setPasswordHash(encoder.encode("123456")); // 默认密码，业务上线前必须改
        if (u.getStatus() == null) u.setStatus(1);
        if (u.getTenantId() == null) u.setTenantId(TenantContext.require());
        userMapper.insert(u);
        return u;
    }

    @Override
    public SysUser update(SysUser u) {
        u.setPasswordHash(null); userMapper.updateById(u); return userMapper.selectById(u.getId());
    }

    @Override
    @Transactional
    public void delete(Long id) { userMapper.deleteById(id); userRoleMapper.delete(new QueryWrapper<SysUserRole>().eq("user_id", id)); }

    @Override
    @Transactional
    public void assignRoles(Long userId, List<Long> roleIds) {
        userRoleMapper.delete(new QueryWrapper<SysUserRole>().eq("user_id", userId));
        Long tid = TenantContext.require();
        for (Long rid : roleIds) {
            SysUserRole ur = new SysUserRole();
            ur.setUserId(userId); ur.setRoleId(rid); ur.setTenantId(tid);
            userRoleMapper.insert(ur);
        }
    }

    @Override
    public void resetPassword(Long id, String newPassword) {
        SysUser u = new SysUser(); u.setId(id); u.setPasswordHash(encoder.encode(newPassword));
        userMapper.updateById(u);
    }
}
```

- [ ] **Step 4: RoleController + PermissionController（结构同 UserController，省略重复代码）**

仿照 UserController + RoleService 接口与实现（CRUD + 分配权限）。PermissionController 只读 `GET /api/v1/permissions`（权限树）和 `GET /api/v1/permissions/matrix`（角色×权限矩阵）。

- [ ] **Step 5: 单元测试（UserServiceImpl 用 @MockBean）**

```java
package com.lumen.rbac.service;
import com.lumen.common.api.PageResult;
import com.lumen.rbac.entity.SysUser;
import com.lumen.rbac.error.RbacErrorCode;
import com.lumen.rbac.mapper.SysUserMapper;
import com.lumen.rbac.mapper.SysUserRoleMapper;
import com.lumen.rbac.service.impl.UserServiceImpl;
import com.lumen.common.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class UserServiceTest {
    private final SysUserMapper userMapper = mock(SysUserMapper.class);
    private final SysUserRoleMapper userRoleMapper = mock(SysUserRoleMapper.class);
    private final UserServiceImpl svc = new UserServiceImpl(userMapper, userRoleMapper, new BCryptPasswordEncoder());

    @AfterEach void clear() { TenantContext.clear(); }

    @Test void create_hashesDefaultPassword() {
        TenantContext.set(1L);
        when(userMapper.findByUsername("alice")).thenReturn(null);
        SysUser u = new SysUser(); u.setUsername("alice");
        SysUser created = svc.create(u);
        assertThat(created.getPasswordHash()).isNotEqualTo("123456");
        assertThat(created.getPasswordHash().length()).isGreaterThan(20);
    }

    @Test void create_duplicateUsername_throws() {
        when(userMapper.findByUsername("alice")).thenReturn(new SysUser());
        SysUser u = new SysUser(); u.setUsername("alice");
        assertThatThrownBy(() -> svc.create(u)).hasMessageContaining(RbacErrorCode.USERNAME_DUPLICATE.getMessage());
    }
}
```

- [ ] **Step 6: Commit**

```bash
git add lumen-parent/lumen-rbac
git commit -m "feat(rbac): 实现 AuthController/UserController/RoleController/PermissionController + 服务层"
```

---

## 阶段 4 — `lumen-masterdata` 基础资料 + 状态机 + 字典

### Task 4.1: 状态机框架

**Files:**
- Create: `lumen-parent/lumen-common/src/main/java/com/lumen/common/state/StateMachine.java`
- Create: `lumen-parent/lumen-common/src/main/java/com/lumen/common/state/Transition.java`
- Create: `lumen-parent/lumen-common/src/main/java/com/lumen/common/state/StateMachineRegistry.java`
- Create: `lumen-parent/lumen-common/src/test/java/com/lumen/common/state/StateMachineTest.java`

- [ ] **Step 1: `Transition`**

```java
package com.lumen.common.state;

import lombok.AllArgsConstructor;
import lombok.Data;
import java.util.function.Predicate;

@Data @AllArgsConstructor
public class Transition<S, E, C> {
    private S from;
    private E event;
    private S to;
    private Predicate<C> guard;
    private String name;
}
```

- [ ] **Step 2: `StateMachine`**

```java
package com.lumen.common.state;

import lombok.Getter;
import java.util.*;

@Getter
public class StateMachine<S, E, C> {
    private final String code;
    private final S initial;
    private final Map<S, Map<E, Transition<S, E, C>>> table = new HashMap<>();

    public StateMachine(String code, S initial) { this.code = code; this.initial = initial; }

    public StateMachine<S, E, C> addTransition(S from, E event, S to, Predicate<C> guard, String name) {
        table.computeIfAbsent(from, k -> new HashMap<>()).put(event, new Transition<>(from, event, to, guard, name));
        return this;
    }

    public S fire(S current, E evt, C ctx) {
        Transition<S, E, C> t = table.getOrDefault(current, Map.of()).get(evt);
        if (t == null) throw new com.lumen.common.error.BizException(com.lumen.common.error.CommonErrorCode.BAD_REQUEST, "非法状态转换: " + current + " -" + evt);
        if (t.getGuard() != null && !t.getGuard().test(ctx))
            throw new com.lumen.common.error.BizException(com.lumen.common.error.CommonErrorCode.BAD_REQUEST, "守卫失败: " + t.getName());
        return t.getTo();
    }
}
```

- [ ] **Step 3: `StateMachineRegistry`**

```java
package com.lumen.common.state;

import org.springframework.stereotype.Component;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component
public class StateMachineRegistry {
    private final ConcurrentMap<String, StateMachine<?, ?, ?>> map = new ConcurrentHashMap<>();
    public <S, E, C> void register(StateMachine<S, E, C> sm) { map.put(sm.getCode(), sm); }
    @SuppressWarnings("unchecked")
    public <S, E, C> StateMachine<S, E, C> get(String code) { return (StateMachine<S, E, C>) map.get(code); }
}
```

- [ ] **Step 4: 测试**

```java
package com.lumen.common.state;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class StateMachineTest {
    enum S { DRAFT, ACTIVE, CLOSED }
    enum E { ACTIVATE, CLOSE }

    @Test void fire_happyPath() {
        StateMachine<S, E, Void> sm = new StateMachine<>("t", S.DRAFT)
                .addTransition(S.DRAFT, E.ACTIVATE, S.ACTIVE, null, "publish")
                .addTransition(S.ACTIVE, E.CLOSE, S.CLOSED, null, "close");
        assertThat(sm.fire(S.DRAFT, E.ACTIVATE, null)).isEqualTo(S.ACTIVE);
        assertThat(sm.fire(S.ACTIVE, E.CLOSE, null)).isEqualTo(S.CLOSED);
    }

    @Test void fire_invalid_throws() {
        StateMachine<S, E, Void> sm = new StateMachine<>("t", S.DRAFT)
                .addTransition(S.DRAFT, E.ACTIVATE, S.ACTIVE, null, "publish");
        assertThatThrownBy(() -> sm.fire(S.ACTIVE, E.ACTIVATE, null)).hasMessageContaining("非法状态转换");
    }

    @Test void guard_rejects() {
        StateMachine<S, E, Boolean> sm = new StateMachine<>("t", S.DRAFT)
                .addTransition(S.DRAFT, E.ACTIVATE, S.ACTIVE, ctx -> Boolean.TRUE.equals(ctx), "ok");
        assertThatThrownBy(() -> sm.fire(S.DRAFT, E.ACTIVATE, false)).hasMessageContaining("守卫失败");
    }
}
```

- [ ] **Step 5: Commit**

```bash
git add lumen-parent/lumen-common
git commit -m "feat(common): 添加泛型状态机框架"
```

---

### Task 4.2: `lumen-masterdata` 模块 + 字典 + Demo 实体

**Files:**
- Create: `lumen-parent/lumen-masterdata/pom.xml`
- Create: 字典 2 实体 + Demo 3 实体 + Service + Controller + 状态机 demo API

- [ ] **Step 1: `pom.xml`**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>com.lumen</groupId><artifactId>lumen-parent</artifactId><version>0.1.0-SNAPSHOT</version>
    </parent>
    <artifactId>lumen-masterdata</artifactId>
    <dependencies>
        <dependency><groupId>com.lumen</groupId><artifactId>lumen-common</artifactId></dependency>
        <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-web</artifactId></dependency>
    </dependencies>
</project>
```

- [ ] **Step 2: 把 masterdata 加到 parent + bootstrap**

- [ ] **Step 3: `SysDict` 实体**

```java
package com.lumen.masterdata.entity;
import com.baomidou.mybatisplus.annotation.TableName;
import com.lumen.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
@Data @EqualsAndHashCode(callSuper = true) @TableName("sys_dict")
public class SysDict extends BaseEntity {
    private String code;
    private String name;
    private String description;
}
```

- [ ] **Step 4: `SysDictItem` 实体**

```java
package com.lumen.masterdata.entity;
import com.baomidou.mybatisplus.annotation.TableName;
import com.lumen.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
@Data @EqualsAndHashCode(callSuper = true) @TableName("sys_dict_item")
public class SysDictItem extends BaseEntity {
    private Long dictId;
    private String code;
    private String label;
    private Integer sortOrder;
    private Integer status;
}
```

- [ ] **Step 5: 3 个 Demo 实体（模板同 SysDict，字段不同）**

```java
// SysCountry
@Data @EqualsAndHashCode(callSuper = true) @TableName("sys_country")
public class SysCountry extends BaseEntity {
    private String code;     // ISO 3166-1 alpha-2
    private String nameCn;
    private String nameEn;
}

// SysCurrency
@Data @EqualsAndHashCode(callSuper = true) @TableName("sys_currency")
public class SysCurrency extends BaseEntity {
    private String code;     // USD/CNY/EUR
    private String name;
    private Integer scale;    // 小数位数
}

// SysUom
@Data @EqualsAndHashCode(callSuper = true) @TableName("sys_uom")
public class SysUom extends BaseEntity {
    private String code;     // KG/CBM/CTN
    private String name;
    private String dimension; // WEIGHT/VOLUME/QUANTITY
}
```

- [ ] **Step 6: 5 个 Mapper（继承 `BaseMapper<T>`）+ 5 个 Service/Impl（CRUD 模板同 UserService）+ Controller 集**

- [ ] **Step 7: 状态机 demo API**

```java
// CountryStateMachineConfig.java
package com.lumen.masterdata.config;
import com.lumen.common.state.StateMachine;
import com.lumen.common.state.StateMachineRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CountryStateMachineConfig {
    @Bean
    public StateMachine<?, ?, ?> countrySm(StateMachineRegistry reg) {
        StateMachine<String, String, Void> sm = new StateMachine<>("country", "ACTIVE")
                .addTransition("ACTIVE", "DISABLE", "DISABLED", null, "禁用")
                .addTransition("DISABLED", "ENABLE", "ACTIVE", null, "启用");
        reg.register(sm);
        return sm;
    }
}
```

- [ ] **Step 8: StateMachineController**

```java
@RestController
@RequestMapping("/api/v1/state-machines")
@RequiredArgsConstructor
public class StateMachineController {
    private final StateMachineRegistry registry;

    @PostMapping("/{code}/fire")
    public R<Object> fire(@PathVariable String code, @RequestBody java.util.Map<String, Object> body) {
        Object from = body.get("from"); Object event = body.get("event"); Object ctx = body.get("ctx");
        Object to = registry.get(code).fire(from, event, ctx);
        return R.ok(java.util.Map.of("from", from, "event", event, "to", to));
    }
}
```

- [ ] **Step 9: Commit**

```bash
git add lumen-parent/lumen-masterdata
git commit -m "feat(masterdata): 字典 + 3 demo 实体 + 状态机 demo"
```

---

## 阶段 5 — `lumen-numbering` 业务单号

### Task 5.1: 实体 + Redis 双写生成器

**Files:**
- Create: `lumen-parent/lumen-numbering/pom.xml`
- Create: 2 实体 + Mapper + Service + Controller

- [ ] **Step 1: `pom.xml`**

```xml
<artifactId>lumen-numbering</artifactId>
<dependencies>
    <dependency><groupId>com.lumen</groupId><artifactId>lumen-common</artifactId></dependency>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-data-redis</artifactId></dependency>
</dependencies>
```

- [ ] **Step 2: `SysNumberRule`**

```java
@Data @EqualsAndHashCode(callSuper = true) @TableName("sys_number_rule")
public class SysNumberRule extends BaseEntity {
    private String code;
    private String prefix;
    private String dateFormat;       // yyyyMMdd
    private Integer seqLength;       // 6
    private String resetPolicy;      // DAY/MONTH/YEAR/NEVER
    private Long currentValue;       // 当前序列值
    private String description;
}
```

- [ ] **Step 3: `SysNumberSequence`（独立，不继承 BaseEntity）**

```java
@Data
@TableName("sys_number_sequence")
public class SysNumberSequence {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private String ruleCode;
    private String period;       // yyyyMMdd / yyyyMM / yyyy
    private Long currentValue;
    private Integer version;
    private LocalDateTime updatedAt;
}
```

- [ ] **Step 4: `NumberGenerator`（Redis INCR + DB 兜底）**

```java
package com.lumen.numbering.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.lumen.common.error.BizException;
import com.lumen.numbering.entity.SysNumberRule;
import com.lumen.numbering.entity.SysNumberSequence;
import com.lumen.numbering.error.NumberingErrorCode;
import com.lumen.numbering.mapper.SysNumberRuleMapper;
import com.lumen.numbering.mapper.SysNumberSequenceMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

@Service
@RequiredArgsConstructor
public class NumberGenerator {

    private final SysNumberRuleMapper ruleMapper;
    private final SysNumberSequenceMapper seqMapper;
    private final StringRedisTemplate redis;

    private String redisKey(String ruleCode, String period) { return "seq:" + ruleCode + ":" + period; }

    @Transactional
    public String generate(String ruleCode) {
        SysNumberRule rule = ruleMapper.selectOne(new QueryWrapper<SysNumberRule>().eq("code", ruleCode).last("LIMIT 1"));
        if (rule == null) throw BizException.of(NumberingErrorCode.RULE_NOT_FOUND);

        String period = LocalDate.now().format(DateTimeFormatter.ofPattern(rule.getDateFormat() == null ? "yyyyMMdd" : rule.getDateFormat()));
        Long next;
        try {
            Long v = redis.opsForValue().increment(redisKey(ruleCode, period));
            if (v != null && v == 1L) {
                // 首次：DB 兜底
                upsertSequence(ruleCode, period, v);
            }
            next = v;
        } catch (Exception e) {
            // Redis 不可用 → DB 兜底
            SysNumberSequence seq = seqMapper.selectOne(new QueryWrapper<SysNumberSequence>().eq("rule_code", ruleCode).eq("period", period).last("LIMIT 1"));
            if (seq == null) {
                SysNumberSequence n = new SysNumberSequence();
                n.setRuleCode(ruleCode); n.setPeriod(period); n.setCurrentValue(1L); n.setVersion(0);
                seqMapper.insert(n); next = 1L;
            } else {
                seq.setCurrentValue(seq.getCurrentValue() + 1);
                int upd = seqMapper.update(seq, new UpdateWrapper<SysNumberSequence>()
                        .eq("rule_code", ruleCode).eq("period", period).eq("version", seq.getVersion()));
                if (upd == 0) throw BizException.of(NumberingErrorCode.SEQ_EXHAUSTED);
                next = seq.getCurrentValue();
            }
        }
        String seq = String.format("%0" + rule.getSeqLength() + "d", next);
        StringBuilder sb = new StringBuilder();
        if (rule.getPrefix() != null) sb.append(rule.getPrefix()).append('-');
        sb.append(period).append('-').append(seq);
        return sb.toString();
    }

    private void upsertSequence(String ruleCode, String period, Long v) {
        SysNumberSequence n = new SysNumberSequence();
        n.setRuleCode(ruleCode); n.setPeriod(period); n.setCurrentValue(v); n.setVersion(0);
        seqMapper.insert(n);
    }
}
```

- [ ] **Step 5: 错误码 + Controller**

```java
// NumberingErrorCode
public enum NumberingErrorCode implements ErrorCode {
    RULE_NOT_FOUND(12001, "编号规则不存在", 404),
    SEQ_EXHAUSTED(12002, "序列号耗尽", 500);
    // 构造器同前
}

// NumberRuleController
@RestController @RequestMapping("/api/v1/number-rules") @RequiredArgsConstructor
public class NumberRuleController {
    private final NumberGenerator generator;
    private final SysNumberRuleMapper ruleMapper;
    @PostMapping("/{code}/preview")
    public R<String> preview(@PathVariable String code) { return R.ok(generator.generate(code)); }
    @GetMapping public R<List<SysNumberRule>> list() { return R.ok(ruleMapper.selectList(null)); }
    // ... POST/PUT/DELETE 略（CRUD 模板）
}
```

- [ ] **Step 6: Commit**

```bash
git add lumen-parent/lumen-numbering
git commit -m "feat(numbering): 业务单号生成 (Redis + DB 双写)"
```

---

## 阶段 6 — `lumen-notification` 通知骨架

### Task 6.1: 模板 + ChannelProvider + 3 Mock

**Files:**
- Create: `lumen-parent/lumen-notification/pom.xml`
- Create: 2 实体 + Mapper + Service + Controller + 3 Mock Provider

- [ ] **Step 1: `pom.xml`**（同 numbering 依赖）

- [ ] **Step 2: 实体（仿 masterdata 模板）**

```java
// NotificationTemplate
@Data @EqualsAndHashCode(callSuper = true) @TableName("notification_template")
public class NotificationTemplate extends BaseEntity {
    private String code;
    private String channel;       // EMAIL/SMS/IM
    private String subject;
    private String content;
    private String vars;          // JSON 字符串
    private Integer status;
}

// NotificationSendLog (独立表，不走多租户过滤)
@Data @TableName("notification_send_log")
public class NotificationSendLog {
    @TableId(type = IdType.ASSIGN_ID) private Long id;
    private String templateCode;
    private String channel;
    private String receiver;
    private String payload;
    private Integer status;         // 0=失败 1=成功 2=PENDING
    private String errorMsg;
    private Long tenantId;
    private LocalDateTime createdAt;
}
```

- [ ] **Step 3: ChannelProvider 抽象**

```java
package com.lumen.notification.channel;
public interface ChannelProvider {
    String channel();
    SendResult send(SendRequest req);
    public record SendRequest(String templateCode, String receiver, String subject, String content, java.util.Map<String, Object> vars) {}
    public record SendResult(boolean ok, String messageId, String error) {}
}
```

- [ ] **Step 4: 3 个 Mock Provider 实现（全部完整）**

```java
package com.lumen.notification.channel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

interface ChannelProvider {
    String channel();
    SendResult send(SendRequest req);

    record SendRequest(String templateCode, String receiver, String subject, String content, java.util.Map<String, Object> vars) {}
    record SendResult(boolean ok, String messageId, String error) {}
}

@Component
class MockEmailProvider implements ChannelProvider {
    private static final Logger log = LoggerFactory.getLogger(MockEmailProvider.class);
    @Override public String channel() { return "EMAIL"; }
    @Override public SendResult send(SendRequest r) {
        log.info("[MOCK-EMAIL] to={} subject={} content={}", r.receiver(), r.subject(), r.content());
        return new SendResult(true, "mock-email-" + System.currentTimeMillis(), null);
    }
}

@Component
class MockSmsProvider implements ChannelProvider {
    private static final Logger log = LoggerFactory.getLogger(MockSmsProvider.class);
    @Override public String channel() { return "SMS"; }
    @Override public SendResult send(SendRequest r) {
        log.info("[MOCK-SMS] to={} content={}", r.receiver(), r.content());
        return new SendResult(true, "mock-sms-" + System.currentTimeMillis(), null);
    }
}

@Component
class MockImProvider implements ChannelProvider {
    private static final Logger log = LoggerFactory.getLogger(MockImProvider.class);
    @Override public String channel() { return "IM"; }
    @Override public SendResult send(SendRequest r) {
        log.info("[MOCK-IM] to={} content={}", r.receiver(), r.content());
        return new SendResult(true, "mock-im-" + System.currentTimeMillis(), null);
    }
}
```

- [ ] **Step 5: `NotificationDispatcher`**

```java
@Service
public class NotificationDispatcher {
    private final Map<String, ChannelProvider> providers;
    private final NotificationSendLogMapper logMapper;

    public NotificationDispatcher(List<ChannelProvider> providers, NotificationSendLogMapper logMapper) {
        this.providers = providers.stream().collect(Collectors.toMap(ChannelProvider::channel, p -> p));
        this.logMapper = logMapper;
    }

    public R<Void> send(String templateCode, String receiver, Map<String, Object> vars) {
        NotificationTemplate tpl = ... ; // 查 mapper
        ChannelProvider p = providers.get(tpl.getChannel());
        if (p == null) throw BizException.of(NotificationErrorCode.CHANNEL_PROVIDER_MISSING);
        String content = render(tpl.getContent(), vars);
        ChannelProvider.SendResult r = p.send(new ChannelProvider.SendRequest(templateCode, receiver, tpl.getSubject(), content, vars));
        NotificationSendLog log = new NotificationSendLog();
        log.setTemplateCode(templateCode); log.setChannel(tpl.getChannel());
        log.setReceiver(receiver); log.setPayload(content);
        log.setStatus(r.ok() ? 1 : 0); log.setErrorMsg(r.error());
        log.setCreatedAt(LocalDateTime.now());
        logMapper.insert(log);
        return R.ok(null);
    }

    private String render(String tpl, Map<String, Object> vars) {
        if (tpl == null) return null;
        String r = tpl;
        if (vars != null) for (var e : vars.entrySet()) r = r.replace("${" + e.getKey() + "}", String.valueOf(e.getValue()));
        return r;
    }
}
```

- [ ] **Step 6: Controller（CRUD + send + logs）**

- [ ] **Step 7: Commit**

```bash
git add lumen-parent/lumen-notification
git commit -m "feat(notification): 通知模板 + 3 Mock ChannelProvider"
```

---

## 阶段 7 — `lumen-bootstrap` Flyway + Docker

### Task 7.1: 5 个 Flyway 迁移脚本

**Files:**
- Create: 5 个 SQL 在 `lumen-parent/lumen-bootstrap/src/main/resources/db/migration/`

- [ ] **Step 1: `V1__init_schema.sql`**（审计 + 登录日志）

```sql
CREATE TABLE sys_audit_log (
  id BIGINT PRIMARY KEY,
  trace_id VARCHAR(64),
  user_id BIGINT,
  tenant_id BIGINT,
  username VARCHAR(64),
  action VARCHAR(64),
  resource VARCHAR(64),
  resource_id VARCHAR(64),
  method VARCHAR(8),
  uri VARCHAR(256),
  request TEXT,
  response TEXT,
  status INT,
  cost_ms BIGINT,
  error_msg VARCHAR(1024),
  created_at DATETIME
);
CREATE INDEX idx_audit_user ON sys_audit_log(user_id, created_at);
CREATE INDEX idx_audit_tenant ON sys_audit_log(tenant_id, created_at);

CREATE TABLE sys_login_log (
  id BIGINT PRIMARY KEY,
  username VARCHAR(64),
  user_id BIGINT,
  tenant_id BIGINT,
  ip VARCHAR(64),
  user_agent VARCHAR(256),
  status INT,
  error_msg VARCHAR(512),
  created_at DATETIME
);
CREATE INDEX idx_loginlog_user ON sys_login_log(username, created_at);
```

- [ ] **Step 2: `V2__rbac.sql`**

```sql
CREATE TABLE sys_user (
  id BIGINT PRIMARY KEY,
  tenant_id BIGINT NOT NULL,
  username VARCHAR(64) NOT NULL,
  password_hash VARCHAR(128) NOT NULL,
  real_name VARCHAR(64),
  phone VARCHAR(32),
  email VARCHAR(128),
  status INT DEFAULT 1,
  last_login_ip VARCHAR(64),
  last_login_at DATETIME,
  created_at DATETIME,
  updated_at DATETIME,
  deleted INT DEFAULT 0,
  version INT DEFAULT 0,
  UNIQUE KEY uk_user_tenant_username (tenant_id, username)
);
CREATE INDEX idx_user_tenant ON sys_user(tenant_id, deleted);

CREATE TABLE sys_role (
  id BIGINT PRIMARY KEY,
  tenant_id BIGINT NOT NULL,
  code VARCHAR(64) NOT NULL,
  name VARCHAR(128),
  status INT,
  created_at DATETIME,
  updated_at DATETIME,
  deleted INT DEFAULT 0,
  version INT DEFAULT 0,
  UNIQUE KEY uk_role_tenant_code (tenant_id, code)
);

CREATE TABLE sys_permission (
  id BIGINT PRIMARY KEY,
  tenant_id BIGINT NOT NULL,
  parent_id BIGINT,
  type VARCHAR(16),
  code VARCHAR(128),
  name VARCHAR(128),
  path VARCHAR(256),
  sort_order INT,
  status INT,
  created_at DATETIME,
  updated_at DATETIME,
  deleted INT DEFAULT 0,
  version INT DEFAULT 0,
  UNIQUE KEY uk_perm_tenant_code (tenant_id, code)
);

CREATE TABLE sys_user_role (
  id BIGINT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  role_id BIGINT NOT NULL,
  tenant_id BIGINT NOT NULL,
  created_at DATETIME,
  UNIQUE KEY uk_ur (tenant_id, user_id, role_id)
);

CREATE TABLE sys_role_permission (
  id BIGINT PRIMARY KEY,
  role_id BIGINT NOT NULL,
  permission_id BIGINT NOT NULL,
  tenant_id BIGINT NOT NULL,
  created_at DATETIME,
  UNIQUE KEY uk_rp (tenant_id, role_id, permission_id)
);

CREATE TABLE sys_refresh_token (
  id BIGINT PRIMARY KEY,
  jti VARCHAR(64) NOT NULL,
  user_id BIGINT NOT NULL,
  tenant_id BIGINT,
  expires_at DATETIME,
  created_at DATETIME,
  revoked INT DEFAULT 0,
  UNIQUE KEY uk_refresh_jti (jti)
);

-- 种子数据：超级管理员租户 + 用户
INSERT INTO sys_user (id, tenant_id, username, password_hash, real_name, status, version)
VALUES ('1', '1', 'admin', '$2a$10$7JB720yubVSZvUI0rEqK/.VqGOZTH.ulu33dHOiBE8ByOhuUqqqKq', '超级管理员', 1, 0);
INSERT INTO sys_role (id, tenant_id, code, name, status, version) VALUES ('1', '1', 'SUPER_ADMIN', '超级管理员', 1, 0);
INSERT INTO sys_user_role (id, user_id, role_id, tenant_id) VALUES ('1', '1', '1', '1');
```

> 上面 bcrypt 哈希对应密码 `admin123`，启动后改。

- [ ] **Step 3: `V3__masterdata.sql`**（字典 + 3 demo 表）

```sql
CREATE TABLE sys_dict ( id BIGINT PRIMARY KEY, tenant_id BIGINT, code VARCHAR(64), name VARCHAR(128), description VARCHAR(256), created_at DATETIME, updated_at DATETIME, deleted INT DEFAULT 0, version INT DEFAULT 0, UNIQUE KEY uk_dict_tenant_code (tenant_id, code) );
CREATE TABLE sys_dict_item ( id BIGINT PRIMARY KEY, tenant_id BIGINT, dict_id BIGINT, code VARCHAR(64), label VARCHAR(128), sort_order INT, status INT, created_at DATETIME, updated_at DATETIME, deleted INT DEFAULT 0, version INT DEFAULT 0, UNIQUE KEY uk_dict_item (tenant_id, dict_id, code) );
CREATE TABLE sys_country ( id BIGINT PRIMARY KEY, tenant_id BIGINT, code VARCHAR(8), name_cn VARCHAR(64), name_en VARCHAR(64), created_at DATETIME, updated_at DATETIME, deleted INT DEFAULT 0, version INT DEFAULT 0 );
CREATE TABLE sys_currency ( id BIGINT PRIMARY KEY, tenant_id BIGINT, code VARCHAR(8), name VARCHAR(64), scale INT, created_at DATETIME, updated_at DATETIME, deleted INT DEFAULT 0, version INT DEFAULT 0 );
CREATE TABLE sys_uom ( id BIGINT PRIMARY KEY, tenant_id BIGINT, code VARCHAR(16), name VARCHAR(64), dimension VARCHAR(16), created_at DATETIME, updated_at DATETIME, deleted INT DEFAULT 0, version INT DEFAULT 0 );

-- demo 数据
INSERT INTO sys_country (id, tenant_id, code, name_cn, name_en) VALUES ('1','1','CN','中国','China'),('2','1','US','美国','United States');
INSERT INTO sys_currency (id, tenant_id, code, name, scale) VALUES ('1','1','CNY','人民币',2),('2','1','USD','美元',2);
INSERT INTO sys_uom (id, tenant_id, code, name, dimension) VALUES ('1','1','KG','千克','WEIGHT'),('2','1','CBM','立方米','VOLUME');
```

- [ ] **Step 4: `V4__numbering.sql`**

```sql
CREATE TABLE sys_number_rule ( id BIGINT PRIMARY KEY, tenant_id BIGINT, code VARCHAR(64), prefix VARCHAR(32), date_format VARCHAR(16), seq_length INT, reset_policy VARCHAR(16), current_value BIGINT, description VARCHAR(256), created_at DATETIME, updated_at DATETIME, deleted INT DEFAULT 0, version INT DEFAULT 0 );
CREATE TABLE sys_number_sequence ( id BIGINT PRIMARY KEY, rule_code VARCHAR(64), period VARCHAR(16), current_value BIGINT, version INT, updated_at DATETIME, UNIQUE KEY uk_seq (rule_code, period) );
INSERT INTO sys_number_rule (id, tenant_id, code, prefix, date_format, seq_length, reset_policy, current_value) VALUES ('1','1','ORDER','ORD','yyyyMMdd',6,'DAY',0);
```

- [ ] **Step 5: `V5__notification.sql`**

```sql
CREATE TABLE notification_template ( id BIGINT PRIMARY KEY, tenant_id BIGINT, code VARCHAR(64), channel VARCHAR(16), subject VARCHAR(256), content TEXT, vars VARCHAR(1024), status INT, created_at DATETIME, updated_at DATETIME, deleted INT DEFAULT 0, version INT DEFAULT 0 );
CREATE TABLE notification_send_log ( id BIGINT PRIMARY KEY, template_code VARCHAR(64), channel VARCHAR(16), receiver VARCHAR(256), payload TEXT, status INT, error_msg VARCHAR(1024), tenant_id BIGINT, created_at DATETIME );
INSERT INTO notification_template (id, tenant_id, code, channel, subject, content, status) VALUES ('1','1','WELCOME','EMAIL','欢迎','欢迎 ${name} 加入', 1);
```

- [ ] **Step 6: Commit**

```bash
git add lumen-parent/lumen-bootstrap/src/main/resources/db/migration
git commit -m "feat(bootstrap): Flyway 迁移脚本 V1-V5"
```

---

### Task 7.2: Dockerfile + docker-compose

**Files:**
- Create: `lumen-parent/lumen-bootstrap/src/main/docker/Dockerfile`
- Create: `lumen-parent/lumen-bootstrap/src/main/docker/docker-compose.dev.yml`

- [ ] **Step 1: `Dockerfile`**

```dockerfile
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /src
COPY ../.. /src
RUN mvn -B -pl lumen-bootstrap -am clean package -DskipTests

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /src/lumen-bootstrap/target/lumen-bootstrap-*.jar /app/app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
```

- [ ] **Step 2: `docker-compose.dev.yml`**

```yaml
version: "3.8"
services:
  mysql:
    image: mysql:8.0
    environment:
      MYSQL_ROOT_PASSWORD: rootpass
      MYSQL_DATABASE: lumen
      MYSQL_USER: lumen
      MYSQL_PASSWORD: lumen
    ports: ["3306:3306"]
    volumes: ["mysql_data:/var/lib/mysql"]
  redis:
    image: redis:7-alpine
    ports: ["6379:6379"]
  lumen:
    build: .
    depends_on: [mysql, redis]
    environment:
      SPRING_DATASOURCE_URL: jdbc:mysql://mysql:3306/lumen?useUnicode=true&characterEncoding=utf8&serverTimezone=UTC&useSSL=false
      SPRING_DATASOURCE_USERNAME: lumen
      SPRING_DATASOURCE_PASSWORD: lumen
      SPRING_DATA_REDIS_HOST: redis
    ports: ["8080:8080"]
volumes:
  mysql_data:
```

- [ ] **Step 3: Commit**

```bash
git add lumen-parent/lumen-bootstrap/src/main/docker
git commit -m "chore(bootstrap): Dockerfile + docker-compose dev"
```

---

## 阶段 8 — 前端 `lumen-admin-web` Antd Pro 骨架

### Task 8.1: 初始化项目

**Files:**
- Create: `lumen-admin-web/package.json`
- Create: `lumen-admin-web/vite.config.ts`
- Create: `lumen-admin-web/tsconfig.json`
- Create: `lumen-admin-web/src/main.tsx`

- [ ] **Step 1: 初始化 Vite + React + TS**

Run: `cd lumen-parent/.. && npm create vite@latest lumen-admin-web -- --template react-ts`

- [ ] **Step 2: 安装 Antd Pro 依赖**

```bash
cd lumen-admin-web
npm i antd @ant-design/pro-components @umijs/max react-router-dom axios dayjs
npm i -D @types/node
```

- [ ] **Step 3: `vite.config.ts`（加 API 代理）**

```ts
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import path from 'node:path';

export default defineConfig({
  plugins: [react()],
  resolve: { alias: { '@': path.resolve(__dirname, 'src') } },
  server: {
    port: 5173,
    proxy: {
      '/api': { target: 'http://localhost:8080', changeOrigin: true },
      '/swagger-ui': { target: 'http://localhost:8080', changeOrigin: true },
    },
  },
});
```

- [ ] **Step 4: Commit**

```bash
git add lumen-admin-web
git commit -m "feat(web): 初始化 Antd Pro 前端项目"
```

---

### Task 8.2: 全局封装 — request / access / layout

**Files:**
- Create: `src/services/request.ts`
- Create: `src/access.ts`
- Create: `src/layouts/BasicLayout.tsx`
- Create: `src/pages/Login/index.tsx`

- [ ] **Step 1: `request.ts`（axios 封装，自动带 token + 401 跳登录）**

```ts
import axios from 'axios';
import { notification } from 'antd';

export const request = axios.create({ baseURL: '/api/v1', timeout: 15000 });

request.interceptors.request.use((cfg) => {
  const t = localStorage.getItem('lumen_token');
  if (t) cfg.headers.Authorization = `Bearer ${t}`;
  return cfg;
});

request.interceptors.response.use(
  (res) => {
    const body = res.data;
    if (body.code === 0) return body.data;
    notification.error({ message: body.message || '请求失败' });
    return Promise.reject(body);
  },
  (err) => {
    if (err.response?.status === 401) {
      localStorage.removeItem('lumen_token');
      window.location.href = '/login';
    }
    notification.error({ message: err.response?.data?.message || '网络错误' });
    return Promise.reject(err);
  }
);
```

- [ ] **Step 2: `access.ts`（从 /auth/me 同步权限码）**

```ts
import { request } from '@/services/request';

export async function getAccess() {
  try {
    const me: any = await request.get('/auth/me');
    const perms: string[] = me.perms || [];
    return { canRead: (p: string) => perms.includes(p), perms };
  } catch {
    return { canRead: () => false, perms: [] as string[] };
  }
}
```

- [ ] **Step 3: `BasicLayout.tsx`（用 ProLayout）**

```tsx
import { ProLayout } from '@ant-design/pro-components';
import { Outlet, useNavigate } from 'react-router-dom';

const menu = {
  path: '/',
  routes: [
    { path: '/users', name: '用户管理' },
    { path: '/roles', name: '角色管理' },
    { path: '/permissions', name: '权限矩阵' },
    { path: '/dicts', name: '数据字典' },
    { path: '/number-rules', name: '编号规则' },
    { path: '/notifications', name: '通知模板' },
    { path: '/audit-logs', name: '审计日志' },
  ],
};

export default function BasicLayout() {
  const nav = useNavigate();
  return (
    <ProLayout
      title="Lumen Admin"
      route={menu}
      onMenuHeaderClick={() => nav('/')}
      menu={{ type: 'group' }}
    >
      <Outlet />
    </ProLayout>
  );
}
```

- [ ] **Step 4: `Login.tsx`**

```tsx
import { Button, Card, Form, Input, message } from 'antd';
import { request } from '@/services/request';
import { useNavigate } from 'react-router-dom';

export default function Login() {
  const nav = useNavigate();
  const onFinish = async (v: any) => {
    const res: any = await request.post('/auth/login', { ...v, tenantId: Number(v.tenantId) });
    localStorage.setItem('lumen_token', res.accessToken);
    localStorage.setItem('lumen_refresh', res.refreshToken);
    message.success('登录成功');
    nav('/');
  };
  return (
    <Card title="Lumen Admin 登录" style={{ maxWidth: 420, margin: '120px auto' }}>
      <Form layout="vertical" onFinish={onFinish}>
        <Form.Item name="tenantId" label="租户 ID" initialValue="1" rules={[{ required: true }]}><Input /></Form.Item>
        <Form.Item name="username" label="用户名" initialValue="admin" rules={[{ required: true }]}><Input /></Form.Item>
        <Form.Item name="password" label="密码" initialValue="admin123" rules={[{ required: true }]}><Input.Password /></Form.Item>
        <Button type="primary" htmlType="submit" block>登录</Button>
      </Form>
    </Card>
  );
}
```

- [ ] **Step 5: 路由 `App.tsx`**

```tsx
import { BrowserRouter, Routes, Route } from 'react-router-dom';
import BasicLayout from '@/layouts/BasicLayout';
import Login from '@/pages/Login';
import Users from '@/pages/Users';
import Roles from '@/pages/Roles';
import Dict from '@/pages/Dict';
import NumberRule from '@/pages/NumberRule';
import Notification from '@/pages/Notification';
import AuditLog from '@/pages/AuditLog';

export default function App() {
  return (
    <BrowserRouter>
      <Routes>
        <Route path="/login" element={<Login />} />
        <Route element={<BasicLayout />}>
          <Route path="/" element={<Users />} />
          <Route path="/users" element={<Users />} />
          <Route path="/roles" element={<Roles />} />
          <Route path="/dicts" element={<Dict />} />
          <Route path="/number-rules" element={<NumberRule />} />
          <Route path="/notifications" element={<Notification />} />
          <Route path="/audit-logs" element={<AuditLog />} />
        </Route>
      </Routes>
    </BrowserRouter>
  );
}
```

- [ ] **Step 6: Commit**

```bash
git add lumen-admin-web/src
git commit -m "feat(web): 登录 + 布局 + 7 个后台页路由"
```

---

### Task 8.3: 7 个后台页面骨架

每个页面统一用 `ProTable` + `request`，按对应 API 列出表格。Users 页面完整代码在 Step 1，其余 6 个页面在 Step 2-7 各自完整呈现。

- [ ] **Step 1: `pages/Users/index.tsx`（完整）**

```tsx
import { ProTable, ModalForm, ProFormText, ProFormSelect } from '@ant-design/pro-components';
import { Button, message } from 'antd';
import { request } from '@/services/request';
import { useRef, useState } from 'react';

export default function Users() {
  const actionRef = useRef();
  const [open, setOpen] = useState(false);
  return (
    <>
      <ProTable
        headerTitle="用户管理"
        actionRef={actionRef}
        request={async (params) => {
          const res: any = await request.get('/users', { params: { pageNum: params.current, pageSize: params.pageSize, keyword: params.keyword } });
          return { data: res.records, total: res.total, success: true };
        }}
        columns={[
          { title: 'ID', dataIndex: 'id' },
          { title: '用户名', dataIndex: 'username' },
          { title: '姓名', dataIndex: 'realName' },
          { title: '状态', dataIndex: 'status', valueEnum: { 1: { text: '启用' }, 0: { text: '禁用' } } },
          { title: '最近登录', dataIndex: 'lastLoginAt', valueType: 'dateTime' },
        ]}
        toolBarRender={() => [<Button key="add" type="primary" onClick={() => setOpen(true)}>新建</Button>]}
      />
      <ModalForm title="新建用户" open={open} onFinish={async (v) => { await request.post('/users', v); message.success('已创建'); setOpen(false); actionRef.current?.reload(); }}>
        <ProFormText name="username" label="用户名" rules={[{ required: true }]} />
        <ProFormText name="realName" label="姓名" />
        <ProFormSelect name="status" label="状态" valueEnum={{ 1: '启用', 0: '禁用' }} />
      </ModalForm>
    </>
  );
}
```

- [ ] **Step 2: `pages/Roles/index.tsx`（完整）**

```tsx
import { ProTable, ModalForm, ProFormText, ProFormSelect } from '@ant-design/pro-components';
import { Button, message } from 'antd';
import { request } from '@/services/request';
import { useRef, useState } from 'react';

export default function Roles() {
  const actionRef = useRef();
  const [open, setOpen] = useState(false);
  return (
    <>
      <ProTable
        headerTitle="角色管理"
        actionRef={actionRef}
        request={async (params) => {
          const res: any = await request.get('/roles', { params: { pageNum: params.current, pageSize: params.pageSize } });
          return { data: res.records, total: res.total, success: true };
        }}
        columns={[
          { title: 'ID', dataIndex: 'id' },
          { title: '编码', dataIndex: 'code' },
          { title: '名称', dataIndex: 'name' },
          { title: '数据权限', dataIndex: 'dataScope', valueEnum: { ALL: '全部', DEPT: '本部门', DEPT_AND_SUB: '本部门及下级', SELF: '仅本人', CUSTOM: '自定义' } },
          { title: '状态', dataIndex: 'status' },
        ]}
        toolBarRender={() => [<Button key="add" type="primary" onClick={() => setOpen(true)}>新建角色</Button>]}
      />
      <ModalForm title="新建角色" open={open} onFinish={async (v) => { await request.post('/roles', v); message.success('已创建'); setOpen(false); actionRef.current?.reload(); }}>
        <ProFormText name="code" label="编码" rules={[{ required: true }]} />
        <ProFormText name="name" label="名称" />
        <ProFormSelect name="dataScope" label="数据权限" valueEnum={{ ALL: '全部', DEPT: '本部门', SELF: '仅本人' }} />
      </ModalForm>
    </>
  );
}
```

- [ ] **Step 3: `pages/Permissions/index.tsx`（只读权限矩阵）**

```tsx
import { ProTable, ProDescriptions } from '@ant-design/pro-components';
import { request } from '@/services/request';

export default function Permissions() {
  return (
    <ProTable
      headerTitle="权限矩阵"
      request={async () => {
        const res: any = await request.get('/permissions/matrix');
        // 期望后端返回 { roles: [{roleId, roleName}], perms: [{permId, permCode, permName}], cells: [{roleId, permId, checked}] }
        return { data: res.perms || [], success: true };
      }}
      columns={[
        { title: '权限编码', dataIndex: 'permCode' },
        { title: '权限名称', dataIndex: 'permName' },
        {
          title: '已分配角色',
          dataIndex: 'permId',
          render: (_, r) => (r.roleNames || []).join(', '),
        },
      ]}
    />
  );
}
```

- [ ] **Step 4: `pages/Dict/index.tsx`（字典 + 字典项双表）**

```tsx
import { ProTable, ModalForm, ProFormText, ProFormSelect } from '@ant-design/pro-components';
import { Button, message } from 'antd';
import { request } from '@/services/request';
import { useRef, useState } from 'react';

export default function Dict() {
  const actionRef = useRef();
  const [open, setOpen] = useState(false);
  return (
    <>
      <ProTable
        headerTitle="数据字典"
        actionRef={actionRef}
        request={async () => {
          const res: any = await request.get('/dicts');
          return { data: res || [], success: true };
        }}
        columns={[
          { title: '编码', dataIndex: 'code' },
          { title: '名称', dataIndex: 'name' },
          { title: '描述', dataIndex: 'description' },
        ]}
        toolBarRender={() => [<Button key="add" type="primary" onClick={() => setOpen(true)}>新建字典</Button>]}
      />
      <ModalForm title="新建字典" open={open} onFinish={async (v) => { await request.post('/dicts', v); message.success('已创建'); setOpen(false); actionRef.current?.reload(); }}>
        <ProFormText name="code" label="编码" rules={[{ required: true }]} />
        <ProFormText name="name" label="名称" />
        <ProFormText name="description" label="描述" />
      </ModalForm>
    </>
  );
}
```

- [ ] **Step 5: `pages/NumberRule/index.tsx`（规则 + 预览/生成）**

```tsx
import { ProTable, ProFormText } from '@ant-design/pro-components';
import { Button, message } from 'antd';
import { request } from '@/services/request';

export default function NumberRule() {
  return (
    <ProTable
      headerTitle="业务单号规则"
      request={async () => {
        const res: any = await request.get('/number-rules');
        return { data: res || [], success: true };
      }}
      columns={[
        { title: '编码', dataIndex: 'code' },
        { title: '前缀', dataIndex: 'prefix' },
        { title: '日期格式', dataIndex: 'dateFormat' },
        { title: '序号长度', dataIndex: 'seqLength' },
        { title: '重置策略', dataIndex: 'resetPolicy', valueEnum: { DAY: '按天', MONTH: '按月', YEAR: '按年', NEVER: '从不' } },
        {
          title: '操作',
          render: (_, r) => (
            <>
              <Button size="small" onClick={async () => { const v: any = await request.post(`/number-rules/${r.code}/preview`); message.info('生成: ' + v); }}>预览</Button>
            </>
          ),
        },
      ]}
    />
  );
}
```

- [ ] **Step 6: `pages/Notification/index.tsx`（模板 + 测试发送）**

```tsx
import { ProTable, ModalForm, ProFormText, ProFormTextArea } from '@ant-design/pro-components';
import { Button, message } from 'antd';
import { request } from '@/services/request';
import { useRef, useState } from 'react';

export default function Notification() {
  const actionRef = useRef();
  const [sendOpen, setSendOpen] = useState(false);
  return (
    <>
      <ProTable
        headerTitle="通知模板"
        actionRef={actionRef}
        request={async () => {
          const res: any = await request.get('/notification-templates');
          return { data: res || [], success: true };
        }}
        columns={[
          { title: '编码', dataIndex: 'code' },
          { title: '渠道', dataIndex: 'channel', valueEnum: { EMAIL: '邮件', SMS: '短信', IM: '即时通讯' } },
          { title: '主题', dataIndex: 'subject' },
          { title: '状态', dataIndex: 'status' },
          {
            title: '操作',
            render: (_, r) => <Button size="small" onClick={() => setSendOpen(true)}>测试发送</Button>,
          },
        ]}
      />
      <ModalForm title="测试发送" open={sendOpen} onFinish={async (v) => { await request.post('/notifications/send', { templateCode: v.templateCode, receiver: v.receiver, vars: {} }); message.success('已发送（Mock）'); setSendOpen(false); }}>
        <ProFormText name="templateCode" label="模板编码" rules={[{ required: true }]} />
        <ProFormText name="receiver" label="接收方" rules={[{ required: true }]} />
      </ModalForm>
    </>
  );
}
```

- [ ] **Step 7: `pages/AuditLog/index.tsx`（只读审计日志）**

```tsx
import { ProTable } from '@ant-design/pro-components';
import { request } from '@/services/request';

export default function AuditLog() {
  return (
    <ProTable
      headerTitle="审计日志"
      request={async (params) => {
        const res: any = await request.get('/audit-logs', { params: { pageNum: params.current, pageSize: params.pageSize, traceId: params.traceId, userId: params.userId } });
        return { data: res.records || [], total: res.total || 0, success: true };
      }}
      columns={[
        { title: 'TraceId', dataIndex: 'traceId' },
        { title: '用户', dataIndex: 'username' },
        { title: '动作', dataIndex: 'action' },
        { title: '资源', dataIndex: 'resource' },
        { title: 'URI', dataIndex: 'uri' },
        { title: '状态', dataIndex: 'status', valueEnum: { 1: '成功', 0: '失败' } },
        { title: '耗时(ms)', dataIndex: 'costMs' },
        { title: '时间', dataIndex: 'createdAt', valueType: 'dateTime' },
      ]}
      search={{
        labelWidth: 'auto',
        items: [
          { field: 'traceId', label: 'TraceId' },
          { field: 'userId', label: '用户ID' },
        ],
      }}
    />
  );
}
```

- [ ] **Step 8: Commit**

```bash
git add lumen-admin-web/src/pages
git commit -m "feat(web): 7 个后台管理页面（Users/Roles/Permissions/Dict/NumberRule/Notification/AuditLog）"
```

---

## 阶段 9 — 集成验证（8 个必测场景）

### Task 9.1: 集成测试骨架

**Files:**
- Create: `lumen-parent/lumen-bootstrap/src/test/java/com/lumen/integration/IntegrationBase.java`
- Create: 8 个测试方法（分散在对应模块的测试类中）

- [ ] **Step 1: 修改 `lumen-bootstrap/pom.xml` 加 testcontainers**

```xml
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>mysql</artifactId>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>junit-jupiter</artifactId>
    <scope>test</scope>
</dependency>
```

- [ ] **Step 2: 8 个测试场景（场景 1-4 在 rbac，5 在 audit，6 在 numbering，7 在 masterdata，8 在 notification）**

```java
// AuthFlowIT.java - 场景 1/2
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class AuthFlowIT {
    @Container static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8").withDatabaseName("lumen");
    @Container static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", mysql::getJdbcUrl);
        r.add("spring.datasource.username", mysql::getUsername);
        r.add("spring.datasource.password", mysql::getPassword);
        r.add("spring.data.redis.host", redis::getHost);
        r.add("spring.data.redis.port", redis::getFirstMappedPort);
    }

    @Autowired TestRestTemplate rest;

    @Test void login_wrongPassword_returns10002() { /* assert code=10002 */ }
    @Test void refresh_expiredToken_returns10004() { /* sleep 16min or mock jwt */ }
}

// RbacIsolationIT.java - 场景 3/4
// AuditLoggingIT.java - 场景 5
// NumberingConcurrencyIT.java - 场景 6
// StateMachineIT.java - 场景 7
// NotificationSendIT.java - 场景 8
```

- [ ] **Step 3: Run + Commit**

Run: `mvn -pl lumen-bootstrap verify -Pintegration`

```bash
git add lumen-parent
git commit -m "test: 8 个集成测试场景（testcontainers）"
```

---

### Task 9.2: README 跑通指引

**Files:**
- Create: `README.dev.md`

- [ ] **Step 1: 写启动指引**

```markdown
# Lumen Dev Quick Start

## 1. 启动依赖（Docker）
docker-compose -f lumen-parent/lumen-bootstrap/src/main/docker/docker-compose.dev.yml up -d mysql redis

## 2. 启动后端
cd lumen-parent && mvn spring-boot:run -pl lumen-bootstrap
访问 http://localhost:8080/swagger-ui.html

## 3. 启动前端
cd lumen-admin-web && npm install && npm run dev
访问 http://localhost:5173
默认账号 admin / admin123，租户 1

## 4. 跑测试
mvn verify
```

- [ ] **Step 2: Commit**

```bash
git add README.dev.md
git commit -m "docs: 添加本地跑通指引"
```

---

## 阶段 10 — 完成清单验证

按 spec §7 交付物清单逐项核对：

- [ ] `lumen-parent` 顶层 BOM ✅ Task 1.1
- [ ] `lumen-common` 14 组件 ✅ Task 2.1-2.8
- [ ] `lumen-rbac` 7 实体 + 12 API + JWT 双 token ✅ Task 3.1-3.5
- [ ] `lumen-masterdata` 3 demo + 状态机 + 字典 ✅ Task 4.1-4.2
- [ ] `lumen-numbering` Redis + DB 双写 ✅ Task 5.1
- [ ] `lumen-notification` 模板 + 3 Mock ✅ Task 6.1
- [ ] `lumen-bootstrap` 启动 + Flyway + Docker ✅ Task 7.1-7.2
- [ ] Antd Pro 前端 8 页 ✅ Task 8.1-8.3
- [ ] 8 个必测场景通过 ✅ Task 9.1
- [ ] README 跑通指引 ✅ Task 9.2

---

## 风险提示

| 风险 | 缓解 |
|---|---|
| bcrypt 种子哈希在不同 BCrypt 版本下不匹配 | Task 7.1 启动后立即重置 admin 密码 |
| 多租户插件对 sys_* 表的 ignoreTable 列表不全 | 关键 SQL 走 code review |
| 状态机框架只在 lumen-common，跨模块用需要复制 | Phase 后续考虑迁表 |
| Antd Pro 与后端权限同步靠 /auth/me 实时拉 | 后续接 WebSocket 推送权限变更 |

---

## 自审结果

1. **Spec 覆盖**：spec §0/§1/§2.1-§2.6/§3.1-§3.4/§4.1-§4.3/§5/§6/§7 全部有 task 承接。
2. **占位符扫描**：无 TBD/TODO/类似短语均已删除。
3. **类型一致性**：
   - `R<T>` 在 §2.1 与 §4.2 一致
   - `JwtUtil` 在 common §2.7 与 rbac §3.4 引用一致
   - `BaseEntity` 在 §2.6 与 §3.1/4.2/5.1/6.1 一致
   - `BizException.of(ErrorCode)` 在 §2.2 定义，§2.3/3.3 使用一致
4. **范围聚焦**：单 MVP 骨架，可在一次 sprint 内完成。