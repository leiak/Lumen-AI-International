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

想重置但**不想重建容器**（`down -v` 会停容器、可能要重新拉镜像），只重建 schema
就够了 —— 下一次 IT 启动时 Flyway 会从 V1 重跑全量迁移：

```bash
docker exec lumen-mysql mysql --user=lumen --password=lumen \
  -e "DROP DATABASE lumen; CREATE DATABASE lumen CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
```

一轮全量 IT 会攒下几百行 fixture（4.5B 实测残留 19 条 billing rule / 279 条 outbox /
38 张 DO / 53 个 driver）。4.x 早期那批 IT（`Billing*IT` / `MonthlyClosingIT` /
`CsConfigIT` / `DispatchMasterDataIT`）用的是**固定主键/唯一键**且不留 `@AfterEach`
清理，脏库上第二次跑就是一片 `DuplicateKeyException` / `Finance Rule ... already exists`
（4.5B 一次跑出 28 个），而这跟被测代码没关系。**跑全量前先重建 schema。**

⚠️ **`-Dtest` 排除不掉 IT**：`surefire`（单元）认 `-Dtest`，`failsafe`（IT）认
`-Dit.test`。写成 `-Dtest='!OutboxRetryIT'` 的话单元测试被排掉了，IT 却照跑不误 ——
而且**不会有任何报错提示**。排除 IT 必须用 `-Dit.test='!OutboxRetryIT'`。

⚠️ **`-pl X` 不带 `-am` 会测到 `~/.m2` 的旧 jar**：Maven 只把 `-pl` 列出的模块放进
reactor，兄弟模块（`lumen-dispatch` / `lumen-customer` 等）是从本地仓库解析的。
改了兄弟模块的代码却忘了 `install`，`lumen-bootstrap` 测的就是**改动前的实现** ——
4.5B 因此白跑了两轮 IT。**凡是跨模块改动，一律 `-am`。**

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

## 14. Iteration 4.2 — Customer Service SLA & Escalation (2026-10-15+)

Iteration 4.2 在 4.1 配送调度基础上落地客服中心（lumen-cs 纵切片）：V24 schema（4 张业务表）+ 3 级 SLA 升级（customer_service → cs_supervisor → cs_manager）+ 业务时间算法 + 跨模块 outbox 事件。本节以 §13 风格总结 6 个 PR 的最终交付。

### 14.1 业务目标

- **CS 工作台**: 客服可以查看分配给本角色 (customer_service / cs_supervisor / cs_manager) 的异常工单，看到升级链 (L1/L2/L3) 与剩余 SLA 时间。
- **SLA 配置化**: 每个租户可配置 (severity, level) → response/resolve 分钟数 + 升级目标 level；系统按 5 分钟轮询扫描，超 resolve 分钟即写 `t_sla_event (ESCALATED)` 并通过 `t_event_outbox` 通知 dispatch 端 `WmsDeliveryException.handler` 切换。
- **业务时间**: SlaCalculator 按 `t_business_calendar` (Mon-Fri 9-18) + `t_holiday` 计算实际业务分钟数；非工作时间 / 节假日整体跳过。
- **解耦**: lumen-cs 依赖 `lumen-common` / `lumen-extension` / `lumen-dispatch`（事件契约 `DeliveryExceptionStateChangedEvent` + 只读视图 `WmsDeliveryExceptionView` 通过此依赖拿到；反向依赖 lumen-cs 仍禁止）。跨模块事件流：`DeliveryExceptionStateChangedEvent` 进 cs；`ExceptionEscalatedEvent` 进 dispatch。

### 14.2 模块与表结构 (V24 migration + 2 seeds)

新增 Maven 子模块 `lumen-cs`（第 9 个业务子模块，与 lumen-dispatch 同级），4 张业务表：

| 表 | 行 / 列 | 用途 |
|----|---------|------|
| `t_sla_config` | 配置 | `(tenant_id, severity, level)` 唯一；`response_minutes` / `resolve_minutes` / `escalate_to_level` |
| `t_business_calendar` | 配置 | `(tenant_id, weekday, start_time)` 唯一；一天可多段（午休拆分）；CHECK `end_time > start_time` |
| `t_holiday` | 配置 | `(tenant_id, holiday_date)` 唯一；整体跳过业务时间计算 |
| `t_sla_event` | 日志 | 单条 SLA 事件；`UNIQUE KEY (exception_id, event_type, current_level)` 防并发重复升级 |

V24.2 seed：9 条默认 SLA 配置 (HIGH/MEDIUM/LOW × L1/L2/L3)。V24.3 seed：Mon-Fri 9-18 默认工作时段。

### 14.3 核心引擎

- **`SlaCalculator`** (`lumen-cs/.../calculator/SlaCalculator.java`) — 纯函数 `businessMinutes(cal, from, to)`：按天迭代 + 首末日按时间戳比例裁剪；节假日/非工作日整体跳过。12 个单测覆盖 full workday / across midnight / holiday / multi-range / DST / 30 天 / empty cal 等。
- **`EscalationService.scan()`** (`lumen-cs/.../service/EscalationServiceImpl.java`) — 5min `@Scheduled` 入口；对每个 OPEN/INVESTIGATING 工单：(1) 派生 severity (DAMAGE→HIGH, OVERTIME/REJECTED→MEDIUM, ADDR_ERR→LOW)；(2) 查 SLA config，若 `businessMinutes > resolveMinutes` 且 `escalate_to_level != null` → (3) 写 `t_sla_event` (UNIQUE KEY 幂等) + (4) 发 `ExceptionEscalatedEvent` outbox。
- **`SlaEventService.record()`** — 依赖 `t_sla_event.UNIQUE KEY` 防并发重复升级（`DuplicateKeyException` 被吞，返回既有 row）。

### 14.4 业务事件 (4.1.x SPI 复用)

| 事件方向 | 事件 | eventType | 模块边界 |
|---------|------|----------|---------|
| dispatch → cs | `DeliveryExceptionStateChangedEvent` | `delivery.exception.state_changed` | 4.1.x event；cs `DeliveryExceptionStateChangedEventListener` 消费 → 写 `L1_RESPONSE_DUE` / `L1_RESPONDED` / `RESOLVED` / `CANCELLED` (state→event_type 映射见 listener) |
| cs → dispatch | `ExceptionEscalatedEvent` | `exception.escalated` | 新增 cs event；`CsEventTypeRegistrar` 注册到 OutboxDispatcher；dispatch `ExceptionEscalatedEventListener` 消费 → 更新 `WmsDeliveryException.handler` |

### 14.5 前端页面（4 pages + 3 shared components）

`lumen-admin-web/src/pages/Cs/`：

| 路由 | 页面 | PR |
|------|------|----|
| `/cs/sla-configs` | SLA 配置列表 | PR2 |
| `/cs/calendar` | 业务时间列表 | PR2 |
| `/cs/holidays` | 节假日列表 | PR2 |
| `/cs/exceptions` | SLA 视角异常工单列表（嵌入 `EscalationChain` + `SlaCountdownTag`） | PR6 |

共享组件 (`lumen-admin-web/src/components/cs/`)：

- **`SlaCountdownTag`** — 颜色编码 SLA 倒计时（green/orange/red 阈值 80% / 100%）
- **`EscalationChain`** — L1/L2/L3 升级链可视化 + "已升级 N 次" 红色 tag
- **`SlaConfigForm`** — ProForm 化 SLA 配置编辑器（severity / level / responseMinutes / resolveMinutes / escalateToLevel）

2 个新 hook: `useCsExceptionList` (list + reload) + `useSlaConfigs` (read-only 配置查询)。

### 14.6 权限与角色 (V25 migration)

- **5 个新 `cs:*` 权限** (`sys_permission`)：`cs:sla:config` / `cs:calendar:config` / `cs:holiday:config` / `cs:exception:view` / `cs:exception:respond`
- **3 个新角色** (`sys_role`)：
  - `customer_service` — L1 工单处理（cs:exception:view + cs:exception:respond）
  - `cs_supervisor` — L2 工单处理（同 L1）
  - `cs_manager` — L3 工单处理 + 所有 cs: 配置权限
- **EXCEPTION_WORK_ORDER guard 更新** — `investigate/resolve` 角色门控从 `dispatch_admin` 切换到 `customer_service OR cs_supervisor OR cs_manager`；旧的 `dispatch:exception:*` 权限从 `dispatch_admin` 解绑。

### 14.7 集成测试 (12 新 IT)

`mvn -pl lumen-bootstrap verify -Dit.test='!OutboxRetryIT' -DskipFrontendBuild=true`：

| IT | 验证 |
|----|------|
| `CsConfigIT` (4) | SLA config / 业务时间 / 节假日 CRUD + 重复配置 409 |
| `CsEscalationIT` (5) | happy path / severity routing / escalation chain / idempotency on UNIQUE KEY |
| `CsCrossModuleIT` (3) | handler propagation / disconnected-listener resilience / V25 guard 拒绝 dispatch_admin 调查 |

PR2 + PR4 + PR5 = **4 + 5 + 3 = 12 IT**。

### 14.8 已知 follow-up（不在本切片范围内）

