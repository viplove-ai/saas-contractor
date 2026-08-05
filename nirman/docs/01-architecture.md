# 01 — Architecture

## Functional architecture

```
                     ┌──────────────────────────────────────┐
                     │  Browser / installed PWA             │
                     │  React + TS + Vite                   │
                     │  ┌────────────┐  ┌────────────────┐  │
                     │  │ Online UI  │  │ Offline drafts │  │
                     │  │ TanStack   │  │ IndexedDB +    │  │
                     │  │ Query      │  │ sync queue     │  │
                     │  └────────────┘  └────────────────┘  │
                     └───────────────┬──────────────────────┘
                                     │ HTTPS, JWT bearer
                     ┌───────────────▼──────────────────────┐
                     │  Nginx (TLS, static, /api proxy)     │
                     └───────────────┬──────────────────────┘
                     ┌───────────────▼──────────────────────┐
                     │  Spring Boot modular monolith        │
                     │  ┌────────────────────────────────┐  │
                     │  │ web  → service → repository    │  │
                     │  └────────────────────────────────┘  │
                     │  cross-cutting: security, audit,     │
                     │  approval, attachment, import,       │
                     │  period-lock, error handling         │
                     └────┬─────────────────────────┬───────┘
                          │                         │
                 ┌────────▼────────┐      ┌─────────▼────────┐
                 │  PostgreSQL 16  │      │  MinIO (S3 API)  │
                 │  Flyway-managed │      │  bills, photos   │
                 └─────────────────┘      └──────────────────┘
```

## Module boundaries

A modular monolith: one deployable, one database, but packages behave like services. A module owns its tables and exposes a Java API. Cross-module reads go through that API, never through another module's repository.

| Module | Owns | Depends on |
|---|---|---|
| `identity` | organisations, users, roles, permissions, site assignments, tokens | — |
| `project` | projects, sites, stores, boq_items | identity |
| `masterdata` | units, materials, vendors, labour_contractors, expense_categories | identity |
| `labour` | workers, wage_rates, allocations, attendance, corrections | project, masterdata, approval |
| `inventory` | purchase orders, goods receipts, stock_transactions, transfers, counts | project, masterdata, approval |
| `expense` | expenses, payments, site_advances, settlements | project, masterdata, approval |
| `dpr` | daily_progress_reports and child tables | labour, inventory, expense (read-only) |
| `approval` | approvals, approval_rules | identity |
| `attachment` | attachments, storage client | identity |
| `audit` | audit_logs | — |
| `reporting` | no tables; read-only projections, Excel/PDF export | all |
| `dashboard` | no tables; aggregate queries | all |
| `imports` | import_batches | all |

### Enforced rules
- Controllers hold no business logic. They map DTO → service call → DTO.
- JPA entities never leave the service layer. MapStruct converts to DTOs.
- Every financial or stock write runs inside `@Transactional` with explicit isolation where needed.
- `@Version` optimistic locking on every mutable business entity.
- Site access is validated in the service layer by `SiteAccessGuard`, never only in the UI.

## Technical architecture

**Request pipeline**
```
Nginx → CorrelationIdFilter → RateLimitFilter (no-op in v1, hook present)
      → JwtAuthenticationFilter → Spring Security authorization
      → @RestController → @Service (@Transactional) → Repository → PostgreSQL
      → GlobalExceptionHandler → RFC 7807 ProblemDetail
```

**Auth**
- Access token: JWT, 15 min, carries `sub`, `org`, `roles`, `perms`, `sites` claim (or `ALL` for admin).
- Refresh token: opaque UUID, hashed with SHA-256 in `refresh_tokens`, 30 days, single-use with rotation. Reuse of a rotated token revokes the whole family and raises an audit event.
- Passwords: BCrypt cost 12.

**Audit**
`@Auditable` annotation plus a Hibernate entity listener writes to `audit_logs` with before and after JSON. Financial and approval writes are audited unconditionally, not by annotation.

**Approval engine**
One table `approvals` plus `approval_rules`. A module calls `ApprovalService.submit(entityType, entityId, context)`. The engine reads matching rules, builds the level chain, and emits `approve / reject / return / cancel` transitions. Modules subscribe to a Spring `ApplicationEvent` to apply their own status change. No approval `if` statements live inside a business service.

**Period lock**
`period_locks(site_id, year_month, module, locked_by, locked_at)`. A single `PeriodLockGuard` is called by every write path in labour, inventory and expense. Nothing else needs to know about locking.

**Idempotency**
`POST` endpoints that create transactions accept `Idempotency-Key`. The key plus user plus endpoint is stored in `idempotency_records` with the response body. A repeat returns the stored response instead of a duplicate row.

## Frontend architecture

```
src/
  app/         router, providers, theme, layout shells
  features/    one folder per module: api hooks, schemas, components, routes
  shared/      ui primitives, formatting, permission helpers, api client
  offline/     Dexie schema, sync queue, image compression, conflict UI
```
- Server state: TanStack Query. Client state: React context only where genuinely shared.
- Forms: React Hook Form with a Zod schema per form. The same Zod schema shapes the offline draft record.
- Two shells: `SupervisorShell` (big-tile home, one task per screen) and `DeskShell` (sidebar, tables, filters) for Admin, Engineer and Accountant. The role picks the shell.

### Visual direction
A site tool used in sunlight on a cracked phone screen, so: high contrast, no thin greys, no decorative gradients.
- Palette: `--ink #14181D`, `--surface #FFFFFF`, `--muted #5A646E`, `--line #DCE1E6`, `--signal #C2410C` (the one accent, reserved for actions that write data), `--ok #15803D`, `--warn #B45309`, `--stop #B91C1C`.
- Type: **IBM Plex Sans** for UI, **IBM Plex Mono** for every quantity, rate and amount — numbers align in columns and misread digits are the expensive failure here.
- Signature element: the status chip. One shape, five states (`Draft`, `Pending sync`, `Submitted`, `Verified`, `Rejected`), same position on every card and row, so a supervisor learns one visual grammar and reads it across all six modules.
- Minimum touch target 48px. No control smaller than that on a supervisor screen.

## Deployment
- Dev: `docker compose up` gives Postgres, MinIO, backend, frontend dev server.
- Prod: same compose file with `--profile prod` — Nginx serves the built SPA and proxies `/api`, backend runs as a fat jar, Postgres and MinIO are external or volume-backed.
- Config: environment variables only. No secret is ever committed.
