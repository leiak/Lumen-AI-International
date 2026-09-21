# Lumen Dev Quick Start

> **For new contributors**: this guide takes you from a clean checkout to a running backend + frontend in ~10 minutes.
>
> See [`README.md`](./README.md) for the project overview / business docs. This file is the developer-only runbook.

---

## 0. Prerequisites

| Tool | Version | Notes |
|------|---------|-------|
| JDK | 17+ | `java -version` |
| Maven | 3.8+ | `mvn -version` |
| Node.js | 18+ | `node --version` (frontend) |
| npm | 9+ | `npm --version` (frontend) |
| Docker Desktop | 4.x+ | for MySQL + Redis + integration tests |
| Git | 2.30+ | for cloning |

Windows: use Git Bash or WSL2 for shell commands. macOS / Linux: native bash works.

---

## 1. Start infrastructure (MySQL + Redis)

From the repo root:

```bash
docker compose -f lumen-parent/lumen-bootstrap/src/main/docker/docker-compose.dev.yml up -d mysql redis
```

This pulls `mysql:8.0` and `redis:7-alpine`, starts them on ports `3306` and `6379` respectively, and waits for health checks to pass. The first pull takes ~2 minutes.

Verify:

```bash
docker compose -f lumen-parent/lumen-bootstrap/src/main/docker/docker-compose.dev.yml ps
# Both mysql and redis should be "healthy"
```

The same compose file also defines a `lumen` service that builds the backend jar from the `lumen-parent/` build context. To run **everything** (MySQL + Redis + backend) in containers, omit `mysql redis` and use:

```bash
docker compose -f lumen-parent/lumen-bootstrap/src/main/docker/docker-compose.dev.yml up -d
```

For the rest of this guide we assume the **hybrid** setup (DB in Docker, backend on host) because that's the fastest iteration loop for a contributor.

---

## 2. Start the backend

From the repo root:

```bash
cd lumen-parent
mvn spring-boot:run -pl lumen-bootstrap -am
```

First build: ~3 minutes (downloads dependencies, compiles 6 modules). Subsequent runs: ~30 seconds.

When you see `Started BootstrapApplication in X.XXX seconds`, the backend is ready.

- API base path: `http://localhost:8080/api/v1/...`
- Swagger UI: `http://localhost:8080/swagger-ui.html`
- Health: `http://localhost:8080/actuator/health`

Flyway will auto-create the schema (13 migrations under `lumen-parent/lumen-bootstrap/src/main/resources/db/migration/`):

| Version | Purpose |
|---------|---------|
| `V1__init_schema.sql` | Audit + login base tables |
| `V2__rbac.sql` | RBAC (users, roles, permissions) + seed data |
| `V3__masterdata.sql` | Dict, Country, Currency, UoM + demo rows |
| `V4__numbering.sql` | Number rule + sequence counter |
| `V5__notification.sql` | Notification templates + send log |
| `V6__fix_permissions.sql` | 补齐 20 个 CRUD 权限 (dict/currency/uom/notification_template) |
| `V7__event_outbox.sql` | t_event_outbox 表（Iteration 1.5 C1） |
| `V8__state_transition.sql` | t_state_transition 表（Iteration 1.5 C2） |
| `V9__country_state_transition.sql` | sys_country 状态机种子（DRAFT→ACTIVE/VOID, ACTIVE→FROZEN） |
| `V10__country_status.sql` | sys_country 加 status 列 |
| `V12__approval_record.sql` | t_approval_record 表（Iteration 1.5 C4） |
| `V13__approval_permission.sql` | approval:list / approval:approve 权限种子（挂 SUPER_ADMIN） |

> Migrations are immutable once applied. If you need to change a V1-V13 file, create a new `V14__fix_xxx.sql` instead — see `docs/15-表结构对账与待更新清单-v2.1.md`.

---

## 3. Start the frontend

In a new terminal:

```bash
cd lumen-admin-web
npm install        # first time only, ~2 minutes
npm run dev
```

Open `http://localhost:5173`.

The login form pre-fills in dev mode (`import.meta.env.DEV` gated — production build starts blank):

- 租户 ID (Tenant ID): `1`
- 用户名 (Username): `admin`
- 密码 (Password): `admin123`

Click **登录 (Login)**. The 7 backend management pages are accessible from the left menu:

1. 用户管理 (Users)
2. 角色管理 (Roles)
3. 权限矩阵 (Permission Matrix)
4. 数据字典 (Dicts)
5. 编号规则 (Number Rules)
6. 通知模板 (Notifications)
7. 审计日志 (Audit Logs)

