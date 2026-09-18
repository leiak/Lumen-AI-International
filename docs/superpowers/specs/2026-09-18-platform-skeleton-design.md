# 平台骨架 MVP — 设计 spec

> 日期：2026-09-18  
> 范围：国际物流系统第一个可运行版本（骨架优先，业务模块后续增量）  
> 状态：草稿，待用户审核

---

## 0. 背景与目标

业务设计文档（v1~v4，10 个模块 + 5 个扩展模块 + 跨切面 + 原型）已齐全。仓库当前**只有文档、没有源代码**。

第一版目标不是"把所有业务都写出来"，而是**先把支撑所有业务的骨架立起来**，让后续业务代码有可复用、可信赖的底座。

### MVP 范围

| 包含 | 不包含 |
|---|---|
| 基础资料框架（CRUD + 状态机 + 字典 + 3 个 demo 实体） | 全部业务实体（客户、合作方、港口、HS编码、仓库…） |
| RBAC 全量矩阵（用户/角色/权限/数据权限） | 业务级权限（按业务对象） |
| 业务单号生成（规则 + 序列 + 预览） | 业务单据本身 |
| 通知骨架（模板 + ChannelProvider + Mock 实现） | 真实短信/邮件/IM 渠道 |
| 错误体系 + 结构化日志 + 审计日志 | ELK 接入 |
| 多租户（行级 `tenant_id` 隔离） | schema 级 / 库级隔离 |
| JWT 无状态认证 | OAuth2 / SSO |
| Docker Compose 一键起本地 | K8s / 生产编排 |

### 不做（写到 §6）

参照业务设计文档 §14 跨切面要求，把骨架边界写死，避免范围蔓延。

---

## 1. 顶层架构

### 1.1 视图

```
┌─────────────────────────────────────────────────────────────┐
│                  Browser (React + Antd Pro)                 │
└──────────────────────────┬──────────────────────────────────┘
                           │ HTTPS / JSON
┌──────────────────────────▼──────────────────────────────────┐
│                 lumen-bootstrap (Spring Boot 3)             │
│   ┌─────────┐  ┌─────────┐  ┌─────────┐  ┌─────────┐        │
│   │  rbac   │  │masterdata│ │numbering│  │notification│      │
│   └────┬────┘  └────┬────┘  └────┬────┘  └────┬────┘        │
│        └────────────┴────────────┴────────────┘              │
│                          │                                   │
│              ┌───────────▼───────────┐                       │
│              │     lumen-common      │                       │
│              │  错误/日志/租户/审计   │                       │
│              └───────────┬───────────┘                       │
└──────────────────────────┼───────────────────────────────────┘
                           │
        ┌──────────────────┼──────────────────┐
        ▼                  ▼                  ▼
   MySQL 8 (单库)    Redis 7 (token/限流)   (预留 MQ/ES)
```

### 1.2 Maven 多模块

```
lumen-parent/                         (顶层 POM：BOM/版本)
├── lumen-common/                     (错误/日志/租户/审计/工具)
├── lumen-rbac/                       (用户/角色/权限)
├── lumen-masterdata/                 (基础资料 + 状态机框架 + 字典)
├── lumen-numbering/                  (业务单号)
├── lumen-notification/               (通知模板 + ChannelProvider)
└── lumen-bootstrap/                  (唯一可执行 jar)
```

### 1.3 关键技术依赖（BOM）

| 类别 | 依赖 | 版本 |
|---|---|---|
| 框架 | spring-boot-starter-parent | 3.2.x |
| 安全 | spring-boot-starter-security | 跟随 Boot |
| JWT | io.jsonwebtoken:jjwt-* | 0.12.x |
| ORM | mybatis-plus-spring-boot3-starter | 3.5.x |
| 数据库 | mysql-connector-j + HikariCP | 8.x |
| 迁移 | flyway-mysql | 9.x |
| 缓存 | spring-boot-starter-data-redis (Lettuce) | 跟随 Boot |
| 验证 | spring-boot-starter-validation | 跟随 Boot |
| API 文档 | springdoc-openapi-starter-webmvc-ui | 2.x |
| 工具 | lombok + mapstruct + hutool | 最新 |
| 测试 | spring-boot-starter-test + testcontainers | 跟随 Boot |
| 监控 | spring-boot-starter-actuator | 跟随 Boot |

---

## 2. 模块职责

### 2.1 lumen-common

