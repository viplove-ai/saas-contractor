# 05 — API specification

Base path `/api/v1`. JSON only. OpenAPI served at `/swagger-ui.html`.

## Conventions
- **Auth**: `Authorization: Bearer <access token>` on everything except `/auth/login` and `/auth/refresh`.
- **Correlation**: request may send `X-Correlation-Id`; the response always returns one, and it appears in every log line.
- **Idempotency**: transaction-creating POSTs accept `Idempotency-Key: <uuid>`. Repeats return the original response.
- **Optimistic locking**: mutating PUT/PATCH requires `version` in the body. Mismatch → `409`.
- **Pagination**: `?page=0&size=25&sort=createdAt,desc`. Response is `{content, page, size, totalElements, totalPages}`.
- **Filtering**: explicit named params per endpoint (`siteId`, `projectId`, `from`, `to`, `status`, `q`). No generic query DSL exposed.
- **Errors**: RFC 7807.
```json
{ "type":"https://nirman/errors/validation", "title":"Validation failed", "status":400,
  "detail":"2 fields rejected", "instance":"/api/v1/attendance",
  "correlationId":"9f1c…", "errors":[{"field":"checkOutTime","message":"must be after checkOutTime"}] }
```
- **Status codes**: 200 read, 201 create (with `Location`), 204 delete/void, 400 validation, 401 unauthenticated, 403 permission or site scope, 404, 409 conflict/version/duplicate, 422 business rule (negative stock, locked period), 429 rate limit, 500.

## Endpoints

### /auth
| Method | Path | Notes |
|---|---|---|
| POST | `/auth/login` | body `{username,password}` → access + refresh + user profile |
| POST | `/auth/refresh` | rotates refresh token; reuse revokes family |
| POST | `/auth/logout` | revokes current refresh family |
| POST | `/auth/password/change` | requires current password |
| GET | `/auth/me` | profile, roles, permissions, assigned sites |

### /users, /roles
`GET|POST /users` (`?q`, `?active`, `?role`), `GET|PUT /users/{id}`, `PATCH /users/{id}/status`, `POST /users/{id}/password/reset`, `GET|PUT /users/{id}/sites`, `GET|PUT /users/{id}/roles`, `GET /roles`, `GET /permissions`.

A reset takes `{temporaryPassword}`, returns 204 and never echoes a password: it forces `must_change_password` and revokes the member's refresh families, so the value the admin typed is the only copy. Creating a site with `siteEngineerId` or `supervisorId` opens a site assignment for those members, and dropping them closes it — `sites.supervisor_id` is a label, `user_site_assignments` is the permission, and the two are kept in step server-side.

### /projects, /sites, /boq-items
`GET|POST /projects`, `GET|PUT /projects/{id}`, `GET /projects/{id}/summary`.
`GET|POST /sites` (`?projectId`), `GET|PUT /sites/{id}`, `GET|POST /sites/{id}/stores`.
`GET|POST /boq-items` (`?projectId&siteId`), `GET|PUT /boq-items/{id}`, `POST /boq-items/{id}/progress`.

### /workers
`GET|POST /workers` (`?siteId&contractorId&skill&active&q`), `GET|PUT /workers/{id}`,
`GET|POST /workers/{id}/wage-rates` (POST closes the open rate and opens a new one),
`POST /workers/{id}/allocations`, `POST /workers/{id}/photo`.

### /attendance
| Method | Path | Notes |
|---|---|---|
| GET | `/attendance/roster?siteId&date` | workers assigned to the site with any existing record prefilled |
| POST | `/attendance/bulk` | idempotent; array of records, one client UUID each |
| PUT | `/attendance/{id}` | draft only |
| POST | `/attendance/submit` | `{siteId,date}` → Draft → Submitted |
| POST | `/attendance/verify` | `{ids[],action:VERIFY\|REJECT,remarks}` |
| POST | `/attendance/{id}/corrections` | reason, old value, new value; raises an approval |
| POST | `/attendance/lock` | `{siteId,yearMonth}` admin only |
| GET | `/attendance` | `?siteId&from&to&workerId&status` paged |
| GET | `/attendance/summary` | `?siteId&from&to&groupBy=worker\|contractor\|skill\|date` |

### /materials, /inventory
`GET|POST /materials`, `GET|PUT /materials/{id}`, `GET|POST /materials/{id}/conversions`, `GET /units`.

| Method | Path | Notes |
|---|---|---|
| POST | `/inventory/opening-stock` | one-time per store+material; 409 on a second |
| GET|POST | `/inventory/goods-receipts` | idempotent on the client id; multi-line; 409 on a repeated vendor invoice unless `?force=true` |
| GET | `/inventory/goods-receipts/{id}` | |
| POST | `/inventory/goods-receipts/{id}/verify` | `{action:VERIFY\|REJECT}` — verifying is what puts material into the store |
| POST | `/inventory/goods-receipts/{id}/cancel` | only before it has posted |
| GET|POST | `/inventory/issues` | idempotent; requires boqItemId or purpose |
| GET | `/inventory/issues/{id}` | |
| POST | `/inventory/issues/{id}/approve` | `{action:APPROVE\|REJECT}` — approving takes the stock out and freezes the average onto the lines |
| POST | `/inventory/issues/{id}/cancel` | only before it has posted |
| GET|POST | `/inventory/transfers` | |
| GET | `/inventory/transfers/{id}` | readable by whoever runs either end |
| POST | `/inventory/transfers/{id}/dispatch` | → IN_TRANSIT; out of the sender, counted at neither store |
| POST | `/inventory/transfers/{id}/receive` | → RECEIVED; credits what actually arrived, records the shortage |
| POST | `/inventory/transfers/{id}/cancel` | only before dispatch |
| POST | `/inventory/wastage` | type WASTAGE or DAMAGE, reason mandatory |
| GET|POST | `/inventory/counts` | physical count; system quantity frozen per line, variance computed server-side |
| GET | `/inventory/counts/{id}` | |
| POST | `/inventory/counts/{id}/decision` | `inventory:adjust` only; approving posts one adjustment per differing line |
| POST | `/inventory/adjustments` | admin only, signed, reason mandatory |
| GET | `/inventory/stock` | `?storeId&siteId&materialId&lowOnly` current qty, avg rate, value, in transit |
| GET | `/inventory/ledger` | `?storeId&materialId&from&to` append-only movement, oldest first |

