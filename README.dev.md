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
mvn spring-boot:run -pl lumen-bootstrap
```

First build: ~3 minutes (downloads dependencies, compiles 6 modules). Subsequent runs: ~30 seconds.

When you see `Started BootstrapApplication in X.XXX seconds`, the backend is ready.

- API base path: `http://localhost:8080/api/v1/...`
- Swagger UI: `http://localhost:8080/swagger-ui.html`
- Health: `http://localhost:8080/actuator/health`

Flyway will auto-create the schema (5 migrations under `lumen-parent/lumen-bootstrap/src/main/resources/db/migration/`):

| Version | Purpose |
|---------|---------|
| `V1__init_schema.sql` | Audit + login base tables |
| `V2__rbac.sql` | RBAC (users, roles, permissions) + seed data |
| `V3__masterdata.sql` | Dict, Country, Currency, UoM + demo rows |
| `V4__numbering.sql` | Number rule + sequence counter |
| `V5__notification.sql` | Notification templates + send log |

> Migrations are immutable once applied. If you need to change a V1-V5 file, create a new `V6__fix_xxx.sql` instead — see `docs/15-表结构对账与待更新清单-v2.1.md`.

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
3. 权限管理 (Permissions)
4. 数据字典 (Dicts)
5. 编号规则 (Number Rules)
6. 通知模板 (Notifications)
7. 审计日志 (Audit Logs)

---

## 4. Run tests

### Backend unit tests

```bash
cd lumen-parent
mvn test
```

Runs unit tests across all 6 modules in a few seconds.

### Backend integration tests (testcontainers)