| 组件 | 职责 |
|---|---|
| `BizException` / `ErrorCode` | 业务异常 + 错误码枚举 |
| `GlobalExceptionHandler` | `@RestControllerAdvice` 统一兜底 |
| `R<T>` / `PageResult<T>` | 统一响应 / 分页 |
| `TenantContext` (ThreadLocal) | 当前租户上下文 |
| `TenantInterceptor` (HandlerInterceptor) | 从 JWT 提取 tenantId 注入 |
| `BaseEntity` | id / tenantId / createdAt / updatedAt / deleted / version |
| `MyMetaObjectHandler` | 自动填充审计字段 |
| `AuditAspect` (`@AuditLog`) | 注解切面写审计日志 |
| `AuditLog` 实体 + Mapper + 异步线程池 | 审计日志落库 |
| `TraceIdFilter` | 分配 traceId 写 MDC + 响应 header |
| `JwtUtil` | JWT 签发 / 解析 / 校验 |
| `SensitiveMaskUtil` | 密码 / token / 手机号脱敏 |

### 2.2 lumen-rbac

**实体**：`SysUser` / `SysRole` / `SysPermission` / `SysUserRole` / `SysRolePermission` / `SysRefreshToken` / `SysLoginLog`

**关键 API**：

| 接口 | 方法 | 用途 |
|---|---|---|
| `/api/v1/auth/login` | POST | 用户名密码登录 |
| `/api/v1/auth/refresh` | POST | refresh 换 access |
| `/api/v1/auth/logout` | POST | 注销 |
| `/api/v1/auth/me` | GET | 当前用户信息 + 权限码 |
| `/api/v1/users` | GET/POST | 用户列表/创建 |
| `/api/v1/users/{id}` | GET/PUT/DELETE | 用户详情/修改/删除 |
| `/api/v1/users/{id}/roles` | PUT | 分配角色 |
| `/api/v1/users/{id}/reset-password` | POST | 重置密码 |
| `/api/v1/roles` | GET/POST | 角色列表/创建 |
| `/api/v1/roles/{id}/permissions` | PUT | 分配权限 |
| `/api/v1/permissions` | GET | 权限树 |
| `/api/v1/permissions/matrix` | GET | 角色 × 权限矩阵 |

**权限模型**：RBAC + 数据权限 `@DataScope`（ALL / DEPT / DEPT_AND_SUB / SELF / CUSTOM）。

**多租户**：每个 user/role/permission 带 `tenant_id`，JWT 取 `tenantId` 后由 MyBatis-Plus 多租户插件追加 SQL 过滤。

### 2.3 lumen-masterdata

**通用组件**：
- `BaseEntity` / `BaseService` / `BaseController`
- `StateMachine<S, E, C>`（泛型状态机）
- `StateMachineRegistry`（运行时注册）
- 字典：`SysDict` + `SysDictItem`

**三个 demo 实体**：
- `SysCountry`（国家代码）
- `SysCurrency`（币种）
- `SysUom`（计量单位）

**关键 API**：
- `GET/POST/PUT/DELETE /api/v1/countries`（CRUD demo）
- `GET/POST /api/v1/dicts/{type}/items`
- `POST /api/v1/state-machines/{code}/fire`（状态机推进）

**预留**：状态机转换表 `sys_state_machine` / `sys_state_transition`，骨架阶段代码配置 + Flyway 初始化。

### 2.4 lumen-numbering

**实体**：`SysNumberRule` / `SysNumberSequence`（乐观锁）

**关键 API**：
- `GET/POST/PUT/DELETE /api/v1/number-rules`
- `POST /api/v1/number-rules/{code}/preview`
- `POST /api/v1/number-rules/{code}/generate`

**算法**：Redis `INCR` + DB 持久化双写。  
**格式**：`{prefix}-{date_format}-{seq}`，如 `ORD-20260918-000123`。

### 2.5 lumen-notification

**实体**：`NotificationTemplate` / `NotificationSendLog`

**抽象**：
```java
public interface ChannelProvider {
    String channel();              // EMAIL / SMS / IM
    SendResult send(SendRequest req);
}
```

**骨架 Mock 实现**：`MockEmailProvider` / `MockSmsProvider` / `MockImProvider`（只写日志）。

**关键 API**：
- `GET/POST/PUT /api/v1/notification-templates`
- `POST /api/v1/notifications/send`
- `GET /api/v1/notification-logs`