- **`loadCalendar()` stub** — EscalationServiceImpl 内临时返回空 calendar（4.2.0 简化版），完整实现需在 4.2.1 接入 BusinessCalendarService + HolidayService（已编写服务，但 PR4 scheduler 简化未消费）。
- **calendar / holiday 接 FE SlaCalculator 预览** — 后端 SlaCalculator 已就位；FE 没有 "现在距 SLA 还剩多少 min" 的实时预览组件，留作 4.2.1。
- **`pickUserForLevel()` stub** — 当前返回 `system_<tenant>_<level>` 格式占位；真实 user-role 表 + 按 level 选可用坐席留作 4.2.1。
- **`delivery.escalation` 实时推送** — 当前 ESCALATED event 仅消费在 dispatch 端更新 handler；未向 CS UI 推 websocket，CS 客户需要刷新页面看到升级链变化（4.2.1 / 4.3 接入 notification）。
- **Excel 导出 / 批量操作** — `/cs/exceptions` 当前只支持查看，不支持批量 escalate / batch re-assign；留作 4.2.1。

> **PR6 finalize (4.2 final wire):** `escalationStatus` follow-up 已落地 — `CsExceptionController` 现聚合 `ExceptionSlaView` (PR6 新服务 `ExceptionSlaAggregationService` 拼装 `severity` / `currentLevel` / `businessMinutesElapsed` / `resolveTargetMinutes` / `slaEvents` 全字段), 直接返 wire-ready DTO; severity filter 接受 FE `HIGH/MEDIUM/LOW` 反向映射到 `wms_delivery_exception.type` 集合; 分页参数 `current/size` 对齐 `DeliveryExceptionController`; perm 简化为 `cs:exception:view` (V25 seed).

### 14.9 总测试状态 (PR6 收尾)

| 维度 | 数 |
|------|----|
| Maven 后端单测 | **167+** pass（lumen-cs 55 + lumen-dispatch 21 + lumen-wms 91；CS 56 = 23 PR1..5 + 32 PR6 + 1 listener + CsConstants 等） |
| Maven 集成测试 | **85/85 pass** (12 cs + 21 dispatch + 41 wms + 11 其它基线，排除 1 条 OutboxRetryIT 已知 flake; PR6 新加 1 条 `CsExceptionListIT` end-to-end) |
| Vitest 前端 | **127/127 pass** (24 test files) — 106 旧 + 21 新（8 service + 5 hooks + 8 共享/cs） |
| FE build / tsc | clean |

新文件 / 改动 (PR6 部分)：

- 1 个 FE service 模块: `services/cs/exceptionView.ts` + `csExceptionViewService.list` + `listCsExceptions` 别名 + `computeEscalationStatus` 阈值 helper
- 3 个 FE 共享组件: `components/cs/{SlaCountdownTag, EscalationChain, SlaConfigForm}.tsx` + `index.ts` barrel
- 2 个 FE hook: `hooks/{useCsExceptionList, useSlaConfigs}.ts`
- 1 个 FE page: `pages/Cs/Exceptions/List.tsx` — ProTable 嵌入 `EscalationChain` + `SlaCountdownTag`
- 8 个新 Vitest (24 test files total now):
  - 3 component: `SlaCountdownTag.test.tsx` (3 cases) / `EscalationChain.test.tsx` (2 cases) / `SlaConfigForm.test.tsx` (1 case)
  - 3 service: `exceptionView.test.ts` (7 cases) / `slaConfig.test.ts` (1 case) / `businessCalendar.test.ts` (1 case)
  - 2 hook: `useCsExceptionList.test.ts` (3 cases) / `useSlaConfigs.test.ts` (3 cases)
- App.tsx + BasicLayout.tsx 路由 + 菜单新增 `/cs/exceptions`
- locale: 新增 `menu.cs.exceptions` 键 (zh-CN + en-US)

---

## §15 Iteration 4.3.x — 末端费用 + 财务联动 (lumen-finance 模块)

Iteration 4.3 在 4.1/4.2 业务流程基础上落地末端计费 + 财务单据链路：新建 `lumen-finance` 模块（计费引擎 + 单据基础），订阅 `lumen-extension` outbox 事件，事件驱动建 accrual → invoice，单币种 CNY、1 级审批、Aviator 沙箱求值。本节以 §13/§14 同款风格总结 3 个 sub-iter，本 spec 4.3.1 详写，4.3.2/4.3.3 见后续 spec。

### §15.1 4.3.1 — 计费引擎 + 单据基础

**Sub-iter scope**: 新建 `lumen-finance` Maven 子模块、6 张表（`t_billing_rule` / `t_billing_rule_item` / `t_billing_event_log` / `t_billing_accrual` / `t_billing_invoice` / `t_billing_audit`）、Aviator 表达式沙箱（5.4.1 + 9 变量白名单 + 100ms 超时）、1 级审批流（DRAFT → PENDING_L1 → L1_APPROVED → REJECTED）、2 个前端报表页（`/finance/invoices` + `/finance/rules`）。

**Multi-sub-iter 总览**: 4.3 全栈拆为 3 个 sub-iter，每个独立可交付：
- **4.3.1** （本 spec）— `lumen-finance` 骨架 + 计费引擎 + 单据基础 + RBAC + 报表页（16 endpoint + 11 前端 vitest）
- **4.3.2** — 多币种（CNY + USD + 汇率表）+ 税率分离 + 2 级财务复核（L1 财务运营 → L2 财务经理）+ 调账单
- **4.3.3** — 多 ERP Adapter（SAP / Oracle / Yonyou）+ 月度对账 + 4 个剩余报表页（按客户 / 司机 / 路线 / 异常维度）+ 历史数据回填 + notification push（CS 4.2.1 follow-up）

### §15.2 关键设计

- **事件驱动**: `lumen-finance` 订阅 `lumen-extension` outbox 事件（`delivery.order.completed` / `delivery.exception.resolved` / `outbound.shipped`），不反向依赖 `lumen-dispatch` / `lumen-wms`（解除循环依赖；4.1/4.2 沿用 4.1.x event SPI）。
- **表达式沙箱**: Aviator 5.4.1 + 9 变量白名单（`weight` / `volume` / `distance` / `quantity` / `serviceLevel` / `customerTier` / `routeCode` / `pickupZone` / `deliveryZone`） + 操作符受限（无 `import` / 无类反射 / 无 method-call）+ 100ms hard timeout（`Future.get(100, MILLIS)`） + 10 个逃逸测试（脚本注入 / 死循环 / 重类型转换 / 内存耗尽等）。
- **2 阶段计费**: `t_billing_accrual` 状态机 OPEN → LOCKED（finalize 由月度 cron 触发，4.3.3）→ `t_billing_invoice` DRAFT（手动开票）→ PENDING_L1 → L1_APPROVED → REJECTED（评论回 DRAFT）。
- **单币种 CNY**: 4.3.1 仅 CNY；4.3.2 引入 USD + `t_exchange_rate` + `t_tax_rate` + 单据币种字段。
- **幂等性**: `t_billing_event_log` 用 `(event_id)` UNIQUE KEY 防重复处理 outbox event（at-least-once → exactly-once-effective）。
- **审批可审计**: `t_billing_audit` 记录每次状态变更（前后 status + 操作人 + reason + timestamp），供 V28 加的 `error_msg` 字段补充失败原因。

### §15.3 模块与表结构 (V26 + V27 + V28 migrations)

新增 Maven 子模块 `lumen-finance`（第 10 个业务子模块，与 `lumen-cs` / `lumen-dispatch` 同级），6 张业务表：

| 表 | 行 / 列 | 用途 |
|----|---------|------|
| `t_billing_rule` | 配置 | `rule_code` 唯一；`active` (V26) 起算；`version` 乐观锁；`expression` (TEXT) Aviator 脚本；状态机 DRAFT/ACTIVE/INACTIVE/ARCHIVED |
| `t_billing_rule_item` | 子项 | `rule_id` FK；`variable_name` / `operator` / `threshold_value` / `fee_amount` 多条分段计费 |
| `t_billing_event_log` | 日志 | `(event_id)` UNIQUE 防重复；`rule_id` FK；`event_payload` (JSON) + `computed_amount` + `status` (PROCESSED/SKIPPED/FAILED) |
| `t_billing_accrual` | 计提 | `accrual_no` 业务编号唯一；`period_start/end`；`total_amount`；状态机 OPEN/LOCKED/REVERSED |
| `t_billing_invoice` | 发票 | `invoice_no` 业务编号唯一；FK accrual；`status` (DRAFT/PENDING_L1/L1_APPROVED/REJECTED)；V28 新增 `error_msg` 列记失败原因 |
| `t_billing_audit` | 审计 | 单条状态变更；`before_status` / `after_status` / `operator` / `reason` |

V26 主 schema + V27 补 5 条 `finance:*` 权限 + V28 给 `t_billing_invoice` 加 `error_msg VARCHAR(500)` 列（PR3 IT fixture 兼容）。

### §15.4 核心引擎

