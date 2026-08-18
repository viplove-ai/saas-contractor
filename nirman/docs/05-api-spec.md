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
`GET /stores` (`?siteId`), `PUT|DELETE /stores/{id}`.
`GET|POST /boq-items` (`?projectId&siteId`), `GET|PUT /boq-items/{id}`, `POST /boq-items/{id}/progress`.

Creating a site also creates its default store, coded and named `site-<site code>` and
`site-<site name>`. Nobody is asked for it: a store is the shed by the gate, and a site
without one meets its first delivery with an empty store picker. `/stores` is the register
for the sites that keep more than one — `GET` there returns each store with its site's code
and name, narrowed to the caller's assignments like every other site-scoped read. A store is
reached through its site's fence and holds no permission of its own (`site:read`,
`site:write`, `site:delete`). `DELETE` is refused for any store the stock ledger, a receipt,
an issue, a transfer or a stock count has ever named; a store that is finished with is set
`active: false`, which keeps its ledger readable and stops new documents naming it.

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
`POST /materials/field` names a material at the gate from a name and a unit
(`masterdata:provisional`); the row is marked provisional and a name the organisation already
holds comes back rather than being duplicated.
`PUT /materials/{id}/field` is its second half — the same permission correcting the name it
gave, and only the name: not the rate, not the unit, because changing what a material is
measured in re-reads every quantity ever booked against it. Needs `version`. A field
correction marks the row provisional again so the office reads the new name; an office
caller's does not. A rename onto a name the catalogue already holds is a 409.

### /vendors, /expense-categories
`GET|POST /vendors` (`?type&active&q`), `GET|PUT /vendors/{id}` — `vendor:write`, not
`masterdata:write`, because onboarding a dealer is the accountant's work.

**Nobody types a supplier code.** `code` on the POST is optional and every screen leaves it
out; the server derives one from what he supplies and what he is called — `MAT-SHIVSHAKTI`,
`TRN-KUMAONCARRIERS` — appending `-2` for a second firm of the same name and falling back to
the `VEN-<year>-<n>` counter for a name with nothing usable in it. A code that *is* sent is
kept, for an organisation migrating a register whose codes already mean something.

`GET|POST /expense-categories` is the office's taxonomy. `POST /expense-categories/field`
takes a name alone (`masterdata:provisional:head`) and is what the "Other" answer on the
add-expense screen posts: the head is marked provisional with neither cost flag set, and a
name already on file comes back rather than becoming a second line of the same report.

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
| GET|POST | `/inventory/equipment` | plant held at a store — **not stock**, nothing here touches the ledger. `?siteId&storeId&status`. POST is idempotent on the client id and needs `equipment:create`; the row is PENDING unless the caller may accept it |
| GET | `/inventory/equipment/{id}` | |
| POST | `/inventory/equipment/{id}/decision` | `{action:ACCEPT\|REJECT,remarks}` — `equipment:approve`, admin only |
| PUT | `/inventory/equipment/{id}` | correct an entry. `equipment:write` on any row; `equipment:create` on any row at a site the caller is posted to, and that correction re-opens it to PENDING and drops the old decision. Needs `version` |
| PUT | `/inventory/equipment/{id}/photo` | `{attachmentId}`, null to remove. Same rule as PUT above; replacing an existing picture from the field re-opens the row, adding the first one does not |
| DELETE | `/inventory/equipment/{id}` | `equipment:write`, admin only. Soft |
| GET|POST | `/inventory/stock-corrections` | the field asking for a stock figure to be put right. `?siteId&storeId&status`. POST needs `inventory:correct`, is idempotent on the client id, and **posts nothing**; signed `quantityDelta`, reason mandatory. One open request per store and material — a second is a 409 |
| POST | `/inventory/stock-corrections/{id}/decision` | `{action:ACCEPT\|REJECT,remarks}` — `inventory:adjust`, because accepting *is* posting the adjustment. ACCEPT runs the ordinary ADJUSTMENT through the ledger, so a closed period or a balance that would go negative refuses it and leaves the request PENDING |

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
| GET|POST | `/expenses` | idempotent on the client id. POST returns **409 with the candidates** on a duplicate — exact vendor-and-bill-number, or same vendor and amount within a week — unless `?force=true`, which then requires `duplicateOverrideReason`. GET filters `?siteId&vendorId&categoryId&status&allocation&paymentStatus&from&to` |
| GET | `/expenses/summary` | what the same filter adds up to: booked, the site's share, the company's, paid, payable — computed per call, never stored |
| GET|PUT | `/expenses/{id}` | PUT for draft, returned and rejected rows only. An approved expense is re-opened or voided, never edited in place |
| POST | `/expenses/{id}/submit` | bill number, attached photograph or `noBillReason` required above `expense_settings.bill_required_above`; raises the approval chain |
| POST | `/expenses/{id}/approve` | `{action:APPROVE\|REJECT\|RETURN,remarks,allocation,siteShare,allocationNote}` — routes to the same engine as `/approvals/{id}/action`. The allocation is taken **only on an approval**; absent, the head's proposal stands |
| PUT | `/expenses/{id}/allocation` | `expense:allocate`. Re-decides whose cost it is — `{allocation:SITE\|COMPANY\|SPLIT,siteShare,note}`. Refused on a closed site, a locked period, a void row, and on a material-purchase or wage head whose value is counted at the site |
| POST | `/expenses/{id}/revise` | the author re-opens an approved expense: keeps its number, cancels its approval, re-enters the chain. Needs `reason` and `version`; refused once anything has been paid against it |
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
`GET|POST /dprs` (`?siteId&status&from&to`), `GET|PUT /dprs/{id}`,
`GET /dprs/prefill?siteId&date` → labour, material, expense rollup for the day,
`POST /dprs/{id}/submit`, `POST /dprs/{id}/verify`, `POST /dprs/{id}/approval`,
`POST /dprs/{id}/photos`, `GET /dprs/{id}/pdf`.