---

## 4. Run tests

### Backend unit tests

```bash
cd lumen-parent
mvn test -am
```

Runs unit tests across all 6 modules in a few seconds.

### Backend integration tests (testcontainers)

Integration tests spin up real MySQL + Redis containers via [Testcontainers](https://java.testcontainers.org/), so **Docker Desktop must be running**.

```bash
cd lumen-parent
mvn verify -pl lumen-bootstrap -am
```

First run: ~5 minutes (pulls `mysql:8.0` + `redis:7-alpine` images via testcontainers). Subsequent: ~1 minute.

### Backend integration tests with reused containers (`international-*`)

If your host already has 3306 / 6379 occupied by something else, or you want faster
iteration on a long-lived dev container, you can reuse a fixed pair of containers
(`international-mysql`, `international-redis`) defined in
`lumen-bootstrap/src/main/docker/docker-compose.test.yml` instead of having
testcontainers spin up a fresh one each run.

```bash
# 1. 起/重置容器（down -v 会把残留业务表清掉 —— **复用模式不做自动 reset**，
#    所以每次跑 IT 之前最好 reset 一下避免数据冲突；testcontainers 模式不需要这步）
cd D:/work-ai/0401-lumen-International-Logistics
docker compose -f lumen-parent/lumen-bootstrap/src/main/docker/docker-compose.test.yml down -v
docker compose -f lumen-parent/lumen-bootstrap/src/main/docker/docker-compose.test.yml up -d
# 等到 international-mysql health=healthy（~30s；首次拉镜像更慢）

# 2. 加 -Dlumen.test.reuse-containers=true 跑 IT
cd lumen-parent
mvn -pl lumen-bootstrap verify -am -Dlumen.test.reuse-containers=true
```

容器名前缀固定为 `international-` 区别于同机其他项目（`aiopc-*`）和 lumen dev
compose（`lumen-mysql` / `lumen-redis`）。如果端口冲突（宿主 3306 / 6379 被占），
编辑 `docker-compose.test.yml` 里的 `ports:` 段，并通过 JVM 参数同步覆盖：

```bash
mvn -pl lumen-bootstrap verify -am \
  -Dlumen.test.reuse-containers=true \
  -Dlumen.test.mysql.host=localhost -Dlumen.test.mysql.port=3307 \
  -Dlumen.test.redis.host=localhost -Dlumen.test.redis.port=6379
```

⚠️ **复用模式 caveat**：和 testcontainers 不同，`IntegrationBase` 不会在 JVM
退出时自动 drop volumes。所以同一套容器连跑两次 IT 时，第二次会看到上一次
留的用户 / outbox 行 / login log —— 失败表现为 `Expected size: 1 but was: 2`
或者 409 冲突。要么每次跑前 `down -v`，要么干脆用 testcontainers 默认模式。

**26 `@Test` methods covering 8 mandatory scenarios + Iteration 1.5 cross-cutting:**

| # | Scenario | Test method |
|---|----------|-------------|
| 1 | Login with wrong password → 401 + `USER_PASSWORD_WRONG` | `AuthFlowIT#scenario1_loginWrongPassword_returns10002` |
| 2 | Refresh with malformed token → 401 | `AuthFlowIT#scenario2_refreshMalformedToken_returnsAuthError` |
| 3 | Cross-tenant user list filtered | `RbacIsolationIT#scenario3_listUsers_returnsOnlyTenantJsonMatchingLoginTenant` |
| 4 | Cross-tenant role assignment rejected | `RbacIsolationIT#scenario4_assignRoleToNonexistentUser_rejected` |
| 5 | `POST /users` writes `sys_audit_log` | `AuditLoggingIT#scenario5_postUser_writesAuditLog` |
| 6 | Concurrent number generation produces unique values | `NumberingConcurrencyIT#scenario6_concurrentPreview_producesUniqueNumbers` |
| 7 | Country state machine: `ACTIVE → DISABLED` | `StateMachineIT#scenario7_countryTransition_activeToDisabled_succeeds` |
| 7b | Country state machine: `DISABLED → ACTIVE` | `StateMachineIT#scenario7b_countryTransition_disabledToActive_succeeds` |
| 7c | Country state machine: invalid event → 400 | `StateMachineIT#scenario7c_countryTransition_invalidEvent_returns400` |
| 8 | Notification send writes `notification_send_log` | `NotificationSendIT#scenario8_sendNotification_writesSendLog` |
| C1.1 | Outbox: business publish → consumer receives | `OutboxProducerConsumerIT` |
| C1.2 | Outbox: failed events retry with backoff | `OutboxRetryIT` |
| C1.3 | Outbox: concurrent dispatch picks distinct rows | `OutboxConcurrencyIT` |
| C1.4 | Outbox: async listener receives via dispatcher | `OutboxAsyncIT` |
| C2.1 | State machine: t_state_transition drive assertion | `StateConfigIT` |
| C2.2 | SysCountry: changeState + outbox country.state_changed | `CountryStateTransitionIT` |
| C4.1 | Approval: submit → approve → event → apply changes | `ApprovalFlowIT` |
| C4.2 | Approval: non-admin role → noPermission | `ApprovalFlowIT` |
| C4.3 | Approval: reject terminates + second approve fails | `ApprovalFlowIT` |
| C4.4 | Approval: only applicant can withdraw | `ApprovalFlowIT` |

### Frontend build

```bash
cd lumen-admin-web
npm run build      # tsc -b && vite build → outputs dist/
npm run type-check # tsc --noEmit (type check only)
npm run lint       # ESLint, max-warnings 0
```

---

## 5. End-to-end demo flow

After steps 1-3 are running:

1. Log in with `admin` / `admin123` / tenant `1`.
2. Navigate to **用户管理 (Users)** → click **新建 (New)** → create a user.
3. Navigate to **数据字典 (Dicts)** → create a dictionary entry.
4. Navigate to **编号规则 (Number Rules)** → click **预览 (Preview)** on the `ORDER` rule (seeded in `V4__numbering.sql`: prefix `ORD`, date `yyyyMMdd`, 6-digit seq, daily reset) → see a generated number like `ORD-YYYYMMDD-NNNNNN`.
5. Navigate to **通知模板 (Notifications)** → click **测试发送 (Test Send)** → fill in receiver → submit → check backend logs for `[MOCK-EMAIL]` output (notifications are stubbed in dev; see `lumen-notification` mock channels).
6. Open backend logs (terminal from step 2) → look for audit log entries with `traceId`, `userId`, `action`, `resource`.

---

## 6. Project layout

```
lumen-parent/                          # Backend (Maven multi-module, parent BOM in pom.xml)
├── lumen-common/                      # Shared: R, PageResult, BaseEntity, TenantContext, JWT, Audit, BizException, SecurityUtil
├── lumen-rbac/                        # RBAC: User, Role, Permission, Auth, JWT, SecurityConfig
├── lumen-masterdata/                  # Master data: Dict, Country, Currency, UoM, approval listeners (outbox consumers)
├── lumen-numbering/                   # Number generator: Redis INCR + DB fallback
├── lumen-notification/                # Notification: templates + 3 mock channels (EMAIL/SMS/IM)
├── lumen-extension/                   # Cross-cutting: Event Outbox (C1) + State Machine (C2) + Approval (C4)
└── lumen-bootstrap/                   # Entry point: @SpringBootApplication + @EnableExtension + Flyway + Docker

lumen-admin-web/                       # Frontend (Vite + React 18 + TS + Antd Pro)
├── src/
│   ├── App.tsx                        # Mounts AuthProvider + LocaleProvider + ConfigProvider
│   ├── access.ts                      # Permission sync (legacy shim; prefer useAccess)
│   ├── auth/                          # AuthProvider, ProtectedRoute, useAuth, tokenStorage, silentRefresh
│   ├── components/                    # ThemeToggle, LocaleSwitcher, ErrorBoundary, LoadingSkeleton
│   │   └── columns/                   # StatusTag, DateTimeColumn, IdColumn (table cell primitives)
│   ├── hooks/                         # useAccess, useDict, useTableRequest
│   ├── layouts/                       # BasicLayout (ProLayout)
│   ├── locale/                        # zh-CN / en-US + LocaleProvider (in-house i18n, ~33 keys)
│   ├── pages/                         # Login, Users, Roles, Permissions, Dict, NumberRule, Notification, AuditLog, NotFound
│   ├── services/                      # axios wrapper (request.ts) with silent refresh-on-401
│   ├── theme/                         # tokens.ts (light/dark Antd ThemeConfig) + useThemeMode hook
│   ├── types/                         # api.ts (shared request/response shapes)
│   └── __tests__/                     # Vitest specs (smoke + per-module unit tests)
└── vite.config.ts                     # Dev server on :5173, proxies /api/v1 → :8080

docs/
├── 01-系统设计总览-v1.0.md            # System overview
├── 02-业务表结构设计-v2.0.md          # Business table design
├── 13-第二轮深化设计-总览与扩展性框架-v4.0.md   # Cross-cutting + extensibility framework
└── superpowers/                       # Platform-skeleton spec + implementation plan
    ├── specs/2026-09-18-platform-skeleton-design.md
    └── plans/2026-09-18-iteration-1.5-cross-cutting-foundation.md
```

---

## 7. lumen-extension 跨切面基建（Iteration 1.5+）

后端 Maven 多模块新增第 7 个 module `lumen-extension`，承载三个跨切面能力：

- **C1 — Event Outbox** (`lumen-extension/outbox/`)：业务事件 → `t_event_outbox` 表（与业务事务同提交）→ `OutboxDispatcher` (`@Scheduled` 2s 轮询 + `FOR UPDATE SKIP LOCKED`) → `@Async @EventListener` 异步消费。指数退避重试（30s→120s→600s）+ 死信兜底。
- **C2 — 状态机配置化** (`lumen-extension/state/`)：`t_state_transition` 表 + Caffeine 30s TTL 缓存 + `StateMachineEngine.assertTransition`。SysCountry 已迁移至此模式。
- **C4 — 审批流** (`lumen-extension/approval/`)：`t_approval_record` + yml 配置 `lumen.approval.chains` 路由 + 单级审批 service。SysCountry edit 提交审批 → SUPER_ADMIN 审批通过 → 异步 listener 应用变更。

启用方式：`@EnableExtension` 加在 `LumenApplication` 上。

事件消费模板（**任何模块订阅 outbox 事件**的标准写法）：
```java
@Async("outboxExecutor")
@EventListener
public void on(SomeDomainEvent e) { ... }
```

> **不要**使用 `@TransactionalEventListener(AFTER_COMMIT)`：OutboxDispatcher 无活动事务，AFTER_COMMIT 不会触发。outbox 行的存在已经证明业务事务已提交。
>
> 异步 listener 跑在 `outboxExecutor` 线程上，`TenantContext` ThreadLocal 是空的 —— 需要租户过滤的 mapper 调用必须从事件本身的 `tenantId` 字段重新设置，try-finally 清理：
> ```java
> @Async("outboxExecutor") @EventListener
> public void on(CountryEditApprovedEvent e) {
>     try {
>         TenantContext.set(e.getTenantId());
>         countryMapper.updateById(...);
>     } finally { TenantContext.clear(); }
> }
> ```

---

## 8. Security notes for production

> **MUST change before any non-dev deploy:**

- `LUMEN_JWT_SECRET` — HS256 needs **at least 32 bytes (256 bits)** of secret material. Both `docker-compose.dev.yml` and `application-dev.yml` ship a 64-hex-char default (`0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef`, 32 bytes raw), which is OK for HS256. Generate a fresh one with `openssl rand -hex 32` for staging / prod.
- Admin password — `admin / admin123` is seeded by `V2__rbac.sql` for dev convenience; force a password reset on first login for any non-dev environment.
- `MYSQL_ROOT_PASSWORD` and `MYSQL_PASSWORD` in `docker-compose.dev.yml` (`rootpass` / `lumen`) — replace with strong values before exposing the stack.
- CORS: No explicit CORS config exists; dev relies on the Vite proxy (same-origin). Production should add a `CorsConfigurationSource` permitting only the real frontend origin(s).

**Tenant isolation:** All tenant-scoped tables go through the MyBatis-Plus multi-tenant plugin (`TenantLineInnerInterceptor`). Verify by trying to read another tenant's data — should return empty. See `RbacIsolationIT` for the executable check.

**JWT algorithm:** JJWT 0.12.x auto-selects the HMAC algorithm by secret byte length: 32-47 bytes → HS256, 48-63 bytes → HS384, ≥ 64 bytes → HS512. Both dev (64-hex-char = 32 bytes) and compose (64-hex-char = 32 bytes) defaults select HS256. Production should pin explicitly via the `lumen.jwt.algorithm` property.

---

## 9. Troubleshooting

### Backend won't start: `Cannot load driver class: com.mysql.cj.jdbc.Driver`

MySQL container is not running. Check `docker compose -f lumen-parent/lumen-bootstrap/src/main/docker/docker-compose.dev.yml ps` and ensure `mysql` is `healthy`.

### Backend starts but Flyway fails: `Migration checksum mismatch`

You've modified a migration script. Migrations are immutable once applied. Create a new `V14__fix_xxx.sql` instead, or drop the dev DB volume (`docker compose ... down -v`) to start fresh.

### Frontend can't reach backend: `Network Error` on login

1. Is backend running on `:8080`? `curl http://localhost:8080/actuator/health`
2. Is Vite proxy configured? Check `lumen-admin-web/vite.config.ts` → `/api/v1` proxy target should be `http://localhost:8080`.
3. CORS issue? Check `SecurityConfig.java` — it should permit `/api/v1/auth/**`.

### Integration tests time out / fail with "Could not find a valid Docker environment"

Docker Desktop must be running (Docker daemon socket accessible). On macOS / Windows ensure Docker Desktop is started, not just installed. On Linux, confirm your user is in the `docker` group.

### Permission denied on user create

The seeded `admin` user has the `SUPER_ADMIN` role with all 27 permissions. If you logged in as a different user, they may lack `user:create`. Either:
- Use the `admin` user (recommended for dev), or
- Assign the `user:create` permission via the **角色管理 (Roles)** page.

### Outbox event listener never fires

- Check `t_event_outbox.status`: stuck `PENDING` rows past their `next_retry_at` mean the dispatcher's `@Scheduled` 2s poll isn't running, or `FOR UPDATE SKIP LOCKED` contention.
- Listener runs on `outboxExecutor` thread — `TenantContext` is empty there. If your listener touches tenant-scoped mappers, set `TenantContext.set(event.getTenantId())` first and clear in finally (see section 7).

---

## 10. Next steps

- Run `mvn verify -pl lumen-bootstrap -am` to confirm all 26 integration tests pass (10 MVP scenarios + 4 outbox + 2 state + 4 approval = 20 listed; rest are parameterized variants within each IT class).
- Browse the 7 pages and exercise CRUD on Users / Roles / Dict / Notification.
- Read `docs/13-第二轮深化设计-总览与扩展性框架-v4.0.md` for the broader platform vision.
- Read `docs/superpowers/specs/2026-09-18-platform-skeleton-design.md` for the MVP design rationale.
- Read `docs/superpowers/plans/2026-09-18-iteration-1.5-cross-cutting-foundation.md` for the 23-task Iteration 1.5 implementation plan.

---

## 11. Iteration 2.0 — Frontend Deepening (2026-09-20+)

This iteration moved `lumen-admin-web` from "runnable skeleton" to "production-grade". Every change is documented inline in source via docblocks — this section is the index. Source paths below are relative to `lumen-admin-web/`.

### 11.1 Auth & Permissions

- **`ProtectedRoute`** (`src/auth/ProtectedRoute.tsx`) wraps any route that requires authentication; redirects to `/login?from=...` if no token.
- **`AuthProvider`** (`src/auth/AuthProvider.tsx`) is the single source of truth for the current user, perms, roles. Mounted once in `src/App.tsx`.
- **Silent refresh** (`src/auth/silentRefresh.ts` + wired in `src/services/request.ts`): axios response interceptor catches `401`, calls `/auth/refresh`, retries the original request. Single-flight Promise pattern prevents thundering-herd refresh.
- **`useAuth()`** (`src/auth/useAuth.ts`) exposes `{ user, login, logout, refresh, isAuthenticated }`.
- **`useAccess()`** (`src/hooks/useAccess.ts`) exposes `{ perms, roles, canRead(perm), canAny(perms), canAll(perms), hasRole(role) }`. Used by both menu perm filtering (`BasicLayout`) and per-row buttons.
- **Token storage** (`src/auth/tokenStorage.ts`) is `localStorage` under `lumen_access` + `lumen_refresh` keys; SSR-safe via `try/catch`.

### 11.2 Shared Hooks

- **`useTableRequest(fetch, deps)`** (`src/hooks/useTableRequest.ts`) — bridges ProTable params ↔ Spring `PageResult`. Returns a memoized request callback.
- **`useDict(code)`** (`src/hooks/useDict.ts`) — fetches `/dicts/{code}/items`, caches in a module-level Map + `sessionStorage` (key `lumen:dict:<code>`). Returns `{ valueEnum, loading }`. Cleared on logout.

### 11.3 Shared Components

- **`StatusTag`** (`src/components/columns/StatusTag.tsx`) — colored Antd `Tag` for boolean/numeric status columns.
- **`DateTimeColumn`** (`src/components/columns/DateTimeColumn.tsx`) — formatted timestamp cell, accepts `format: 'datetime' | 'date'`.
- **`IdColumn`** (`src/components/columns/IdColumn.tsx`) — truncated ID + copy-to-clipboard.
- **`ErrorBoundary`** (`src/components/ErrorBoundary.tsx`, `level='form'`) — inline `Alert` fallback for form crashes; page-level variant wraps the whole `App`.
- **`ThemeToggle`** (`src/components/ThemeToggle.tsx`) — header button that flips between light/dark mode.
- **`LocaleSwitcher`** (`src/components/LocaleSwitcher.tsx`) — header dropdown that flips between `zh-CN` / `en-US`.

### 11.4 Theme & i18n

- **Theme tokens** (`src/theme/tokens.ts`) — light + dark Antd `ThemeConfig`. `cssVar: true` emits CSS variables.
- **`useThemeMode()`** (`src/theme/useThemeMode.ts`) — React hook returning `[mode, setMode, toggle]`. Persists to `lumen_theme_mode` in `localStorage`; system-preference fallback via `matchMedia('(prefers-color-scheme: dark)')`.
- **`LocaleProvider`** (`src/locale/LocaleProvider.tsx`) — minimal in-house i18n (~33 keys, see `src/locale/zh-CN.ts` + `src/locale/en-US.ts`). `zh-CN` is the source of truth; `en-US` is `Record<ZhKey, string>` enforced at compile time. Use `useLocale().t('menu.users')`.

### 11.5 Page Features (Iteration 2.0)

- **Roles** (`src/pages/Roles/`) — "分配权限" button per row opens a `Modal` with a multi-select permission `Tree`.
- **Permissions** (`src/pages/Permissions/`) — matrix view (rows = perms, cols = roles) with optimistic checkbox toggles.
- **Users** (`src/pages/Users/`) — "分配角色" button per row opens a `Checkbox.Group` modal.
- **Dict** (`src/pages/Dict/`) — "字典项" button per row opens a `Drawer` with a sub-table for CRUD on dict items.
- **NumberRule** (`src/pages/NumberRule/`) — "预览" + "重置" buttons per row.
- **AuditLog** (`src/pages/AuditLog/`) — `IdColumn` for `traceId` with copy-to-clipboard; status filter dropdown.
- **Notification** (`src/pages/Notification/`) — 403 short-circuit when user lacks `notification_template:list`.

### 11.6 Tests

```bash
cd lumen-admin-web
npm test              # one-shot vitest run
npm run test:watch    # interactive watch mode
npm run test:coverage # v8 coverage report → ./coverage/
```

Test suite (31 tests across 6 files):

| File | Tests |
|------|------:|
| `src/__tests__/smoke.test.tsx` | 2 — Vitest stack smoke |
| `src/__tests__/App.smoke.test.tsx` | 1 — App-level smoke |
| `src/auth/__tests__/AuthProvider.test.tsx` | 6 — context lifecycle |
| `src/hooks/__tests__/useAccess.test.tsx` | 10 — `canRead` / `canAny` / `canAll` / `hasRole` |
| `src/hooks/__tests__/useDict.test.tsx` | 7 — fetch / cache / `sessionStorage` |
| `src/hooks/__tests__/useTableRequest.test.tsx` | 5 — ProTable ↔ Spring bridge |

Coverage of unit-tested modules is 100% on `useAccess` + `useTableRequest` + `useAuth` + `theme/tokens`. It is lower (~58%) on `AuthProvider` because the `/auth/me` bootstrap path requires a real backend.

### 11.7 LocalStorage Keys

| Key | Type | Purpose |
|-----|------|---------|
| `lumen_access` | `localStorage` | JWT access token |
| `lumen_refresh` | `localStorage` | JWT refresh token |
| `lumen_theme_mode` | `localStorage` | `'light' \| 'dark'` |
| `lumen_locale` | `localStorage` | `'zh-CN' \| 'en-US'` |
| `lumen:dict:<code>` | `sessionStorage` | Dict item cache (per code) |

---

**Generated by:** Task 22 of the Iteration 1.5 cross-cutting foundation plan
**Updated by:** Task 5.2 of the Iteration 2.0 frontend deepening plan
**Plan:** [`docs/superpowers/plans/2026-09-18-iteration-1.5-cross-cutting-foundation.md`](./docs/superpowers/plans/2026-09-18-iteration-1.5-cross-cutting-foundation.md)