- **`BillingScriptEngine`** (`lumen-finance/.../engine/BillingScriptEngine.java`) — Aviator 5.4.1 包装；构造时 `addCustomFunction` 注册 9 变量 + 受限操作符；`evaluate(expression, env)` 包 `Future.get(100ms)` 抛 `BillingExpressionTimeoutException` (code 1002)；不命中白名单抛 `FinanceException` (code 1001)。
- **`BillingRuleService`** (`lumen-finance/.../service/BillingRuleService.java`) — 4 endpoint（CRUD + activate/disable + version bump）；`@Transactional` 保证 `t_billing_rule` 与 `t_billing_rule_item` 同写；`@PreAuthorize("hasAuthority('finance:rule:*')")` 4 个权限点。
- **`BillingEngineService`** (`lumen-finance/.../service/BillingEngineService.java`) — 订阅 `BusinessEventListener.processDeliveryOrder()`，按 `event.payload` 匹配 ACTIVE rules，逐条 evaluate → 写 `t_billing_event_log` (UNIQUE 幂等) + 累加到对应 accrual。
- **`AccrualService`** (`lumen-finance/.../service/AccrualService.java`) — `finalize(period)`（OPEN→LOCKED，月度 cron 用）+ `release(accrualId)` 锁后才允许开票。
- **`InvoiceService`** (`lumen-finance/.../service/InvoiceService.java`) — `submit` / `l1Approve` / `l1Reject`；reject 强制 `comment`；L1 通过后写 `t_billing_audit` + 改 status；并发审批用 `SELECT ... FOR UPDATE` 锁 invoice row。

### §15.5 业务事件（4.1.x SPI 复用 + 新增 4.3 finance event）

| 事件方向 | 事件 | eventType | 模块边界 |
|---------|------|----------|---------|
| dispatch → finance | `DeliveryOrderCompletedEvent` | `delivery.order.completed` | 4.1 event SPI；finance `BusinessEventListener.processDeliveryOrder()` 消费 → 触发计费引擎 |
| dispatch → finance | `DeliveryExceptionResolvedEvent` | `delivery.exception.resolved` | 4.1 event SPI；finance `BusinessEventListener.processExceptionResolution()` 消费 → 触发异常费用 |
| wms → finance | `OutboundShippedEvent` | `outbound.shipped` | 3.0 event；finance 消费 → 开仓单 → 计费起点 |
| finance (内) | `InvoiceApprovedEvent` | `invoice.approved` | 4.3.1 新增；finance 内部 audit/snapshot 用；4.3.3 推 ERP 用 |

### §15.6 前端页面（2 pages + 2 shared components）

`lumen-admin-web/src/pages/Finance/`：

| 路由 | 页面 | 用途 | PR |
|------|------|------|----|
| `/finance/invoices` | 发票列表 | ProTable + 状态 tag + submit/l1Approve/l1Reject 操作 + 按 period/customer 筛选 | PR4 |
| `/finance/rules` | 计费规则列表 | ProTable + version display + activate/disable/create + expression 编辑器 | PR4 |

共享组件 (`lumen-admin-web/src/components/finance/`)：

- **`FinanceInvoiceList`** — 列表 + 审批按钮（按 RBAC 动态灰）+ reject 弹窗（带 `comment` 必填校验）
- **`FinanceRuleList`** — 列表 + ACTIVE/DRAFT/INACTIVE 状态切换 + Aviator 表达式编辑（输入即校验，不合法红框提示）

FE service 模块 (`lumen-admin-web/src/services/finance/`)：

- **`financeInvoiceService`** — `list(page, filter)` / `submit(id)` / `l1Approve(id)` / `l1Reject(id, comment)` / `get(id)`
- **`financeRuleService`** — `list(page, filter)` / `get(id)` / `create(payload)` / `activate(id)` / `disable(id)`

### §15.7 权限与角色 (V26 + V27 migrations)

- **6 个新 `finance:*` 权限** (`sys_permission`)：
  - `finance:rule:view` / `finance:rule:edit` / `finance:rule:activate` (rule 三件套)
  - `finance:invoice:view` / `finance:invoice:submit` / `finance:invoice:l1_approve` (invoice 三件套 + 含 reject)
  - 另：V27 seed 加 `finance:report:view` 占位，4.3.3 启用
- **2 个新角色** (`sys_role`)：
  - `finance_ops` — 日常运营（finance:rule:view + finance:invoice:view + finance:invoice:submit）
  - `finance_reviewer` — 1 级复核（finance:invoice:view + finance:invoice:l1_approve）
- 与 `customer_service` / `cs_manager` / `dispatch_admin` 等角色互不干扰；4.3.2 加 `finance_manager` (L2) + `finance_accountant` (会计)。

### §15.8 集成测试 (16 IT in lumen-bootstrap)

`mvn -pl lumen-bootstrap verify -Dit.test='*Billing*IT' -DskipFrontendBuild=true`：

| IT | 验证 | 阶段 |
|----|------|------|
| `BillingRuleActivationIT` | rule DRAFT→ACTIVE 状态机 + 乐观锁冲突 | PR3-T3.1 |
| `BillingRuleAuditIT` | rule 写 audit row + 不变量校验 | PR3-T3.1 |
| `BillingEventListenerIT` | outbox event → `t_billing_event_log` + accrual 累加 | PR3-T3.2 |
| `BillingAccrualFinalizeIT` | accrual OPEN→LOCKED + LOCKED 才能 invoice | PR3-T3.3 |
| `BillingIdempotencyIT` | 同 event_id 重复处理只写一条 log (UNIQUE KEY) | PR3-T3.4 |
| `BillingInvoiceApprovalIT` | 1 级审批 happy path + reject comment 必填 + 并发锁 | PR3-T3.5 |
| `BillingPermissionIT` | 角色门控 6 个权限（view / submit / l1_approve） | PR3-T3.5 |

PR3 = **7 IT** 文件（共 ~14 IT method）。沙箱逃逸 11 个 fixture 是 §15.10 PR2 提及 `BillingExpressionSandboxTest` 单元测试（`lumen-finance/src/test`），不走 bootstrap IT 通道。

### §15.9 已知 follow-up（不在本切片范围内，留 4.3.2/4.3.3）

- **多币种 / 汇率 / 税率分离** — 4.3.2 加 USD + `t_exchange_rate` + `t_tax_rate` 表 + `t_billing_invoice.currency` 字段
- **2 级财务复核** — 4.3.2 加 L2 `finance_manager`，L1_APPROVED → PENDING_L2 → L2_APPROVED
- **调账单** — 4.3.2 加 `t_billing_adjustment` 表（credit/debit note）+ revision_no 链
- **多 ERP Adapter** — 4.3.3 加 `lumen-erp-adapter` 模块（SAP / Oracle / Yonyou 三套）；当前 invoice.l1_approved 仅写 `t_billing_audit`
- **月度对账 UI** — 4.3.3 加 4 个剩余报表页（customer / driver / route / exception）+ 导出 Excel
- **notification push** — 4.3.3 接 `lumen-extension` 推 invoice 状态变化到 CS 工作台
- **历史数据回填** — 4.3.3 提供 batch 入口补 4.1/4.2 已产生但未计费的 delivery order
- **Aviator 表达式 IDE 增强** — 当前 FE 只有合法性校验（红框）；4.3.3 加 Monaco editor + 9 变量自动补全 + 表达式 unit-test 沙箱

### §15.10 总测试状态 (4.3.1 收尾)

| 维度 | 数 | 增量 (vs 4.2 PR6) |
|------|----|---------------------|
| Maven 后端单测 (`lumen-finance`) | **67 pass** | +67 (本 iteration 新增；另 1 mapper Testcontainers 测试需要 Docker full context，CI 跑通过) |
| Maven 后端单测 (累计) | **234+** pass | +67 (lumen-finance) |
| Maven 集成测试 (`lumen-bootstrap` *IT) | **106/106 pass** (34 IT 文件) | +16 Billing 相关 (含 7 Billing*IT 文件 + Audit/Idempotency/Activation/Permission 等) |
| Maven 集成测试 (`lumen-wms` MasterDataIT) | **23/23 pass** | — |
| Maven 集成测试 (累计) | **129/129 pass** | +12 IT (4.3.1 新增 Billing*IT 7 + 其他 5) |
| Vitest 前端 | **138/138 pass** (40 test files) | +11 (4 文件 = 2 finance component + 2 finance service = 5+2+2+2 = 11 cases) |
| FE build / tsc | clean | — |

新文件 / 改动 (4.3.1 全部 PR)：

- 1 个新 Maven 子模块 `lumen-finance`（骨架 + 6 entities + 6 mappers + 4 services + 4 controllers + 67 单元测试 + 11 sandbox escape fixtures）
- 16 BE endpoints (BillingRuleController×4 + BillingAccrualController×4 + BillingInvoiceController×5 + BillingEventLogController×3)
- 3 个 Flyway 迁移：V26（6 表 + 6 权限 + 2 角色 + state transitions）+ V27（finance:report:view 权限补 seed）+ V28（`t_billing_invoice.error_msg` 列）
- 2 个 FE service 模块：`financeInvoiceService` + `financeRuleService`
- 2 个 FE 共享组件：`FinanceInvoiceList` + `FinanceRuleList`
- 2 个 FE 页面：`/finance/invoices` + `/finance/rules`
- 11 new Vitest (4 文件 = 2 component + 2 service = 5+2+2+2 = 11 cases)
- App.tsx + BasicLayout.tsx 路由 + 菜单新增 `/finance/*` 两项
- locale: 新增 `menu.finance.{invoices,rules}` 键 (zh-CN + en-US)
- **iteration 完整 deliverables 共 19 commits**（4 PR 1 spec 14 代码 4 doc）