### /material-estimates
`GET|POST /material-estimates` (`?projectId&materialId&level`) — a repeat for the same scope
and level supersedes rather than overwrites.
`POST /material-estimates/derive` turns a BOQ line's work quantity into a material quantity
through a consumption norm.
`GET /material-estimates/variance?projectId&level&materialId` — estimated versus actual,
measured **only over the BOQ scope the estimate covers**, with uncovered consumption itemised
beside it and a `scopeComplete` flag saying whether the figure is the whole story (docs/09 B).

### /expenses, /payments, /advances
| Method | Path | Notes |
|---|---|---|
| GET|POST | `/expenses` | idempotent on the client id. POST returns **409 with the candidates** on a duplicate — exact vendor-and-bill-number, or same vendor and amount within a week — unless `?force=true`, which then requires `duplicateOverrideReason` |
| GET|PUT | `/expenses/{id}` | PUT for draft, returned and rejected rows only. An approved expense is voided and replaced, never edited |
| POST | `/expenses/{id}/submit` | bill number, attached photograph or `noBillReason` required above `expense_settings.bill_required_above`; raises the approval chain |
| POST | `/expenses/{id}/approve` | `{action:APPROVE\|REJECT\|RETURN,remarks}` — routes to the same engine as `/approvals/{id}/action` |
| POST | `/expenses/{id}/void` | never a hard delete; cancels any level still waiting |
| POST | `/expenses/{id}/attachments` | links an uploaded bill photograph |
| GET|POST | `/payments` | partial payments are the normal case; refuses more than is still payable, and anything against an unapproved expense. `?vendorId&expenseId&from&to` |
| GET | `/vendors/balances` | approved cost, cash paid and payable per vendor — derived from the bills, never stored |
| GET|POST | `/advances` | site float issued to a member of staff (`advance:issue`) |
| GET | `/advances/{id}` | |
| GET|POST | `/advances/{id}/settlements` | attach approved expenses and record cash returned. **Nothing moves on the float until the settlement is approved** |
| POST | `/settlements/{id}/decision` | `{action,remarks}` — approving is what clears the float |
| GET | `/advances/balances` | `?userId&siteId` — floats still outstanding |

### /dprs
`GET|POST /dprs` (`?siteId&from&to`), `GET|PUT /dprs/{id}`,
`GET /dprs/prefill?siteId&date` → labour, material, expense rollup for the day,
`POST /dprs/{id}/submit`, `POST /dprs/{id}/verify`, `GET /dprs/{id}/pdf`.

### /approvals
The one queue, whatever module raised the record. `GET /approvals/pending`
(`?entityType&siteId`) matches on the caller's **roles** rather than their user id — the
queue belongs to the job, so an engineer on leave does not take his site's approvals with
him. `POST /approvals/{id}/action` `{action,remarks}`; only the role a level is assigned to
may act. `GET /approvals/history?entityType&entityId` returns every level raised, decided or
not. `GET /approvals/rules` exposes the configured levels and thresholds so a screen can say
who is next.

Routing lives in `approval_rules` alone (docs/09 open question 2). An organisation with no
rules configured gets a single administrator level — never an automatic pass.

### /attachments
`POST /attachments` multipart → metadata + object key; `GET /attachments/{id}/url` → short-lived signed URL after a site-access re-check; `DELETE /attachments/{id}` (draft owners only).

### /reports, /dashboard, /imports
`GET /reports/{name}` with `?format=json|xlsx` — `attendance-register`, `wage-summary`, `stock-position`, `material-consumption`, `low-stock`, `transfer-register`, `wastage`, `expense-register`, `payable-ageing`, `advance-balances`, `budget-vs-actual`.

`expense-register` returns four figures rather than one total: `costIncurred + materialPurchases + labourDisbursements = totalBooked`. Total booked is what left the books, not what the project cost — material purchase becomes inventory and is costed again at issue, and money handed to a worker settles a wage already costed through attendance (docs/09).
`GET /dashboard/admin`, `GET /dashboard/site/{siteId}`, `GET /dashboard/data-quality`.
`GET /imports/templates/{entity}` (xlsx), `POST /imports/{entity}` (multipart, dry-run flag), `GET /imports/batches`, `GET /imports/batches/{id}/errors` (xlsx).

### /sync
`POST /sync/batch` — offline queue drain. Array of `{clientId, entityType, payload, clientUpdatedAt}`. Response per item: `ACCEPTED | DUPLICATE | CONFLICT | REJECTED` with reason. This is the only endpoint the offline queue talks to.