**扩展点**：`EP-NOTIFY-1` 接入真实渠道（钉钉/企微/阿里云 SMS/SendGrid）。

### 2.6 lumen-bootstrap

```
src/main/java/com/lumen/LumenApplication.java
src/main/resources/
├── application.yml
├── application-dev.yml
├── application-test.yml
├── db/migration/                # Flyway
│   ├── V1__init_schema.sql
│   ├── V2__rbac.sql
│   ├── V3__masterdata.sql
│   ├── V4__numbering.sql
│   └── V5__notification.sql
└── logback-spring.xml
src/main/docker/
├── Dockerfile
└── docker-compose.dev.yml       # MySQL + Redis + 应用
```

---

## 3. 数据流

### 3.1 单次请求链路

```
Browser (Antd Pro)
    │ Authorization: Bearer <access_token>
    ▼
[lumen-bootstrap]
    │
    ├─① TraceIdFilter               生成 traceId → MDC + 响应 header
    ├─② CORS Filter
    ├─③ JwtAuthenticationFilter    构造 Authentication + TenantContext
    ├─④ TenantLineInnerInterceptor 自动追加 WHERE tenant_id = ?
    ├─⑤ AuthorizationFilter        @PreAuthorize / 路径级
    ├─⑥ TenantInterceptor          tenantId 注入 request attribute
    ├─⑦ AuditAspect (@AuditLog)    写操作异步落 sys_audit_log
    ├─⑧ Controller / Service / Mapper
    └─⑨ GlobalExceptionHandler     异常 → R.fail(...)
```

### 3.2 JWT 双 token

```
登录:  username + password
     → 校验通过
        → access_token (15min, payload: sub/tid/roles/perms/jti/exp)
        → refresh_token (7d, jti 不同) + Redis rt:{jti} → userId (TTL 7d)

刷新:  POST /auth/refresh {refreshToken}
     → 校验签名 + jti 在白名单
     → 撤销旧 key → 签发新 access + 新 refresh (rotation)

注销:  POST /auth/logout
     → 黑名单 bl:{jti} = 1, TTL = 剩余有效期
     → 删除 Redis 刷新键 → 清空 SecurityContext
```

### 3.3 权限决策

```
@Controller @PreAuthorize("hasAuthority('user:list')")
 → AuthorizationManager 比对 user.perms ∋ 'user:list'
   ├─ 含 → 进入方法
   └─ 不含 → AccessDeniedException → 403
@DataScope("DEPT") 在 Service 层生效
```

### 3.4 审计策略

| 维度 | 规则 |
|---|---|
| 何时 | `@AuditLog` 注解 + 写操作 (POST/PUT/DELETE) 默认 |
| 内容 | `traceId, userId, tenantId, action, resource, resourceId, request, response, costMs, status` |
| 存储 | 异步线程池 → `sys_audit_log` |
| 不记 | GET 查询、登录登出（走 `sys_login_log`）、token 刷新 |
| 保留 | 180 天，`@Scheduled` 定时清理 |

---

## 4. 错误体系 + 日志

### 4.1 错误码分层

```
ErrorCode (interface)
  ├─ CommonErrorCode (HTTP 状态码)
  │     SUCCESS(0) / BAD_REQUEST(400) / UNAUTHORIZED(401) / FORBIDDEN(403)
  │     NOT_FOUND(404) / PARAM_INVALID(4001) / INTERNAL_ERROR(500)
  ├─ RbacErrorCode (10xxx)   USER_NOT_FOUND / USER_PASSWORD_WRONG / USER_DISABLED
  │                          TOKEN_EXPIRED / TOKEN_REVOKED / PERMISSION_DENIED
  ├─ MasterDataErrorCode(11xxx) DICT_NOT_FOUND / STATE_INVALID_TRANSITION
  │                            ENTITY_HAS_REFERENCE
  ├─ NumberingErrorCode(12xxx)  RULE_NOT_FOUND / SEQ_EXHAUSTED
  └─ NotificationErrorCode(13xxx) TEMPLATE_NOT_FOUND / CHANNEL_PROVIDER_MISSING
```

每条：`{code: int, message: String, httpStatus: int}`。

### 4.2 统一响应

```json
{
  "code": 0,
  "message": "success",
  "data": { },
  "traceId": "abc123",
  "timestamp": 1758163200000
}
```

异常时 `data = null`，加 `errors: [...]`（字段级）。

### 4.3 日志