> **4.3.1 finalize**: §15.1 spec 范围内全部交付 — 单币种 CNY / 1 级审批 / 6 表 / 16 endpoint / Aviator 沙箱 / 跨模块事件订阅 / RBAC 2 角色 6 权限 / 2 FE 报表页。4.3.2 (多币种/2 级/调账单) + 4.3.3 (ERP/对账/报表 UI) 各自独立 spec，4.3 iteration 在 4.3.3 收尾时一并关版。

### §15.11 4.3.2 — 多币种 + L2 复核 + 调账单（增量）

**Sub-iter scope**: V29–V30 增加 `t_exchange_rate` / `t_tax_rate` / `t_billing_adjustment` 3 张表 + `t_billing_invoice.currency` / `tax_rate` / `l2_approver_id` 列 + 1 个角色 (`finance_manager`) + 10 个新权限 + 3 个新 controller + 2 个 invoice L2 endpoint + 6 个新 error code（FX 1001–1003 / TAX 2001–2003）+ 多币种 USD 同步（exchangerate.host 每日 cron）+ L2 分离职责（L1 approver ≠ L2 approver）+ 调账单 CREDIT/DEBIT 工作流（DRAFT → PENDING_L1 → PENDING_L2 → L2_APPROVED → POSTED）+ 3 个新 FE 页面 (`/finance/rates` + `/finance/tax-rates` + `/finance/adjustments`) + Invoice Detail 改造嵌入 `<L2ApprovalButtons />` 共享组件 + 6 个新 Vitest（2 rate service + 2 adjustment service + 2 L2 button + 1 currency util + 1 tax util）。

**关键设计要点**:

- **多币种**: CNY + USD 双币种；`t_exchange_rate` 存历史报价 / `rateDate` 唯一 / `source ∈ {MANUAL, AUTO}`。`FinanceExchangeRateSyncJob` `@Scheduled` 每日 02:00 调 exchangerate.host 拉 USD→CNY 入库。
- **税率分离**: `t_tax_rate` 存 (country, business_type) → rate；invoice `tax_rate_id` FK + 落 `amount_tax` / `amount_net` 两列；`splitGross()` pure-function helper 在 FE 预览（§15.4 currencyFormatter / taxCalculator）。
- **L2 分离职责**: `t_billing_invoice.l1_approver_id` 不允许 = `l2_approver_id`；BE service 校验 + BE R<T> 错误码 `FINANCE_L2_DUPLICATE_APPROVER` (2001)。FE `<L2ApprovalButtons />` 通过 props `l1ApproverId` + `currentUserId` UI-side guard 隐藏按钮（3 个 guard：status===PENDING_L2 + l1 approver ≠ current user + perm）；`<L2ApprovalButtons>` 是 §15.6 新增的共享组件，list/detail 共用。
- **调账单 workflow**: `t_billing_adjustment` 状态机 DRAFT → PENDING_L1 → PENDING_L2 → L2_APPROVED → POSTED（与 invoice 解耦，`source_invoice_id` 弱关联）；`adjustment_type ∈ {CREDIT, DEBIT}`；reject 在 L1/L2 端点都需 `comment`；CS §14 SLA 工单升级的 `delivery.exception.state_changed` outbox event 触发 finance 入账的链路由 4.3.2 4 状态机过渡 → 4.3.3 完成收尾。
- **RBAC (V30 seed)**: `finance_manager` 角色绑 10 个权限 (`finance:rate:view/edit` + `finance:taxrate:view/edit` + `finance:adjustment:view/create/submit/l1_approve/l2_approve/mark-posted`)；`finance_ops` 保留 view + submit，`finance_reviewer` 保留 l1_approve；4.3.1 角色不破坏。
- **测试覆盖**: 25 单元 (lumen-finance 新增 splitter/exchange/permission 路径) + 6 IT (`BillingRateSyncIT` `BillingTaxRateIT` `BillingAdjustmentIT` `BillingL2SoDIT` `BillingCurrencyRoundingIT` `BillingAdjustmentPostingIT`) + 6 Vitest (本 PR 添加)。

---

### §15.12 4.4 — 财务月结 + 对账 + 报表 (2026-10-17)

**范围**: 3 PR / 24 commit / 16 endpoint / 1 migration / 3 entity / 4 service / 1 listener / 1 state machine

**核心增量**:

- **V31 migration**: 3 张新表 (`t_payment_record` / `t_monthly_closing` / `t_reconciliation`) + 月结状态机 (OPEN → IN_PROGRESS → CLOSED 含 CLOSED → IN_PROGRESS reopen) + 4 个新权限 (id 150–153): `finance:payment:view` / `finance:payment:edit` / `finance:closing:manage` / `finance:report:export` (注: `BillingReportController` 实际用 `finance:report:view`, 由 `:export` 重命名; reconciliation 权限只用于 `@PreAuthorize` 但 V31 未 seed → RBAC 待补)
- **4 个新 service**:
  - `PaymentRecordService` — CRUD + CSV 导入 (按 tenant 隔离，全字段校验)
  - `MonthlyClosingService` — `start(period)` 锁定 accrual 快照 → `close()` 期间锁 → `reopen()` (限 SUPER_ADMIN/finance_manager)
  - `ReconciliationService` — `run(period)` 对账 → variance 自动生成调账单 (CREDIT/DEBIT) + 走 L1/L2 审批
  - `ReportService` — POI 即时生成 Excel (3 个: 汇总 / 明细 / 差异)，含多 sheet + 表头 + 数字格式 + autoSize column
- **1 个异步 listener**: `ClosingEventListener` (`@Async("outboxExecutor") @EventListener`) — 订阅 `ClosingAffectedEvent` (由 `AdjustmentServiceImpl.markPosted()` 在 L2_APPROVED → POSTED 后发布) → 触发 `MonthlyClosingService.recalculateSnapshot()` → 异步重算月结快照金额
- **4 个新 controller + 16 endpoint**:
  - `BillingPaymentRecordController` (4) — `GET /api/v1/finance/payment-records` (list 分页) / `POST /api/v1/finance/payment-records` (single create) / `POST /api/v1/finance/payment-records/import` (CSV upload) / `GET /api/v1/finance/payment-records/{id}` (detail)
  - `BillingClosingController` (5) — `GET /api/v1/finance/closing` (list period) / `GET /api/v1/finance/closing/{period}` (snapshot detail) / `POST /api/v1/finance/closing/start` / `POST /api/v1/finance/closing/close` / `POST /api/v1/finance/closing/reopen`
  - `BillingReconciliationController` (4) — `POST /api/v1/finance/reconciliations/run` / `GET /api/v1/finance/reconciliations/list` (list variance) / `POST /api/v1/finance/reconciliations/{id}/resolve` (标记 RESOLVED) / `POST /api/v1/finance/reconciliations/{id}/waive` (豁免差异)
  - `BillingReportController` (3, GET 返回二进制 `application/vnd.openxmlformats-officedocument.spreadsheetml.sheet`) — `/api/v1/finance/reports/summary` / `/api/v1/finance/reports/detail` / `/api/v1/finance/reports/variance`
- **4 个新 FE 页 + 4 个新组件 + 4 个 service 模块** (无新 shared hook):
  - `/finance/closing` — `MonthlyClosingList` (按 period + status 筛选 + start/close/reopen 按钮 + 状态机 perm guard)
  - `/finance/payments` — `PaymentRecordList` (ProTable + 手工新增 Modal + CSV upload 上传 + 错误行 download)
  - `/finance/reconciliation` — `ReconciliationList` (variance 表格 + approve/waive 操作)
  - `/finance/reports` — `ReportList` (3 个 tab: summary / detail / variance，含日期选择器 + 点击下载按钮)

**业务闭环**:

1. **录入实收/实付**: 财务运营 (`finance_ops`) 在 `/finance/payments` 录入实收实付 (手工单条或 CSV 批量上传) → 写 `t_payment_record` (带 tenantId 隔离 + 重复检测)
2. **月底 start 月结**: 财务经理 (`finance_manager`) `POST /closing/start` → 锁定当月 accrual 快照 + 写 `t_monthly_closing (OPEN → IN_PROGRESS)` → 期间内所有 payment / invoice 修改走 audit
3. **close 锁定**: 同角色 `POST /closing/close` → status `IN_PROGRESS → CLOSED` → 期间全锁；任何 reopening 需 `finance_manager` + reason 留痕 (CLOSED → IN_PROGRESS via `reopen`)
4. **调账过账 (POSTED)**: 对账生成或手工调整的调账单 POSTED → 异步 `ClosingEventListener` 触发月结快照重算 → 期间金额实时反映
5. **跑对账**: `POST /reconciliation/run` → 比对 invoice.actual vs payment_record.sum → variance != 0 自动生成调账单 (CREDIT/DEBIT) → 走 L1 (`finance_reviewer`) / L2 (`finance_manager`) 审批
6. **导出 Excel 报表**: `GET /reports/{summary|detail|variance}` → POI 即时生成 `.xlsx` 流式返回 → FE 触发浏览器下载

**PR 拆分 (实际 commit count)**:

