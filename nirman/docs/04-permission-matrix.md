# 04 — Role and permission matrix

Permissions are rows in `permissions`, granted to roles in `role_permissions`. Nothing below is hard-coded in Java beyond the string constant.

Legend: **Y** = allowed. **A** = allowed only for assigned sites. **O** = only own records. **—** = denied.

¹ `attendance:correct` is what separates the two kinds of edit. A row that is draft, rejected or **submitted** is still the supervisor's to change under `attendance:create` — nothing is frozen or paid until verification, so a submitted row waiting in the queue is still only a claim. Once it is **verified** the wage is pinned and posted, so changing it needs `attendance:correct`, a stated reason, and it posts the difference to the worker's ledger as an `ADJUSTMENT`. A **locked** month is closed to both.

| Permission code | Admin | Engineer | Supervisor | Accountant |
|---|:--:|:--:|:--:|:--:|
| `user:read` / `user:write` | Y | — | — | — |
| `role:assign` | Y | — | — | — |
| `project:read` | Y | A | A | Y |
| `project:write` | Y | — | — | — |
| `site:read` | Y | A | A | Y |
| `site:write` | Y | — | — | — |
| `masterdata:read` | Y | Y | Y | Y |
| `masterdata:write` | Y | — | — | — |
| `worker:read` | Y | A | A | — |
| `worker:write` | Y | A | — | — |
| `wage:read` | Y | A | — | Y |
| `wage:write` | Y | — | — | — |
| `attendance:create` | Y | A | A | — |
| `attendance:submit` | Y | A | A | — |
| `attendance:verify` | Y | A | — | — |
| `attendance:correct` | Y | A | — | — |¹
| `attendance:lock` | Y | — | — | — |
| `inventory:receive` | Y | A | A | — |
| `inventory:issue` | Y | A | A | — |
| `inventory:transfer` | Y | A | A | — |
| `inventory:verify` | Y | A | — | — |
| `inventory:adjust` | Y | — | — | — |
| `inventory:read` | Y | A | A | Y |
| `expense:create` | Y | A | A | — |
| `expense:approve:l1` | Y | A | — | — |
| `expense:approve:l2` | Y | — | — | — |
| `expense:read` | Y | A | A/O | Y |
| `payment:record` | Y | — | — | Y |
| `vendor:balance:manage` | Y | — | — | Y |
| `advance:issue` | Y | — | — | Y |
| `advance:settle:submit` | Y | A | A/O | — |
| `advance:settle:approve` | Y | A | — | Y |
| `dpr:draft` | Y | A | A | — |
| `dpr:verify` | Y | A | — | — |
| `boq:read` | Y | A | A | Y |
| `boq:write` | Y | A | — | — |
| `boq:progress:record` | Y | A | — | — |
| `attachment:upload` | Y | A | A | Y |
| `attachment:download` | Y | A | A | Y |
| `report:operational` | Y | A | A | Y |
| `report:financial` | Y | — | — | Y |
| `report:export` | Y | A | — | Y |
| `dashboard:company` | Y | — | — | Y |
| `dashboard:site` | Y | A | — | — |
| `dashboard:dataquality` | Y | A | — | — |
| `audit:read` | Y | — | — | — |
| `import:run` | Y | — | — | — |
| `period:lock` / `period:unlock` | Y | — | — | — |

## Enforcement layers
1. **Method** — `@PreAuthorize("hasAuthority('attendance:verify')")` on the service method, not the controller.
2. **Site scope** — `SiteAccessGuard.assertCanAccess(siteId)` reads the JWT `sites` claim and re-validates against `user_site_assignments`. Called before any read or write touching site data. This is what stops IDOR.
3. **List narrowing** — a guard only fires on a site the caller *names*. Every list that can be asked for without one (`/sites`, `/projects`, `/workers`, `/attendance`, `/worker-advances`) therefore narrows to the caller's assigned sites by default: for a site-scoped role, no filter means "mine", never "everyone's". A user with `seesAllSites` skips the narrowing; a site-scoped user posted nowhere gets an empty page rather than an `IN ()`.
4. **Ownership** — where the matrix says **O**, the service adds `created_by = currentUser` to the query.
5. **State** — approved, locked or cancelled records reject writes regardless of permission. Checked by `PeriodLockGuard` and per-module state machines.

The Accountant is deliberately read-only on operational records: they hold `expense:read` and `payment:record` but no `expense:create`, `attendance:*` or `inventory:*` write permission.

The Supervisor lost `dashboard:site` in V13. Cost per contract line, budget burn and variance
are the engineer's and the accountant's questions; what a supervisor is answerable for is what
happened at site today, which is his muster, his entries and the report he sends. He keeps
`expense:read` — his own bills, which he entered — and holds no approval permission, so
Approvals and payments is gated on `expense:approve:*`/`payment:record` rather than on being
able to read an expense.