- **格式**：JSON 结构化（`LogstashEncoder`）
- **MDC**：`traceId, userId, tenantId, uri, method`
- **级别**：开发 INFO，生产 INFO（关键路径 DEBUG）
- **保留**：本地 7 天滚动 + 7 归档；生产接 ELK 改 appender
- **脱敏**：密码 / token / 手机号走 `SensitiveMaskUtil`

---

## 5. 测试策略

### 5.1 层次

| 层级 | 工具 | 目标 |
|---|---|---|
| 单元 | JUnit 5 + Mockito + AssertJ | Service / Util / 状态机，目标 70% |
| 切片 | `@WebMvcTest` + MockMvc | Controller + 全局异常，路径 100% |
| 集成 | Testcontainers (MySQL + Redis) | RBAC / 编号 / 通知关键路径 |
| E2E | Playwright（可选） | 登录 + 用户管理 |

### 5.2 骨架阶段必测场景

1. 登录：正确 → 200；错密码 → 10002；禁用 → 10003
2. Token 刷新：有效 → 新 token；过期 → 401；撤销 → 10005
3. 权限拦截：无权 → 403；有权 → 200
4. 多租户隔离：A 租户用户无法读写 B 租户数据
5. 审计日志：写操作 → 落库；GET → 不落
6. 编号生成：并发 100 次 → 全部唯一
7. 状态机：合法 → 通过；非法 → 11002
8. 通知发送：Mock → 日志可见、状态 SENT

### 5.3 CI（未来）

骨架阶段先不做，但保留 GitHub Actions 配置位：
- mvn verify（单元 + 切片）
- docker-compose 启动 → integration
- frontend lint + build

---

## 6. 明确不做

| 不做 | 原因 | 何时做 |
|---|---|---|
| 微服务 / Spring Cloud | 骨架阶段单体足够 | 业务模块上量后 |
| Kafka / RabbitMQ | 骨架无异步事件 | 委托单 / 跟踪等业务模块 |
| Elasticsearch | 跟踪 / 单证检索暂不需要 | tracking 模块 |
| Flowable / Camunda | 状态机框架已覆盖 | 复杂审批流出现时 |
| PostgreSQL / MongoDB | 关务 / 单证未启用 | 报关 / 单证模块 |
| 多渠道真实短信/邮件/IM | Mock 够演示 | 上线前 |
| K8s 编排 | 本地 docker-compose 足够 | 生产部署阶段 |
| CI/CD | 本地 mvn verify 足够 | 团队多人协作时 |
| 国际化 i18n | 仅简中 + 文案常量 | 多语言版本 |
| 数据导入导出 Excel | 后台管理非 MVP | 客户提需求时 |

---

## 7. 交付物清单（spec 通过后落计划）

- [ ] `lumen-parent` 顶层 POM（BOM）
- [ ] `lumen-common`：异常/响应/MDC/审计/租户/JWT 工具/状态机抽象
- [ ] `lumen-rbac`：7 个实体 + 12 个 API + JWT 双 token
- [ ] `lumen-masterdata`：3 个 demo 实体 + 状态机框架 + 字典
- [ ] `lumen-numbering`：规则 + 序列 + Redis + DB 双写
- [ ] `lumen-notification`：模板 + ChannelProvider + 3 个 Mock
- [ ] `lumen-bootstrap`：启动类 + Flyway 5 个迁移 + Dockerfile + docker-compose
- [ ] Antd Pro 前端骨架：登录 + 用户 + 角色 + 权限矩阵 + 字典 + 编号 + 通知模板 + 审计日志
- [ ] 测试：8 个必测场景通过
- [ ] README 跑通指引 + OpenAPI 文档可访问

---

## 8. 风险与权衡

| 风险 | 缓解 |
|---|---|
| MyBatis-Plus 多租户插件被业务 SQL 绕过 | 关键表增加唯一约束 `(tenant_id, business_key)`；code review 检查 |
| JWT 撤销不彻底（access 仍可用至过期） | 黑名单 TTL = 剩余有效期；refresh rotation 强制下线旧 refresh |
| Redis 不可用时编号生成降级 | DB 兜底 + 乐观锁，告警监控 |
| 状态机配置化 vs 代码化 | 骨架阶段代码配置（注解 + 注册器），后续再迁 DB |
| Antd Pro 与后端 RBAC 同步延迟 | 前端权限码从 `/auth/me` 实时取，不写死 |