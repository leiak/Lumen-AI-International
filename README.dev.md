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
| `V15__wms_schema.sql` | WMS 12 张业务表（warehouse/location/item/customer + inbound/outbound/adjust/inventory/threshold，Iteration 3.0a） |
| `V16__wms_seed.sql` | WMS 4 状态机种子（WMS_INBOUND 10 / WMS_OUTBOUND 12 / WMS_INV_ADJUST 8 / WMS_LOCATION 4 = 34 transitions，填入 `t_state_transition`） |
| `V17__wms_perms.sql` | WMS 权限种子（33 条 wms:* 权限 + WMS_ADMIN/WMS_MANAGER/WMS_CLERK 角色） |
| `V18__adjust_reject_reason.sql` | `wms_inventory_adjust.reject_reason VARCHAR(255)` 列（PR5-T5.2 驳回原因存储） |

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

Click **登录 (Login)**. The 16 backend management pages are accessible from the left menu (filtered by your role's perms — see `useAccess`):

1. 用户管理 (Users)
2. 角色管理 (Roles)
3. 权限矩阵 (Permission Matrix)
4. 数据字典 (Dicts)
5. 编号规则 (Number Rules)
6. 通知模板 (Notifications)
7. 审计日志 (Audit Logs)
8. 仓库 (Warehouse)
9. 库位 (Location)
10. 商品 (Item)
11. 客户 (Customer)
12. **WMS 库存 (Inventory)** — Iteration 3.0a 主干：按仓库/库位/商品的余额+锁定 ledger
13. **WMS 阈值配置 (Threshold)** — 跨入库/出库/调整三类业务的金额+数量阈值，租户级单行配置
14. **WMS 入库单 (Inbound)** — `DRAFT → PENDING_APPROVAL → RECEIVING → PUTAWAY → DONE`，阈值超 → 审批
15. **WMS 出库单 (Outbound)** — `DRAFT → PENDING_APPROVAL → PICKING → REVIEW → SHIPPED → DONE`，拣货扣减库存
16. **WMS 调整单 (Adjust)** — `DRAFT → PENDING_APPROVAL → APPROVED → DONE`，执行时按行 `deltaQty` ± 调整库存

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

**43 `@Test` methods (26 MVP+Iter1.5 + 17 WMS vertical slice) covering 8 mandatory scenarios + 4 cross-cutting domains + WMS end-to-end:**

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
| W1 | WMS masterdata: warehouse/location/item/customer CRUD + tenant isolation | `MasterDataIT` |
| W2 | WMS inventory: `addQty` / `lockQty` / `releaseLock` / `subtractQty` / `adjustDelta` / `flushLock` 原子性 + 负库存/超额校验 | `InventoryFlowIT` |
| W3 | WMS inbound: full path (DRAFT → PENDING_APPROVAL → RECEIVING → PUTAWAY → DONE) + 阈值触发审批 + 收/上架累加 + 取消 | `InboundFlowIT` |
| W4 | WMS outbound: full path (DRAFT → PICKING → REVIEW → SHIPPED → DONE) + 拣货扣减库存 + 锁释放 + 库存不足 409 | `OutboundFlowIT` |
| W5 | WMS adjust: full path (DRAFT → APPROVED → DONE) + 阈值路由 + 正/负 delta 应用 | `AdjustFlowIT` |

> **Known flake**: `OutboxRetryIT.listenerAlwaysFails_rowGoesDeadLetterAfterThreeAttempts` 在 full-suite 跑时偶发失败（背景 dispatcher 线程跨 IT 残留）。单跑 `mvn -pl lumen-bootstrap verify -Dit.test=OutboxRetryIT` 始终 2/2 通过；pre-Iter 3.0a 已存在，与本次 WMS 切片无关。如需 clean suite 跑全量：`mvn verify -pl lumen-bootstrap -Dit.test='!OutboxRetryIT'` → **41/41 pass**（含 W1-W5 共 17 个 WMS 用例）。

### Frontend build

```bash
cd lumen-admin-web
npm run build      # tsc -b && vite build → outputs dist/
npm run type-check # tsc --noEmit (type check only)
npm run lint       # ESLint, max-warnings 0
```

### Frontend unit tests (Vitest)

```bash
cd lumen-admin-web
npm test           # one-shot, currently 81 tests across 12 test files
```

Test suite (81 tests across 12 files as of Iter 3.0a):

| File | Tests |
|------|------:|
| `src/__tests__/smoke.test.tsx` | 2 — Vitest stack smoke |
| `src/__tests__/App.smoke.test.tsx` | 1 — App-level smoke |
| `src/auth/__tests__/AuthProvider.test.tsx` | 6 — context lifecycle |
| `src/hooks/__tests__/useAccess.test.tsx` | 12 — `canRead` / `canAny` / `canAll` / `hasRole` + stable memoization + case-sensitive matching |
| `src/hooks/__tests__/useDict.test.tsx` | 8 — fetch / cache / `sessionStorage` + loading-true during in-flight |
| `src/hooks/__tests__/useTableRequest.test.tsx` | 6 — ProTable ↔ Spring bridge + empty PageResult |
| `src/components/Wms/__tests__/WmsPromptModal.test.tsx` | 3 — OK / Cancel / `maxLength` enforcement |
| `src/components/Wms/__tests__/StateTransitionPanel.test.tsx` | 8 — status+perm gating / loading / danger / wrap |
| `src/components/Wms/__tests__/WmsNoteTable.test.tsx` | 2 — config-driven render + status filter |
| `src/services/wms/__tests__/{inbound,outbound,adjust,warehouse,location,customer,item,threshold,inventory}.test.ts` | 33 — service HTTP-verb + path + body assertions via `vi.mock('@/services/request')` |

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
├── lumen-wms/                         # WMS 垂直切片 (Iter 3.0a): 12 entities + 8 services + 6 controllers + ThresholdEvaluator + 锁/扣/调拨原子库存接口
└── lumen-bootstrap/                   # Entry point: @SpringBootApplication + @EnableExtension + Flyway + Docker

lumen-admin-web/                       # Frontend (Vite + React 18 + TS + Antd Pro)
├── src/
│   ├── App.tsx                        # Mounts AuthProvider + LocaleProvider + ConfigProvider
│   ├── access.ts                      # Permission sync (legacy shim; prefer useAccess)
│   ├── auth/                          # AuthProvider, ProtectedRoute, useAuth, tokenStorage, silentRefresh
│   ├── components/                    # ThemeToggle, LocaleSwitcher, ErrorBoundary, LoadingSkeleton, WmsNoteTable, StateTransitionPanel, WmsPromptModal
│   │   ├── columns/                   # StatusTag, DateTimeColumn, IdColumn (table cell primitives)
│   │   └── Wms/                      # WMS-specific shared components (Iter 3.0a)
│   ├── hooks/                         # useAccess, useDict, useTableRequest
│   ├── layouts/                       # BasicLayout (ProLayout)
│   ├── locale/                        # zh-CN / en-US + LocaleProvider (in-house i18n, ~38 keys)
│   ├── pages/                         # Login, Users, Roles, Permissions, Dict, NumberRule, Notification, AuditLog, Wms/, NotFound
│   │   └── Wms/                       # Warehouse, Location, Item, Customer, Inventory, Threshold, Inbound/, Outbound/, Adjust/ (Iter 3.0a)
│   ├── services/                      # axios wrapper (request.ts) with silent refresh-on-401
│   │   └── wms/                       # 10 typed service modules (Iter 3.0a): inbound/outbound/adjust/inventory/threshold/warehouse/item/customer/location + index barrel
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

## 12. Iteration 3.0a — WMS Vertical Slice (2026-10-11+)

本迭代把仓储 WMS 从「表结构（PR1）+ 库存原子接口（PR2）+ 入/出/调持久化（PR3 起步）」推到「入库 / 出库 / 调整单端到端 + 阈值路由 + 审批闸门 + 库存自动联动」的完整业务闭环。所有代码均落在新增 Maven 子模块 `lumen-wms` 及 `lumen-admin-web/src/pages/Wms/` 与 `lumen-admin-web/src/services/wms/`。

### 12.1 业务实体（V15 + V18 migrations）

12 张业务表 + 1 列增量：

| 表 | 行数 / 列 | 备注 |
|----|-----------|------|
| `wms_warehouse` | 仓库主数据 | per tenant，全局唯一 code |
| `wms_location` | 库位 | belongs to warehouse + 状态机（ACTIVE/FROZEN/DISABLED） |
| `wms_item` | 商品 | 含 `unit_cost`（调拨金额阈值计算依赖） |
| `wms_customer` | 客户 | 简化版 masterdata |
| `wms_threshold_config` | 租户级阈值 | 6 个阈值（inb/out/adj × amount/qty）+ `approval_required_when_exceed` 开关 |
| `wms_inbound_note` + `wms_inbound_line` | 入库单聚合 | line: `qty` / `received_qty` / `putaway_qty` / `putaway_location_id` |
| `wms_outbound_note` + `wms_outbound_line` | 出库单聚合 | line: `qty` / `picked_qty` / `source_location_id` |
| `wms_inventory_adjust` + `wms_inventory_adjust_line` | 调整单聚合 | line: `deltaQty`（可正可负）；V18 加 `reject_reason` 列 |
| `wms_inventory` | 库存 ledger | (warehouse, location, item) 三元组唯一；`qty` / `locked_qty` 列 + DB CHECK `qty >= locked_qty >= 0` |

### 12.2 状态机（V16 seed）

34 条 transition 写入 `t_state_transition`，runtime 经 `StateMachineEngine.assertTransition` 校验：

| 状态机 | transitions | 角色门控 |
|--------|-------------|----------|
| `WMS_INBOUND` | 10 | wms_clerk / wms_manager |
| `WMS_OUTBOUND` | 12 | wms_clerk / wms_manager |
| `WMS_INV_ADJUST` | 8 | wms_clerk / wms_manager |
| `WMS_LOCATION` | 4 | wms_manager / wms_admin |

> `ApprovalService.submit()` 与本级 ApprovalRouter 的 `WMS_INV_ADJUST` / `WMS_OUTBOUND` / `WMS_INBOUND` bizType 接入仍 deferred — 当前 `submit()` 在 service 层自行调 `ThresholdEvaluator.requiresApproval(...)` 路由 PENDING_APPROVAL / 直入下态。代码内 `// TODO(PR5-iter)` 标注。

### 12.3 库存原子接口（`WmsInventoryService`）

| 方法 | 语义 | 原子性 |
|------|------|--------|
| `addQty(wh, loc, item, qty)` | 收货 / 调拨入 | `qty += qty` |
| `lockQty(wh, loc, item, qty)` | 出库提交预留 | `locked_qty += qty`，校验 `qty - locked_qty >= 0` |
| `releaseLock(wh, loc, item, qty)` | 出库拣货/取消释放 | `locked_qty -= qty` |
| `subtractQty(wh, loc, item, qty)` | 出库拣货物理扣减 | `qty -= qty` + 校验 `qty >= 0`（出库原子：subtractQty + releaseLock 必须同事务） |
| `adjustDelta(wh, loc, item, delta)` | 调整单执行 | `qty += delta`，可正可负 |
| `flushLock(wh, loc, item)` | 出库完成兜底 | `locked_qty = 0`（防 lock 残留） |

事务边界 + `@Version` 乐观锁 + `TenantLineInnerInterceptor` 租户过滤。

### 12.4 阈值路由（`ThresholdEvaluator`）

```java
@Component
public class ThresholdEvaluator {
  public boolean requiresApproval(String bizKind, BigDecimal amount, BigDecimal totalQty);
}
```

- `bizKind ∈ { "INB", "OUT", "ADJ" }` 路由到对应阈值列
- 任一维超阈值且 `approval_required_when_exceed = 1` → 返 `true` → service 进 `PENDING_APPROVAL`
- 全租户单行配置；首次访问 `getOrInit()` 自动插入全 0 行

### 12.5 FE — 共享组件 + 服务模块

- **`WmsNoteTable`** — 通用 ProTable（参数化 service / 列 / 新建按钮 / detail 路由），为后续将 3 个 List 页重构到配置驱动预留接口
- **`StateTransitionPanel`** — 声明式 `transitions[]` → 按 status+perm 过滤 + Popconfirm；3 个 Detail 页均已迁移
- **`WmsPromptModal`** — singleton `showPrompt({ title, placeholder, maxLength, required })` 替代 `window.prompt`；用于入库收/上架、出库拣货/发货、调整驳回原因（maxLength 255 + required）
- **`services/wms/*`** — 10 个 typed 服务模块（inbound / outbound / adjust / inventory / threshold / warehouse / item / customer / location + barrel）；3 个 Detail 页已迁移（其它 6 个 Masterdata List/Form 仍内联 `request(...)`，出范围）

### 12.6 关键 IT 用例（`mvn verify -pl lumen-bootstrap -Dit.test='!OutboxRetryIT'`）

17 条 WMS 集成测试：

| IT | 验证 |
|----|------|
| `MasterDataIT` (5) | 仓库 / 库位 / 商品 / 客户 / 阈值的 CRUD + 租户隔离 |
| `InventoryFlowIT` (3) | `addQty` / `lockQty+releaseLock` / `adjustDelta` 原子性 + 越界拒绝 |
| `InboundFlowIT` (3) | 完整 DRAFT→RECEIVING→PUTAWAY→DONE + 收/上架累加 + 取消 |
| `OutboundFlowIT` (3) | 完整 DRAFT→PICKING→REVIEW→SHIPPED→DONE + 拣货物理扣减 (100→95) + 库存不足 409 + 阈值超审批 |
| `AdjustFlowIT` (3) | 完整 DRAFT→APPROVED→DONE + 正负 delta (100→105→102) + execute→409 |

### 12.8 已知 follow-up（不在本切片范围内）

- **PR5-T5.2** (已合并): `AdjustController.approve` 返回 `R<WmsInventoryAdjust>` 而 outbound 返回 `R<Void>` — 风格统一为后续清理
- **PR5-T5.2** (已合并): `wms_inventory_adjust` 加 `reject_reason` 列需要 V18 — 任何新部署必须跑 V18
- **PR5-T5.3** (已合并): Inbound Detail 缺 `PUTAWAY → DONE` 的 note-level 完成 transition；当前依赖逐行 `putaway` 满了自动滚状态
- **T3.5** (Iter 3.0a 早期): `ThresholdConfigService.update()` 用 `get()` 而非 `getOrInit()` — 首次初始化前 PUT 会抛 404；建议用 `getOrInit()` 重构
- **跨 PR 整合**: ApprovalService 接入 WMS_INBOUND / WMS_OUTBOUND / WMS_INV_ADJUST 三个 bizType 到 ApprovalRouter — 当前 threshold 路由在 service 层手工做，待工单事件接入后统一收敛
- **Outbox 集成**: 入/出/调状态机转换事件未投递 `t_event_outbox` — 后续业务事件统一走 `publishIfNotInTransaction(...)` 模式

### 12.7 总测试状态

| 维度 | 数 |
|------|----|
| Maven 后端单测 | **91/91 pass** (lumen-wms 91 + 其它模块数稳定) |
| Maven 集成测试 | **41/41 pass** (排除 1 条 OutboxRetryIT 已知 flake) |
| Vitest 前端 | **81/81 pass** (12 test files) |
| FE build / tsc | clean |

---

## 13. Iteration 4.1 — Delivery Dispatch (2026-10-12+)

Iteration 4.1 在 WMS 之上引入「配送调度」纵切片：把出库（`SHIPPED`）自然延伸到车、司机、承运人、配送单、异常工单、运力仪表盘，把 `lumen-parent` 第 7 个业务子模块 `lumen-dispatch` 推到端到端可演示状态。

### 13.1 业务目标

- **闭环链路**: `WMS Outbound.shipped` → 自动创建 `WmsDeliveryOrder (PENDING_DISPATCH)` → 调度员派车/装车/出发/到达/签收 → 完成（`SIGNED`）；途中异常 → `EXCEPTION` → 异常工单 OPEN → 调查/解决/关闭。
- **状态机驱动**: 3 个新状态机 (`DISPATCH_ORDER` 7 / `VEHICLE` 6 / `EXCEPTION_WORK_ORDER` 5 = 18 transitions) 全部进 `t_state_transition`，由 `StateMachineEngine.assertTransition` 守门，perm guard 通过 `dispatch_admin` / `dispatcher` / `driver` 角色路由。
- **运力可视化**: 车辆利用率仪表盘（4 KPI 卡 + 每日明细 ProTable + 日期范围选择器）作为调度员的"看板"。

### 13.2 模块与表结构（V19 schema）

新增 Maven 子模块 `lumen-dispatch`（独立 boot classpath、共享 `lumen-common` / `lumen-extension` 基础设施），7 张业务表（V19 + 3 个 CHECK 约束族）：

| 表 | 行 / 列 | 用途 |
|----|---------|------|
| `wms_vehicle` | 主数据 | `plate_no` 全租户唯一；状态机 `IDLE/DISPATCHED/LOADING/ENROUTE/RETURNING/OUT` |
| `wms_driver` | 主数据 | `phone` 唯一；极简 `ACTIVE/SUSPENDED`（service 层 setStatus） |
| `wms_carrier` | 主数据 | `code` 唯一；`ACTIVE/SUSPENDED` 字段，无独立状态机端点 |
| `wms_delivery_order` | 聚合根 | `source_no` 关联出库单；8 状态机迁移（见下表） |
| `wms_delivery_stop` | 子表 | `(order_id, stop_no)` 唯一；`PENDING/ARRIVED/COMPLETED/FAILED` |
| `wms_delivery_line` | 子表 | V19 已定义；PR4 暂不消费，留作 4.1.1 行级粒度 |
| `wms_delivery_exception` | 工单 | `orderId + type`；5 状态机迁移（OPEN→…→CLOSED） |

### 13.3 状态机（V20 seed, 18 transitions）

| 状态机 | transitions | guard 角色 |
|--------|-------------|------------|
| `VEHICLE` (6) | IDLE→DISPATCHED/LOADING/ENROUTE/RETURNING/OUT; *→OUT (freeze) | `dispatch_admin` (freeze/unfreeze) |
| `DISPATCH_ORDER` (7) | PENDING_DISPATCH→DISPATCHED→LOADED→ENROUTE→ARRIVED→SIGNED; PENDING/DISPATCHED/LOADED/ENROUTE→CANCELLED; LOADED/ENROUTE/ARRIVED→EXCEPTION | `dispatcher` / `driver` |
| `EXCEPTION_WORK_ORDER` (5) | OPEN→INVESTIGATING→RESOLVED→CLOSED; CLOSED→OPEN (reopen) | `customer_service` (兜底绑 `dispatch_admin`) |

### 13.4 引擎

- **`MockRoutingEngine`** (`engine/impl/`) — `auto-assign` 端点背后服务侧 `DispatchEngine.autoAssign(id)`：基于「车辆载重/体积 + 司机状态 + 承运人可用」简单选择，输出 `vehicleId` + `driverId`，落 `wms_delivery_order` 并触发 VEHICLE `IDLE→DISPATCHED` 转换。FE 永远不直连 routing —— 调 `deliveryOrderService.autoAssign` 即可。
- **`DefaultLoadingValidator`** (`engine/impl/`) — `DISPATCHED→LOADED` 前校验总重量/体积不超过车辆 `max_load_kg` / `max_volume_m3`；超出返回业务异常 400。

### 13.5 业务事件（`outbound.shipped` → 自动建单）

PR4 落 `@Async @EventListener` 订阅 `OutboundShippedEvent`：

```java
@Async("outboxExecutor") @EventListener
public void on(OutboundShippedEvent e) {
  // TenantContext.set(e.getTenantId()) + try/finally
  // deliveryOrderService.createFromOutbound(outboundId, ...);
}
```

- 消费模板见 §7 —— 必须在 listener 内重设 `TenantContext`（`outboxExecutor` 线程 ThreadLocal 空）。
- **Outbox 投递**: PR4 仅消费事件，**未**对外回投 `delivery_order.created` 业务事件 —— 留作 PR5.5 / 3.0c（follow-up §13.9）。

### 13.6 前端页面（10 pages）

`lumen-admin-web/src/pages/Dispatch/`：

| 路由 | 页面 | 备注 |
|------|------|------|
| `/dispatch/vehicles` | Vehicle List + Form | 状态机冻结/解冻按钮 |
| `/dispatch/drivers` | Driver List + Form | suspend / resume |
| `/dispatch/carriers` | Carrier List + Form | 仅 ACTIVE/SUSPENDED 字段 |
| `/dispatch/orders` | DeliveryOrder List | 8 SM 按钮（assign/load/depart/arrive/sign/cancel/exception/auto-assign） |
| `/dispatch/orders/:id` | DeliveryOrder Detail | StateTransitionPanel + 站点表 |
| `/dispatch/orders/new` | CreateOrderForm | 手工建单；PR4 最小实现 |
| `/dispatch/exceptions` | Exception List | 工单列表 + 状态过滤 |
| `/dispatch/exceptions/:id` | Exception Detail | 4 端点（investigate/resolve/close/reopen） |
| `/dispatch/dashboard` | Utilization Dashboard | 4 KPI 卡 + 每日明细 ProTable |
| `/dispatch/orders/:id/edit` | Edit Form (复用 Create) | 编辑用 |

### 13.7 权限与角色（V21 seed）

- **26 个 `dispatch:*` 权限** (`sys_permission.id` 98–123)：
  `vehicle:list/create/update/freeze` + `driver:list/create/update/suspend` + `carrier:list/create/update` + `order:list/create/update/cancel/assign/load/depart/arrive/sign` + `exception:list/create/investigate/resolve/close` + `dashboard:view`
- **3 个新角色** (`sys_role.id` 5–7)：
  - `DISPATCH_ADMIN` — 全部 26 个 dispatch 权限
  - `DISPATCHER` — `order:list/create/update/cancel/assign/load/depart/arrive/sign` + `dashboard:view` = 10 perms
  - `DRIVER` — `order:assign/load/depart/arrive/sign` = 5 perms（手持端 mock 角色）

> `EXCEPTION_WORK_ORDER` 的 `investigate/resolve/close` 理论上属于 `customer_service`；本期未 seed 该角色（spec §10 PR2），5 个 exception perm 全部兜底绑到 `DISPATCH_ADMIN`，runtime guard 在状态机引擎层会拒非 `customer_service` 触发这些子状态（V20 已声明 guard）。

### 13.8 集成测试 (21 新增)

`mvn -pl lumen-bootstrap verify -Dit.test='!OutboxRetryIT'`：

| IT | 验证 |
|----|------|
| `MasterDataDispatchIT` (3) | 车辆 / 司机 / 承运人 CRUD + 冻结 + 状态机 guard |
| `DeliveryOrderIT` (4) | 完整 PENDING_DISPATCH→DISPATCHED→LOADED→ENROUTE→ARRIVED→SIGNED；assign/load/depart/arrive/sign/cancel 全端点；EXCEPTION 转工单 |
| `DeliveryExceptionIT` (3) | 完整 OPEN→INVESTIGATING→RESOLVED→CLOSED + reopen |
| `OutboundShippedSubscriberIT` (2) | outbox 投递 `OutboundShippedEvent` → listener 自动建 `DeliveryOrder` + `DeliveryStop` |
| `UtilizationQueryIT` (3) | daily + fleet endpoint SQL 聚合 + 0 行/缺数据回退 |
| `DispatchStateMachineIT` (3) | DISPATCH_ORDER / VEHICLE / EXCEPTION_WORK_ORDER 三机无效转换返 400 |
| `LoadingValidatorIT` (3) | 装载时超载/超体积返 400；合法装载通过 |

PR3 + PR4 + PR5 = **6 + 7 + 7 = 20** IT + 1 边角 = **21 新 IT**（含 `MasterDataIT` + `InboundFlowIT` + `OutboundFlowIT` 等 Iter 3.0a 现有 41 个，共 62/62 pass）。

### 13.9 已知 follow-up（不在本切片范围内）

- **PR5.5 / 3.0c** — `delivery_order.created` / `state_changed` 业务事件未投 `t_event_outbox`，下游 notification / analytics 集成需等统一 outbox publish 模式
- **V23** — `EXCEPTION_WORK_ORDER.resolve` 端点缺失，spec §7.1 列为必备；当前由 `investigate` + `close` 间接覆盖，**resolve 必须** 补端点（5.0b 阻塞）
- **V5 EP-D1** — `MockRoutingEngine` 是最邻近选择；真实 TSP 路径规划（高德/百度地图 + 道路权重）需 5.x 引入 `lumen-routing` 子模块
- **`wms_delivery_line` 表已建未消费** — V19 已定义但 PR4 不写行；4.1.1 行级粒度时再开 `/api/v1/dispatch/orders/{id}/lines` 端点（FE stub 已就位 `deliveryLineService.listByOrderId`）
- **`IT suite` 累计 21+41=62 个 case**，本切片以全绿为目标
- **routing service stub** — `services/dispatch/routing.ts` 故意 throw，FE 误调会得到清晰错误而非 404 静默失败

### 13.10 总测试状态（PR6 收尾）

| 维度 | 数 |
|------|----|
| Maven 后端单测 | **112/112 pass** (lumen-dispatch 21 + lumen-wms 91) |
| Maven 集成测试 | **62/62 pass** (21 dispatch + 41 wms，排除 1 条 OutboxRetryIT 已知 flake) |
| Vitest 前端 | **106/106 pass** (16 test files) — 81 旧 + 25 新（12 service + 4 hooks + 9 共享） |
| FE build / tsc | clean |

新文件 / 改动：

- 9 个 typed service 模块: `services/dispatch/{vehicle,driver,carrier,deliveryOrder,deliveryStop,deliveryLine,exception,utilization,routing}.ts` + `index.ts` barrel + `_util.ts` 共享 `asData<T>` adapter
- 4 个 composite hook: `hooks/{useVehicleList,useDeliveryOrderDetail,useExceptionList,useDashboardData}.ts`
- 12 个 service test: `services/dispatch/__tests__/{vehicle,driver,deliveryOrder,exception,utilization}.test.ts`
- 4 个 hook test: `hooks/__tests__/{useVehicleList,useDeliveryOrderDetail,useExceptionList,useDashboardData}.test.ts`
- locale: 7 个 `menu.dispatch.*` 键已就位（无新增；pages 与 WMS 一致采用硬编码中文字符串）

---

**Generated by:** Task 22 of the Iteration 1.5 cross-cutting foundation plan
**Updated by:** Iteration 4.1 — Delivery dispatch (PR1-6 complete; finalization + docs)
**Plans:** [`docs/superpowers/plans/2026-09-18-iteration-1.5-cross-cutting-foundation.md`](./docs/superpowers/plans/2026-09-18-iteration-1.5-cross-cutting-foundation.md) · [`docs/superpowers/plans/2026-10-11-iteration-3.0-wms-vertical-slice.md`](./docs/superpowers/plans/2026-10-11-iteration-3.0-wms-vertical-slice.md) · `docs/superpowers/plans/2026-10-12-iteration-4.1-delivery-dispatch.md`