`PUT /dprs/{id}` writes whichever half the caller owns. Work items and the narrative need
`dpr:verify`; the day's account — the operational status, the conditions and the plant — is
writable by any `dpr:draft` holder posted to the site while the report is `DRAFT`, `REJECTED`
**or** `SUBMITTED`, and never by a `dpr:verify` holder after the handover, who is about to sign
under it. The two halves are applied separately, so a save carrying only one of them cannot
delete the other.

`POST /dprs/{id}/approval` is the office's final acceptance — `dpr:approve`, on a `VERIFIED`
report, once. It claims nothing: the measured quantities reached the measurement book at
`verify` and are untouched by it.

The prefill is **derived on every call**, never cached: the exit criterion is that it matches
the underlying records exactly, and a late correction, a rejection or a worker added to the
muster at five o'clock all have to show up in it. It carries `labourCostProvisional` because
the wage is frozen at verification — a cost standing on unverified attendance can move
overnight without anybody editing anything, and a report that did not say so would be quoting
a figure it cannot stand behind. `suggestedWorkItems` are BOQ lines that had labour or material
charged to them that day: evidence somebody worked on the line, and **not** a measurement of
how much got built.

The document freezes at `submit`, not at save. From then on `snapshotFrozen` is true and the
rolled-up figures are the report's own — a correction to a record underneath shows up as a
difference rather than by rewriting something an engineer signed. `verify` is the act that
posts measured quantities to the measurement book; a work line with no quantity is recorded
and claims nothing. Verifying twice cannot claim twice (`uq_boq_entry_dpr_item`, V9).

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
`GET /dashboard/admin`, `GET /dashboard/site/{siteId}`, `GET /dashboard/data-quality` — all
`?from&to`, defaulting to the month to date and refusing a range wider than a year.

The dashboards own no tables; every figure comes from a business module's own read API, which
is what stops them becoming a fourth opinion about what a project cost. Material is reported as
**three separate figures that add up**, with every term of the identity on the response:

```
openingValue + received − consumed − residual = inventoryValue
```

`purchased` is beside them and is not one of them — it comes from the bills rather than the
ledger, and it is not expected to equal `received`: freight is booked separately and a bill and
its lorry rarely arrive the same day. `residual` is the difference between that arithmetic and
what the balance cache holds, printed even when it is zero, because a reconciliation you only
see when it fails is one nobody believes when it passes.

`costIncurred` and `totalBooked` are carried apart for the same reason the expense register
carries four figures: the first is what the project cost and may be set against a budget, the
second is what left the books. Every data-quality finding carries `whatToDo` and the examples
behind its count, at one of two severities — a five-level scale invites arguments about whether
something is a three or a four, and nobody acts on a three.
`GET /imports/templates/{entity}` (xlsx), `POST /imports/{entity}` (multipart, dry-run flag), `GET /imports/batches`, `GET /imports/batches/{id}/errors` (xlsx).

### /sync
`POST /sync/batch` — offline queue drain. Array of `{clientId, entityType, payload, clientUpdatedAt}`. Response per item: `ACCEPTED | DUPLICATE | CONFLICT | REJECTED` with reason. This is the only endpoint the offline queue talks to.