| PR | 内容 | commits |
|----|------|--------:|
| PR1 | V31 + `PaymentRecordService` + `MonthlyClosingService` + PaymentRecord Controller (4) + Closing Controller (5) + 10 单元 + 1 IT (`PaymentRecordCsvImportIT`) | **11** |
| PR2 | `ReconciliationService` + `ReportService` + `ClosingEventListener` + Reconciliation Controller (4) + Report Controller (3) + 10 单元 + 2 IT (`MonthlyClosingIT` 2 tests + `ReconciliationIT` 1 test = 3 cases; ReportService 仅 5 unit tests 无 IT) | **7** |
| PR3 | 4 FE service 模块 + 4 FE 新组件 + 4 FE page + 4 Vitest 文件 + README §15.12 (本次) | **6** |
| **合计** | | **24** |

**关键 bug 修复 (PR2 期间发现并补齐)**:

- **`ClosingEventListener` 缺 `TenantContext`**: `@Async` 跑在 `outboxExecutor` 线程，ThreadLocal 空 → mapper 走多租户拦截器时报 null tenantId → cross-tenant 全表读。修复: listener 入口 `TenantContext.set(event.getTenantId())` + try/finally `TenantContext.clear()` (PR2 IT `MonthlyClosingIT#eventListener_recalculatesSnapshotOnAdjustmentPosted` 发现并复现)
- **`ReportService` 缺 `tenantId` filter**: `reportService.export(period)` 直接 `select * from t_billing_invoice where period=?` 无 tenant 条件 → 跨租户数据泄露。修复: 所有 report 查询 method 加 `tenant_id` 谓词 + `TenantContext.getTenantId()` assert 非空 (PR2 code review `findbugs:tenant-leak`, fix commit 5e5b2d5)
- **`ReconciliationService.resolve/waive` 缺 `tenantId` 校验**: service 层 `updateById` 前未校验 `entity.tenantId == TenantContext.getTenantId()` → 攻击者可改任意租户的 reconciliation。修复: `resolve()` / `waive()` 入口 assert + `WHERE id=? AND tenant_id=?` 谓词防御 (PR2 code review B1 critical)
- **历史遗留**: `BillingRuleMapperTest` (4.3.1) 是 Testcontainers full-context mapper 测试，在 full-suite 跑时偶发数据隔离冲突 → 临时方案是 `-Dtest='!BillingRuleMapperTest'`，正式 fix 留 4.4.1 切 H2/Testcontainers 单测模式

**累计 (4.3.0 → 4.3.1 → 4.3.2 → 4.4)**:

| 维度 | 4.3.0 | 4.3.1 | 4.3.2 | 4.4 | 增量 |
|------|------:|------:|------:|------:|------:|
| lumen-finance 单元 | 0 | 67 | 92 | 112 | +20 (PR1 10 + PR2 10) |
| BE IT (lumen-bootstrap) | 0 | 16 | 22 | 26 (注: 实际 119→122) | +3 (PR2 新加 3 条) |
| Vitest (lumen-admin-web) | 0 | 129 | 146 | 150 | +4 |
| Endpoint | 0 | 16 | 34 | 50 | +16 |
| Migrations | 0 | 4 | 2 | 1 | +1 (V31) |
| Entities | 0 | 9 | 14 | 17 | +3 (BillingPaymentRecord → t_payment_record, BillingMonthlyClosing → t_monthly_closing, BillingReconciliation → t_reconciliation) |
| Services | 0 | 7 | 14 | 18 | +4 (PaymentRecord + MonthlyClosing + Reconciliation + Report) |
| Listeners | 0 | 1 | 1 | 2 | +1 (ClosingEventListener) |
| 状态机数 | 0 | 4 | 5 | 6 | +1 (monthly_closing) |

> **注**: 表头 BE IT 行实际累计为 119 → 122 (+3 实际 PR2 新增 3 条)，与计划书 +4 差异 1 条 (`BillingAdjustmentPostingIT` 的 variance 变体在 4.3.2 已合并，本迭代仅复用；新 PR2 仅 3 条新 IT 文件)。其它维度 (单元 +20 / Vitest +4 / Endpoint +16 / Migration +1 / Entities +3 / Services +4 / Listeners +1 / 状态机 +1) 与计划书完全一致。

**Known follow-up (留 4.4.1 / 5.x)**:

- **POI 流式优化**: 当前 `ReportService.export*` 全量加载到内存后写 sheet，超过 50k 行有 OOM 风险 → 4.4.1 切 `SXSSFWorkbook` 流式写出
- **ClosingEventListener 失败回滚**: 当前 listener 重算快照失败仅 log，无重试/dead-letter → 4.4.1 接 `OutboxRetry` 同款 retry + dead-letter
- **`BillingRuleMapperTest` 隔离**: 现 `mvn verify` full-suite 偶发冲突，需 `-Dtest='!BillingRuleMapperTest'` workaround → 4.4.1 改 Testcontainers `@Transactional` 自动 rollback 修
- **CSV 导入失败行收集**: 当前 import 接口失败只返 OK 或抛 400，缺失败行明细 download → 5.x 加 `error-rows.xlsx` 同包返回
- **报表多 ERP 维度**: 4.4 仅 invoice / payment / variance 三视角，缺 customer / driver / route / exception 维度 → 5.x 接 4.3.3 计划

### §15.13 4.5A — 客户门户起点 (2026-10-18)

**范围**: 4 PR / 30 commit / 10 endpoint / 3 migration / 0 新 entity / 1 新模块 / 1 新前端工程 / 42 单元 / 13 IT / 18 Vitest

**核心增量**:

- **3 个 migration**:
  - **V33** (`V33__customer_portal.sql`) — 1 新角色 `customer` (id 14) + 7 新权限 (id 159..165: `customer:track:view` / `customer:profile:view` / `customer:profile:edit` 等) + 7 role-perm 绑定 (id 321..327) + 1 新表 `t_customer_user_link` (1 user ↔ N customer 多对多, 唯一键 `(tenant_id, user_id, customer_id, deleted)`, 索引 `idx_link_user` / `idx_link_customer`)
  - **V34** (`V34__delivery_order_customer.sql`) — `wms_delivery_order` 加 `customer_id` (BIGINT NULL) + 索引 `idx_wms_delivery_order_tenant_customer` + 从 `wms_outbound_note` backfill 既有数据 — 客户门户 list 走 `WHERE tenant_id = ? AND customer_id = ?`
  - **V35** (`V35__delivery_pod.sql`) — 拆出 `wms_delivery_pod` 表 (POD 之前挤在 `wms_delivery_order.sign_photo_url` 字段) — 客户门户 `/pod` 端点查独立表，customer_id 同步落表
- **JWT `aud` claim 扩展** (`lumen-common/security/JwtUtil` + `lumen-rbac/security/JwtAuthenticationFilter`):
  - `JwtUtil.issueToken(...)` 加 `aud` (默认 `admin`, 向后兼容) + `active_customer_id` (portal 时存当前 active customer id)
  - `JwtAuthenticationFilter` 按 path 路由: `/api/customer/*` 要 `aud=portal`; `/api/admin/*` 要 `aud=admin`; 缺 `aud` 或 mismatch → 401
- **`lumen-customer` 新模块骨架** (薄门面, 调既有 `lumen-dispatch` / `lumen-wms` 加重载方法):
  - `CustomerContext` (ThreadLocal) + `CustomerContextFilter` (拦截器, 从 JWT claim 注入 customerId 到 ThreadLocal)
  - `PortalAuthService` — login / selectCustomer / refresh / me (调 `UserMapper` + `CustomerUserLinkMapper`)
  - `DeliveryOrderService.pageForCustomer(tenantId, customerId, q)` + `getDetailForCustomer(...)` 重载 (强制 `WHERE customer_id = ?`, 且 `getDetailForCustomer` 加 defense-in-depth tenant check 防跨租户绕过)
  - `PodService.getForCustomer(tenantId, customerId, doId)` 重载 (从 `wms_delivery_pod` 表读, 强制 customerId 范围)
- **3 个 controller + 10 endpoint**:
  - `PortalAuthController` (4) — `@RequestMapping("/api/auth/portal")` — `POST /login` (公开) / `POST /select-customer` (portal token) / `POST /refresh` (refresh token) / `GET /me` (portal token)
  - `PortalTrackController` (3) — `@RequestMapping("/api/customer/track")` + `@PreAuthorize("hasAuthority('customer:track:view')")` — `GET /` (list 分页) / `GET /{doId}` (detail + timeline) / `GET /{doId}/pod` (POD 预览, 含 ownership guard 防跨客户 POD 泄漏)
  - `PortalCommonController` (3) — `@RequestMapping("/api/customer")` — `GET /profile` (`customer:profile:view`) / `PUT /profile` (`customer:profile:edit`, 4.5A stub) / `GET /choices` (`customer:track:view`, 4.5A 仅返 active)
- **`lumen-customer-web` 新前端工程** (Vite 5 + React 18 + TS 5 + Antd 5 + ProComponents 2.7 + zustand 4 + Vitest 1):
  - 6 页: `/login` (公开) / `/select-customer` (需 portal token) / `/track` (ProTable 列表 + 过滤 + 分页) / `/track/:doId` (详情 + Timeline + POD 预览) / `/profile` (我的资料只读 + 修改表单) / `/404` (兜底)
  - 2 layout: `PortalLayout` (顶栏 + CustomerSwitcher) + `PublicLayout` (登录/选客户页用)
  - 3 组件: `CustomerSwitcher` (顶栏客户切换, 4.5A 隐藏但保留 UI) / `PodPreview` (签名照预览) / `StatusTag` (DO 状态徽章)
  - 3 service + 2 store + http (axios + interceptor 401 → 跳登录) + i18n (zh-CN + en-US)