Integration tests spin up real MySQL + Redis containers via [Testcontainers](https://java.testcontainers.org/), so **Docker Desktop must be running**.

```bash
cd lumen-parent
mvn verify -pl lumen-bootstrap
```

First run: ~5 minutes (pulls `mysql:8.0` + `redis:7-alpine` images via testcontainers). Subsequent: ~1 minute.

**10 `@Test` methods covering 8 mandatory scenarios:**

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
4. Navigate to **编号规则 (Number Rules)** → click **预览 (Preview)** on the `ORDER` rule (seeded in `V4__numbering.sql`: prefix `ORD`, date `yyyyMMdd`, 6-digit seq, daily reset) → see a generated number like `ORD-20260918-000001`.
5. Navigate to **通知模板 (Notifications)** → click **测试发送 (Test Send)** → fill in receiver → submit → check backend logs for `[MOCK-EMAIL]` output (notifications are stubbed in dev; see `lumen-notification` mock channels).
6. Open backend logs (terminal from step 2) → look for audit log entries with `traceId`, `userId`, `action`, `resource`.

---

## 6. Project layout

```
lumen-parent/                          # Backend (Maven multi-module, parent BOM in pom.xml)
├── lumen-common/                      # Shared: R, PageResult, BaseEntity, TenantContext, JWT, Audit, BizException
├── lumen-rbac/                        # RBAC: User, Role, Permission, Auth, JWT, SecurityConfig
├── lumen-masterdata/                  # Master data: Dict, Country, Currency, UoM, StateMachine
├── lumen-numbering/                   # Number generator: Redis INCR + DB fallback
├── lumen-notification/                # Notification: templates + 3 mock channels (EMAIL/SMS/IM)
└── lumen-bootstrap/                   # Entry point: @SpringBootApplication + Flyway + Docker

lumen-admin-web/                       # Frontend (Vite + React 18 + TS + Antd Pro skeleton)
├── src/
│   ├── pages/                         # Login, Users, Roles, Permissions, Dict, NumberRule, Notification, AuditLog
│   ├── layouts/                       # BasicLayout (ProLayout)
│   ├── services/                      # axios wrapper (request.ts) with refresh-on-401
│   └── access.ts                      # Permission sync
└── vite.config.ts                     # Dev server on :5173, proxies /api/v1 → :8080

docs/
├── 01-系统设计总览-v1.0.md            # System overview
├── 02-业务表结构设计-v2.0.md          # Business table design
├── 13-第二轮深化设计-总览与扩展性框架-v4.0.md   # Cross-cutting + extensibility framework
└── superpowers/                       # Platform-skeleton spec + implementation plan
    ├── specs/2026-09-18-platform-skeleton-design.md
    └── plans/2026-09-18-platform-skeleton.md
```

---

## 7. Security notes for production

> **MUST change before any non-dev deploy:**

- `LUMEN_JWT_SECRET` — HS256 needs **at least 32 bytes (256 bits)** of secret material. The docker compose file ships a 64-hex-char default (`0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef`, 32 bytes raw), which is OK for HS256. Generate a fresh one with `openssl rand -hex 32` for staging / prod.
- `application-dev.yml` ships a shorter fallback (`0123456789abcdef0123456789abcdef`, 16 bytes raw) — only safe because the docker compose overrides it via env. If you run the backend outside compose (`mvn spring-boot:run`), set `LUMEN_JWT_SECRET` yourself or the JWT library will reject the key at startup.
- Admin password — `admin / admin123` is seeded by `V2__rbac.sql` for dev convenience; force a password reset on first login for any non-dev environment.
- `MYSQL_ROOT_PASSWORD` and `MYSQL_PASSWORD` in `docker-compose.dev.yml` (`rootpass` / `lumen`) — replace with strong values before exposing the stack.
- CORS configuration in `SecurityConfig.java` — restrict to actual frontend origins (the dev profile permits `http://localhost:5173`).

**Tenant isolation:** All tenant-scoped tables go through the MyBatis-Plus multi-tenant plugin (`TenantLineInnerInterceptor`). Verify by trying to read another tenant's data — should return empty. See `RbacIsolationIT` for the executable check.

**JWT algorithm:** Backend uses HS256 by default. The 64-byte default secret in compose auto-selects HS512 via the JJWT auto-detection (anything ≥ 32 bytes picks HS512). Both are safe symmetric HMAC algorithms; production should pin explicitly via the `lumen.jwt.algorithm` property.

---

## 8. Troubleshooting

### Backend won't start: `Cannot load driver class: com.mysql.cj.jdbc.Driver`

MySQL container is not running. Check `docker compose -f lumen-parent/lumen-bootstrap/src/main/docker/docker-compose.dev.yml ps` and ensure `mysql` is `healthy`.

### Backend starts but Flyway fails: `Migration checksum mismatch`

You've modified a migration script. Migrations are immutable once applied. Create a new `V6__fix_xxx.sql` instead, or drop the dev DB volume (`docker compose ... down -v`) to start fresh.

### Frontend can't reach backend: `Network Error` on login

1. Is backend running on `:8080`? `curl http://localhost:8080/actuator/health`
2. Is Vite proxy configured? Check `lumen-admin-web/vite.config.ts` → `/api/v1` proxy target should be `http://localhost:8080`.
3. CORS issue? Check `SecurityConfig.java` — it should permit `/api/v1/auth/**`.

### Integration tests time out / fail with "Could not find a valid Docker environment"

Docker Desktop must be running (Docker daemon socket accessible). On macOS / Windows ensure Docker Desktop is started, not just installed. On Linux, confirm your user is in the `docker` group.

### `401` / `JWT signing key too short` on backend startup

The JJWT library refuses keys shorter than 32 bytes for HS256. Set `LUMEN_JWT_SECRET` to a 64-hex-char value (or `openssl rand -hex 32`) before `mvn spring-boot:run` if you aren't running via compose.

### Permission denied on user create

The seeded `admin` user has the `SUPER_ADMIN` role with all 27 permissions. If you logged in as a different user, they may lack `user:create`. Either:
- Use the `admin` user (recommended for dev), or
- Assign the `user:create` permission via the **角色管理 (Roles)** page.

---

## 9. Next steps

- Run `mvn verify -pl lumen-bootstrap` to confirm all 10 integration tests pass.
- Browse the 7 pages and exercise CRUD on Users / Roles / Dict / Notification.
- Read `docs/13-第二轮深化设计-总览与扩展性框架-v4.0.md` for the broader platform vision.
- Read `docs/superpowers/specs/2026-09-18-platform-skeleton-design.md` for the design rationale behind the skeleton.
- Read `docs/superpowers/plans/2026-09-18-platform-skeleton.md` for the 28-task implementation plan.

---

**Generated by:** Task 9.2 of the platform-skeleton implementation plan
**Plan:** [`docs/superpowers/plans/2026-09-18-platform-skeleton.md`](./docs/superpowers/plans/2026-09-18-platform-skeleton.md)