**业务闭环**:

1. **登录**: 商家访问 `lumen-customer-web/login` → `POST /api/auth/portal/login` 账号密码 → 单客户直接返 portal token + refresh; 多客户返 `choices` 列表, 前端跳 `/select-customer`
2. **选客户**: 多客户商家在 `/select-customer` 选一个 → `POST /select-customer` (新 `PortalSelectCustomerRequest` DTO 携 userId + customerId) → 返新 portal token, JWT 含 `active_customer_id`
3. **查货**: 进 `/track` → `GET /api/customer/track?page=1&size=20` → `DeliveryOrderService.pageForCustomer(tenantId, customerId, q)` → 强制 `WHERE customer_id = ?` → 列表
4. **详情 + 轨迹**: 点 DO 进 `/track/:doId` → `GET /api/customer/track/{doId}` → `getDetailForCustomer` 校验 customerId + tenantId 双 guard → Timeline 组件渲染
5. **POD 预览**: `/track/:doId/pod` → `GET /api/customer/track/{doId}/pod` → controller 先调 `getDetailForCustomer` 校验 ownership (防跨客户 POD 泄漏) → 再调 `PodService.getForCustomer` 从 `wms_delivery_pod` 表读签名照 URL
6. **改资料**: `/profile` → `GET /api/customer/profile` (只读) + `PUT /api/customer/profile` (4.5A stub, 返当前值, 4.5B 落库)

**PR 拆分 (实际 commit count)**:

| PR | 内容 | commits |
|----|------|--------:|
| PR1 (4.5A.1) | V33 + JwtUtil `aud` claim + JwtAuthenticationFilter audience 路由 + 单元 (JwtUtil 2 + JwtAuthFilter 4) | **7** |
| PR2 (4.5A.2) | `lumen-customer` 模块骨架 + CustomerContext/Filter + 3 controller + 8 DTO + PortalAuthService + PortalErrorCode + 16 单元 (CustomerContext 5 + PortalAuthService 10 + CustomerException 2 + PortalAuthControllerSecurity 2 + PortalTrackController 3 + 既有 TrackQuery 3 + ...) | **12** |
| PR3 (4.5A.3) | V34 + V35 + `DeliveryOrderService` 2 重载真实 impl + `PodService.getForCustomer` 真实 impl + 9 单元 (DeliveryOrder 6 + PodService 3) + 13 IT (PortalAuthIT 8 + PortalTrackIT 5) | **6** |
| PR4 (4.5A.4) | `lumen-customer-web` 工程 + 6 页 + 2 layout + 3 组件 + 3 service + 2 store + i18n + 18 Vitest + T3.5 fix 5 个 portal 5 单元 (Pod ownership guard 2 + PortalTrackController +3) | **5** |
| **合计** | | **30** |

**关键 bug 修复 (T3.5 spec review / 集成测试发现并补齐)**:

- **`X-Tenant-Id` 缺省 fallback**: `PortalAuthController` 3 个 handler (login / refresh / me) 假定 header `X-Tenant-Id` 必传, 但 4.5A 单租户门户场景下前端默认无此 header → 多客户商家登录后 tenant context 空 → 跨租户数据泄漏。修复: 每个 handler 首行 `ensureDefaultTenant()` (4.5A 简版默认 tenant=1, 4.5B 多租户扩); `@ModelAttribute` 不可靠, 用 `@RequestHeader(required=false)` + 手动 fallback
- **`select-customer` 端点 `userId` 来源**: `CustomerContextFilter` 只覆盖 `/api/customer/*`, 但 `/api/auth/portal/select-customer` 路径无 `aud=portal` Bearer token (用户在选客户阶段还没 active customer) → 从 `CustomerContext.userId` 读会 NPE。修复: 新 `PortalSelectCustomerRequest(userId, customerId)` DTO, controller 从 DTO body 读 userId; 同时 `/api/auth/portal/select-customer` 加进 `SecurityConfig.permitAll` (与 `/login` `/refresh` 一档)
- **`refresh` 端点 sub claim 解析**: JJWT 0.12 默认 String 序列化所有 claim, `claims.get("sub", Long.class)` 抛 `RequiredTypeException` → 500。修复: `claims.getSubject() + Long.parseLong` 解析 sub; tid 用 `claims.get("tid") instanceof Number/String` 分支判; roles/perms 仍 `claims.get(_, List.class)`
- **POD ownership guard 闭环**: `PodServiceImpl.getForCustomer` 此前依赖 "controller-layer guard", 但 `PortalTrackController.pod()` 此前未实现 ownership check → 跨客户 POD 数据泄漏 (T3.2 spec review C1 critical)。修复: `pod()` 先调 `deliveryOrderService.getDetailForCustomer` 校验 doId 属当前 customer, 不属则 404, 再调 `PodService.getForCustomer`
- **`DeliveryOrderServiceCustomerOverloadTest` 跨租户 404**: `getDetailForCustomer` 防御纵深 — 既校验 customerId 也校验 tenantId, 拒绝跨租户绕过 (4.5A.3 PR commit 242304d 加 1 测试覆盖)
- **`CustomerException` 继承 `AuthenticationException`**: 早期版本 `CustomerException extends RuntimeException` → Spring Security 入口点 (AuthenticationEntryPoint) 不识别 → 401 返 500。修复: `CustomerException extends AuthenticationException` (4.5A.1 fix commit 1aaf9b2 与 `AuthException` 对齐)
- **`refresh` 保留 tid/roles/perms**: 早期版本 `refresh` 硬编码 `tid=1L`, 意味着 tenants ≠ 1 的用户 refresh 后丢 tenant context → 跨租户串扰。修复: refresh 从原 claims 提取 sub/tid/roles/perms 重新签发, 仅在 claims 缺时 fallback 默认值 (4.5A.2 fix commit af3e6d3)
- **TrackQuery compact-constructor clamping**: `TrackQuery(page, size)` 构造时 page<1 → 1, size<1 → 20, size>100 → 100 (4.5A.2 fix commit af3e6d3 加 3 测试, 防前端传 0 / 10000 打爆后端)
- **`PortalAuthController` defensive DTO converters**: `asLocalDateTime()` / `asBigDecimal()` 早期对 unparseable input (e.g. "null", "2026-13-99") 抛 → 500。修复: try/catch + log warning + return null; 加 `instanceof` fast-paths (Number/BigDecimal/LocalDateTime/Instant/Date); filter null records out of items stream 防 `[null, ...]` JSON 数组

**累计 (4.3.0 → 4.3.1 → 4.3.2 → 4.4 → 4.5A)**:

| 维度 | 4.3.0 | 4.3.1 | 4.3.2 | 4.4 | 4.5A | 增量 |
|------|------:|------:|------:|------:|------:|------:|
| 单元 (全模块累计) | 0 | ~150 | ~250 | ~315 | ~360 | +42 (JwtUtil 2 + JwtAuthFilter 4 + TrackQuery 3 + CustomerContext 5 + PortalAuthService 10 + CustomerException 2 + PortalAuthControllerSecurity 2 + PortalTrackController 5 + DeliveryOrder 6 + PodService 3) |
| BE IT (lumen-bootstrap) | 0 | 16 | 22 | 122 | 135 | +13 (PortalAuthIT 8 + PortalTrackIT 5) |
| Vitest (lumen-admin-web) | 0 | 129 | 146 | 150 | 150 | 0 (admin-web 未动) |
| Vitest (lumen-customer-web) | 0 | 0 | 0 | 0 | 18 | +18 (新工程: Login 4 / SelectCustomer 2 / TrackList 5 / TrackDetail 4 / Profile 3) |
| Endpoint | 0 | 16 | 34 | 50 | 60 | +10 (PortalAuth 4 + PortalTrack 3 + PortalCommon 3) |
| Migrations | 0 | 4 | 2 | 1 | 3 | +3 (V33 + V34 + V35) |
| Entities | 0 | 9 | 14 | 17 | 17 | 0 (复用既有 DeliveryOrder / Pod + 新 mapper view) |
| Services | 0 | 7 | 14 | 18 | 19 | +1 (`PortalAuthService` 全新; DeliveryOrder / Pod 加重载不计数) |
| 新模块 | 0 | 0 | 0 | 0 | 1 | +1 (`lumen-customer`) |
| 新前端工程 | 0 | 0 | 0 | 0 | 1 | +1 (`lumen-customer-web`) |

> **注**: 4.5A 末态 endpoint 累计口径含 portal 10 个, 既不在 `lumen-finance` / `lumen-wms` / `lumen-dispatch` controller 计数路径上 (走 `lumen-customer` 独立模块); Vitest 累计拆 admin-web / customer-web 两栏, 避免单一栏数字看起来没动; 单元全模块累计含 4.5A 跨 5 模块新增 (lumen-common +5 / lumen-rbac +4 / lumen-customer +21 / lumen-dispatch +6 / lumen-wms +3) = 42, 与上面单元格 +42 一致 (TrackQuery 3 + CustomerContext 5 + PortalAuthService 10 + CustomerException 2 + PortalAuthControllerSecurity 2 + PortalTrackController 5 + JwtUtil 2 + JwtAuthFilter 4 + DeliveryOrder 6 + PodService 3 = 42)。

**Known follow-up (留 4.5B / 4.5C / 4.5D / 5.x)**:

- **Refresh token httpOnly cookie**: 4.5A 简版 refresh 直接返 access token, 浏览器存 localStorage → XSS 风险。4.5B 接 `Set-Cookie: refresh_token=...; HttpOnly; Secure; SameSite=Strict`
- **审计日志 `customer_id` 字段**: 4.5A 写操作 (4.5A 仅 PUT /profile stub, 无实际写) audit log 缺 `customer_id` 字段。4.5B 加 `AuditLog.customerId` + `audience=portal` 标记
- **多租户 portal fallback**: 4.5A 单租户, `X-Tenant-Id` 缺省默认 tenant=1。4.5B 改多租户 — header 缺省时拒登 + 提示前端补 header
- **CustomerSwitcher UI 启用**: 4.5A 顶栏组件已渲染但禁用 (单租户 / 单客户场景下没意义)。4.5B 多客户商家场景下启用, 点击下拉切 active customer
- **POD 缩略图 + 多图**: 4.5A `PodPreview` 单图预览; 实际 `wms_delivery_pod` 表 `photo_urls` JSON 字段支持多图。4.5B 接多图 carousel
- **Track list 筛选维度**: 4.5A 仅 `status` + 时间区间; 4.5B 加 `customer_po_no` / `do_no` / `carrier` 模糊搜索
- **CustomerContext 改 RequestScope**: 当前 ThreadLocal 在 `@Async` 线程下空 (类似 `TenantContext` 4.4 PR2 教训)。4.5B 改 RequestScope attribute 或 `TaskDecorator` 透传
- **`PortalTrackController` 鉴权细化**: 全部用 `customer:track:view` 一个权限, 没区分 list / detail / pod。4.5B 拆 `customer:track:list` + `customer:track:detail` + `customer:track:pod`
- **PortalCommonController 写实**: 4.5A PUT /profile stub, 4.5B 落库改 sys_user.phone / address, 密码改 BCrypt 重写
- **4.5B/4.5C/4.5D 各自 spec**: 下单 (商家自助下单) / 对账 (商家看月结 / 调账单) / 评价 (POD 后追评) — 后续 sprint 各自 brainstorm, 不在本切片范围

### §15.14 4.5B — 客户自助下单 + 取件时间窗 (2026-10-20)

**范围**: 4 PR / 35 commit (含 spec + plan 各 1) / 5 endpoint / 2 migration (V36 + V37) / 0 新模块 / 3 新前端页 / 31 单元 / 15 IT / 20 Vitest

**核心增量**: 商家 (B2B 客户) 在 `lumen-customer-web` 自助下单 — 选仓库 / 填地址货物 / 选取件时间窗 / 看实时价格预估 / 提交; 提交后可查列表与详情, 订单还停在 `PENDING_DISPATCH` 时可自助取消。替代走 CS 坐席的下单类工单。

- **V36** (`V36__customer_order_pickup.sql`, 单文件 4 block):
  1. `wms_delivery_order` 加 `planned_pickup_window_start` / `planned_pickup_window_end` (DATETIME NULL) + 索引 `idx_do_pickup_window` — 取件窗口只对门户下单有意义, admin / outbound 建的单保持 NULL
  2. 新表 `t_customer_rate_override` — 商家合同价 (唯一键 `uk_tenant_customer_biz (tenant_id, customer_id, business_type, effective_from)`)
  3. `sys_permission` +3 行 + `sys_role_permission` +3 行绑 customer 角色 (id 14)
  4. `sys_dict_item` +1 行 `delivery_order_source_type.PORTAL`

  (原计划的 block 4 `t_state_transition` 行已删 — V20 已有 `PENDING_DISPATCH → CANCELLED` 唯一行, V8 的 UNIQUE KEY 阻止新增。)

- **V37** (`V37__fix_v36_dangling_role_permission.sql`) — 集成测试阶段发现的 V36 修正, 详见下方「V36 权限 id 撞车」。

- **`lumen-finance` 加价能力** (T1.5 / T1.6):
  - `BillingEngineService.estimate(tenantId, customerId, serviceType, env)` — 三级短路: 合同价 override → BillingRule Aviator 求值 → 都没命中返 `source=NONE` (不写 accrual)。override 优先于规则, 商家合同价不该被通用规则覆盖
  - `BillingEngineService.estimateAndAccrue(...)` — 同步估价 + 写 `t_billing_accrual`。**失败不抛**, 写 `OPEN_FAILED` 行由后台 job 重试 — DO 创建是关键路径, 计费失败不该让客户看不到自己的单

- **`lumen-dispatch` 加订单能力** (T1.7 / T2.3 / T2.4):
  - `DeliveryOrderService.createForCustomer(tenantId, customerId, PortalOrderRequest)` — 校验取件窗口 (start ≥ now+2h 且 ≤ now+30d; end > start 且 ≤ start+8h; 同日) → 建 `sourceType=PORTAL` 的 DO → 同步 `estimateAndAccrue` → 发 `DeliveryOrderStateChangedEvent(action=create)`
  - `DeliveryOrderService.cancelForCustomer(...)` — uniform 404 (跨租户 / 跨客户 / 不存在) + 仅 `PENDING_DISPATCH` 可取消 (否则 `PortalBadRequestException` → HTTP 409) → remark 追加取消原因留痕 → 发事件
  - `pageForCustomer` / `getDetailForCustomer` 增返 `plannedPickupWindowStart/End` + `canCancel` (仅 PENDING 为 true)

- **`lumen-customer` 加 1 controller** (T2.6) — `PortalOrderController`, `@RequestMapping("/api/customer/order")`:

  | 端点 | 方法 | 权限 | 说明 |
  |------|------|------|------|
  | `/api/customer/order/preview` | POST | `customer:order:create` | 实时估价, 纯读不落库 |
  | `/api/customer/order` | POST | `customer:order:create` | 下单, 同步 estimateAndAccrue (失败不阻塞) |
  | `/api/customer/order` | GET | `customer:order:view` | 列表分页 (含取件窗口两列) |
  | `/api/customer/order/{doId}` | GET | `customer:order:view` | 详情 (含 canCancel + 取件窗口 + timeline) |
  | `/api/customer/order/{doId}/cancel` | POST | `customer:order:cancel` | 仅 PENDING_DISPATCH 可取消 |

  tenantId / customerId 一律从 `CustomerContext` 取并强制注入 service 调用 — 不读请求体, 防越权 (与 4.5A 的 track 端点同款)。

- **`lumen-customer-web` 加 3 页** (T3.1–T3.5):
  - `/orders/new` — `CreateOrder` 3 步向导 (基本信息 → 货物 + 取件时间窗 → 确认 + 估价 → 提交)
  - `/orders` — `OrderList` ProTable (doNo / status 筛选 + 服务端分页 + 取件窗口列)
  - `/orders/:doId` — `OrderDetail` Descriptions + Timeline + 取消订单 (按钮显隐吃 `canCancel`)

**关键设计决策**:

1. **override 优先于 BillingRule**: 商家签了合同价就不该被通用规则重新算一遍。`estimate` 先查 override, 命中直接返 `base_amount` 并短路 Aviator 求值。
2. **estimateAndAccrue 失败不阻塞下单**: 同步调失败写 `OPEN_FAILED` accrual, 由后台 job 重试。宁可先出一张缺计费的单, 也不能让客户看到"下单失败"。
3. **取件窗口强制同日**: 跨凌晨取件在城配场景罕见, 4.5B 强制 start/end 同日 + 窗口 ≤ 8h; 4.5C 再放宽。
4. **PENDING 取消不冲销 accrual**: 计费侧留给 staff 走 EXCEPTION / CANCELLED 路径处理, 4.5B 不实现。
5. **计费 env 只能用沙箱白名单变量**: `BillingScriptEngine` 的 `WHITELIST_VARIABLES` 只有 9 个 (amount / weight / distance / quantity / customer_level / zone / service_type / is_weekend / is_holiday), env 的 key 必须是其子集, 否则求值抛 `FinanceException_UnknownVar`。

**关键 bug 修复 (集成测试阶段发现并补齐)**:

- **`PortalAuthService.CUSTOMER_PERMS` 漏 `customer:order:cancel`**: 门户 JWT 的 `perms` claim 是 `PortalAuthService` 里硬编码的 `List`, **不查库**。V36 建的权限 + `@PreAuthorize` 双重保障下, 单测全绿, 但线上所有取消端点一律 403。修复: `CUSTOMER_PERMS` 补该 code (8 个权限)。**教训: 门户鉴权有两套真相 (JWT 硬编码 + DB 权限), 加权限 code 时两边都要动**。
- **计费 env 变量越界 (2 处)**: `PortalOrderController.preview` 与 `DeliveryOrderServiceImpl.createForCustomer` 都往 env 塞了 `stopCount` / `totalWeightKg`, 两个 key 都不在 9 变量白名单里 → `FinanceException_UnknownVar`。preview 因 override 短路而侥幸看不出来; create 路径则是 accrue **恒失败**, 门户下单永远写不出 `t_billing_accrual`。修复: 两处都改成白名单子集 (`quantity` / `weight` / `distance`)。
- **V36 权限 id 撞车 → 2 条悬空 role_permission**: `sys_permission` 有 `UNIQUE KEY uk_perm_tenant_code (tenant_id, code)`, 而 V33 建门户角色时已建过 `customer:order:create` (id 160) 与 `customer:order:view` (id 161)。V36 用 id 166/167 再插同样的 code, 撞的是这个唯一键不是主键, `ON DUPLICATE KEY UPDATE` 把 160/161 就地改名改排序, **166/167 从未存在**; 紧随其后的 `sys_role_permission` 328/329 就指向了两个不存在的权限 id。该表无外键, 不报错, 只是后台权限管理界面多两条空白行。修复: **V37** 删掉悬空绑定 (V36 checksum 已冻结不能改)。168 `customer:order:cancel` 是真新建的, 绑定 330 保留。
- **前端 `dayjs.toISOString()` 与 `LocalDateTime` 不兼容**: `CreateOrder` 原用 `start.toISOString()` 发时间窗, 产出 `2026-10-20T14:00:00.000Z`, 而后端字段是 `java.time.LocalDateTime`, Jackson 的 `LocalDateTimeDeserializer` 拒绝尾部的 `Z` → 400。除了被拒, `toISOString()` 还会把本地时间**换算成 UTC** (14:00 → 06:00Z), 静默把取件窗口挪 8 小时。修复: 改用 `dayjs.format('YYYY-MM-DDTHH:mm:ss')` 显式格式化, 补单测锁死契约。
- **`WmsOutboundShippedListener` 丢 customerId (4.5A.3 遗留)**: outbound 出货自动建 DO 时不写 `customer_id`, 客户门户按 customer 过滤, 这些单在商家眼里永远不存在。4.5B.2 修复 (事件携 customerId → listener 回写), 4.5B.4 补 IT 覆盖 (此前 0 覆盖)。

**PR 拆分 (实际 commit count)**:

| PR | 内容 | commits |
|----|------|--------:|
| PR1 (4.5B.1) | V36 (4 block) + `WmsDeliveryOrder` +2 字段 + `CustomerRateOverride` entity/mapper/service + `BillingEngineService.estimate` + `estimateAndAccrue` + 读端点重载增返窗口 | **16** |
| PR2 (4.5B.2) | 3 error code + 3 DTO + `createForCustomer` / `cancelForCustomer` + `PortalOrderController` (5 端点) + `WmsOutboundShippedListener` customerId 修复 | **7** |
| PR3 (4.5B.3) | `lumen-customer-web` 3 页 + 路由 + 菜单 + zh-CN 文案 + 20 Vitest | **5** |
| PR4 (4.5B.4) | 15 IT (8 新 IT 文件) + V37 + 回归修正 + README §15.14 | **5** |
| — | spec + plan (`docs/specs/`, `docs/plans/`) | **2** |
| **合计** | | **35** |

**测试覆盖**:

- 后端单元: **31** — `PortalOrderControllerTest` 12 + `PortalCreateOrderRequestTest` 4 + `DeliveryOrderServiceCancelForCustomerTest` 5 + `DeliveryOrderServiceCreateForCustomerTest` 3 + `BillingEngineServiceEstimateTest` 5 + `CustomerRateOverrideServiceTest` 2
- 后端 IT: **15** — `PortalOrderPickupWindowValidationIT` 5 + `PortalOrderReadWindowIT` 3 + `OutboundShippedCustomerIdIT` 2 + `PortalOrderPreviewIT` / `PortalOrderCreateIT` / `PortalOrderCancelPendingIT` / `PortalOrderCancelDispatchedForbiddenIT` / `PortalOrderCrossCustomer404IT` 各 1
- 前端 Vitest: **20** — `portalOrderService` 5 + `CreateOrder` 5 + `OrderDetail` 6 + `OrderList` 4
- `PortalAuthIT` 8 / `PortalTrackIT` 5 沿用 4.5A, 4.5B 未改动 (回归确认仍绿)

**回归实测 (4.5B.4 T4.3, 2026-10-20)**:

| 范围 | 结果 |
|------|------|
| 后端全模块 `mvn -o verify -am` | **BUILD SUCCESS** — 12/12 模块 SUCCESS |
| lumen-bootstrap 单元 | 全绿 (含本轮修的 2 个 env 断言) |
| lumen-bootstrap IT | **152/152 pass** (排除 `OutboxProducerConsumerIT` 1 + `OutboxRetryIT` 2, 均为 4.x 已知 flake, 见下方) → 加回为 155 |
| 前端 Vitest | **38/38 pass** (9 文件) |
| 前端 `tsc -b` | exit 0 |
| 前端 `vite build` | exit 0, 4149 modules, 34.4s |

本轮回归抓出并修掉的 3 个问题 (都不是 4.5B 功能 bug, 但都让"回归绿"这件事失真):

1. **两个单元测试锁死了 4.5B.1 的 env bug**: `DeliveryOrderServiceCreateForCustomerTest` 与
   `PortalOrderControllerTest` 都断言 `env.get("totalWeightKg")` / `env.get("stopCount")` —— 正是
   上面「计费 env 变量越界」里那两个越界 key。4.5B.1 时它们是绿的 (因为和当时的 bug 实现一致),
   4.5B.4 修完实现, 这两个测试就红了。**改了 env 的构造方式, 锁它的测试也必须一起改** ——
   两处都改成断言白名单内的 `weight` / `quantity`, 并加 `isSubsetOf(白名单)` 兜底断言,
   下次再越界会在单测就红, 而不是拖到 IT。
2. **`OutboundShippedCustomerIdIT` 自己有竞态**: listener 先 insert DO 再 for 循环 insert stop,
   两批各自独立提交; 测试只等 DO 落地就断言 stops, 全量回归时机器被 maven 占满, 竞态必现
   (`Expected size: 2 but was: 1`)。已把 stop 数纳入 await 条件。
3. **前端 5 个 `Test timed out in 5000ms` 是假红**: 与 maven 并行跑时被 CPU 饿死
   (`collect 578s` vs 总时长 132s), 其中 3 个还是 4.5A 的 `Login.test.tsx`。单独重跑全绿。
   **前端与后端回归不要并行跑。**

另: `-Dtest='!OutboxRetryIT'` 排除不掉 IT (failsafe 认 `-Dit.test`), 导致本该跳过的已知
flake 照跑并报错, 一度看起来像 30 个失败。正确写法见 §"Integration tests" 的三条 ⚠️。

**累计 (4.5A → 4.5B)**:

| 维度 | 4.5A | 4.5B | 增量 |
|------|------:|------:|------:|
| 单元 (全模块累计) | ~360 | ~391 | +31 |
| BE IT (lumen-bootstrap) | 140 | 155 | +15 |
| Vitest (lumen-customer-web) | 18 | 38 | +20 |
| Endpoint | 60 | 65 | +5 (PortalOrder 5) |
| Migrations | 3 | 5 | +2 (V36 + V37) |
| Services | 19 | 21 | +2 (`CustomerRateOverrideService` + `PortalOrderController` 背后的重载) |

**Known follow-up (留 4.5C / 4.5D / 5.x)**:

- **合同价谈判**: 4.5B 的 `t_customer_rate_override` 是简化版, 由 staff 在 admin-web 录入; 商家自助谈价 / 审批流留后续
- **接地图 API**: 4.5B 的 estimate 里 `distance` 恒为 0, LAST_MILE 按 `weight * 系数` 算。接实际里程后才能做按距离计费
- **refresh token HttpOnly cookie**: 沿用 4.5A 简版 (localStorage), XSS 风险未解决
- **多租户 portal fallback**: 仍是单租户 (`X-Tenant-Id` 缺省 1)
- **取件窗口放宽**: 4.5B 强制同日 + ≤ 8h; 跨日窗口 / 定时取件留后续
- **PENDING 取消的计费冲销**: 门户取消后 accrual 仍 OPEN, 需 staff 侧对账处理

---



**Generated by:** Task 22 of the Iteration 1.5 cross-cutting foundation plan
**Updated by:** Iteration 4.2 — Customer service SLA + escalation (PR1-6 complete; finalization + docs) · Iteration 4.3.1 — lumen-finance 计费引擎 + 单据基础 (4 PR complete) · Iteration 4.5B — 客户自助下单 + 取件时间窗 (4 PR complete)
**Plans:** [`docs/superpowers/plans/2026-09-18-iteration-1.5-cross-cutting-foundation.md`](./docs/superpowers/plans/2026-09-18-iteration-1.5-cross-cutting-foundation.md) · [`docs/superpowers/plans/2026-10-11-iteration-3.0-wms-vertical-slice.md`](./docs/superpowers/plans/2026-10-11-iteration-3.0-wms-vertical-slice.md) · [`docs/superpowers/plans/2026-10-12-iteration-4.1-delivery-dispatch.md`](./docs/superpowers/plans/2026-10-12-iteration-4.2-cs-sla-escalation.md) · [`docs/superpowers/plans/2026-10-17-iteration-4.3.1-foundation-fees.md`](./docs/superpowers/plans/2026-10-17-iteration-4.3.1-foundation-fees.md)