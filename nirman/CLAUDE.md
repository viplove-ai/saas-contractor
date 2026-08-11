# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

Nirman is a construction site management system for a CPWD contractor: labour attendance, a
real stock ledger, expenses with approvals, daily progress reports and dashboards. Modular
monolith — Spring Boot 3.3 / Java 21 backend, React 18 + Vite PWA frontend, PostgreSQL 16,
MinIO for files.

`README.md` is the authoritative setup guide (toolchain install, dev logins, the seed data).
`docs/00`–`docs/09` carry the design decisions; `docs/00-assumptions.md` and
`docs/09-design-decisions.md` explain *why* the rules below exist and are worth reading
before arguing with one.

## Repository layout note

The git root is one level **above** this directory. CI and deploy workflows live at
`../.github/workflows/` and reference paths as `nirman/backend` and `nirman/frontend`. A
change to build steps has to be made there, not in a `.github/` under this folder.

## Commands

Everything below runs from this directory unless noted.

```bash
./scripts/dev-doctor.sh
```

Reports every prerequisite as OK or MISS and changes nothing. Run it first on an unfamiliar
machine.

```bash
./scripts/dev.sh start
```

Runs the whole stack (`db`, `backend`, `frontend`, or `all`). Also `stop`, `status`,
`restart <target>`, `logs <target>`, `reset-db`. It finds processes by listening port rather
than a stored pid, because `mvn spring-boot:run` forks a separate JVM.

Java changes need `./scripts/dev.sh restart backend` — there is no hot reload. React changes
hot-reload on their own.

### Backend

```bash
cd backend && ./mvnw test
```

```bash
cd backend && ./mvnw test -Dtest=AttendanceWorkflowIntegrationTest
```

```bash
cd backend && ./mvnw test -Dtest=AttendanceWorkflowIntegrationTest#typedHoursSurviveVerification
```

```bash
cd backend && ./mvnw -B verify
```

### Frontend

```bash
cd frontend && npm run lint && npm run typecheck && npm run test
```

```bash
cd frontend && npx vitest run src/features/attendance/previewHours.test.ts
```

```bash
cd frontend && npm run e2e
```

`npm run lint` runs with `--max-warnings 0`; CI fails on a warning. `npm run e2e` builds the
app first and runs Playwright against the production output, because the PWA manifest and
service worker only exist after `vite build`.

### Environment constraints

The primary dev machine is macOS 12, which shapes two things you will otherwise misdiagnose:

- **No Docker daemon.** `AbstractIntegrationTest` falls back to the local `postgresql@16` in
  a separate `nirman_test` database (schema dropped and recreated first) when Testcontainers
  finds no daemon. Run `./scripts/dev-db.sh` before the suite in that case. The dev database
  is never touched by tests.
- **Playwright needs its browser fetched once.** This note used to say the suite could not
  run locally at all, which was true of a macOS 12 machine and is no longer true of this
  one — `npx playwright install chromium` then `npm run e2e` runs the whole suite here in
  about half a minute. Do that rather than pushing a change to `frontend/src/offline/` to
  find out from CI whether it works.

## Architecture

### Module boundaries are real, not decorative

`backend/src/main/java/in/nirman/modules/<module>/` with `api/ domain/ repository/ service/
mapper/`. Not every module carries all five (`audit` writes by plain JDBC because
`audit_logs` is append-only and must never be loaded through the ORM).

The rules, enforced by review rather than by tooling:

- A controller in module A never imports a repository or entity from module B.
- Cross-module reads go through the other module's **`*Lookup` service interface** —
  `LabourLookup`, `InventoryLookup`, `ExpenseLookup`, `SiteLookup`, `BoqLookup`,
  `MaterialLookup`, `UnitLookup`. Each is paired with a `*LookupService` impl and returns
  purpose-shaped
  records for the caller (the DPR, the dashboards), not entities. When the DPR needs one
  site's labour for one day, it calls `LabourLookup.day(...)`, not
  `AttendanceRecordRepository`.
- JPA entities never appear in a controller signature.
- Something shared by three or more modules moves to `common/`, and nothing else does.

`SiteAccessGuard` (in `security/`) and `PeriodLockGuard` (in `common/`) query by JDBC rather
than through the owning module's repositories — nearly every module calls them, so routing
through repositories would create the dependency cycles the boundaries exist to prevent.

### Authorization has layers, and they live in the service

`@PreAuthorize("hasAuthority('attendance:verify')")` goes on the **service** method, not the
controller. Permissions are seeded rows (`V2__permissions_and_system_roles.sql`), not
constants scattered through code — a new permission is a migration.

Site scoping is separate from permissions and enforced in the service layer on every read and
write via `SiteAccessGuard.assertCanAccess(siteId)`. The JWT's `sites` claim can only ever say
*no*; a *yes* is re-checked against `user_site_assignments` on the way through, so access
revoked at 10:00 does not survive in a token issued at 09:58. Frontend role checks
(`shared/roles.ts`) hide what a user cannot use — that is UX, never security.

`PeriodLockGuard.assertOpen(siteId, date, module)` blocks writes into a closed accounting
period and is called by every write path in labour, inventory and expense.

A `*Lookup` method carries no `@PreAuthorize` and no guard call: the caller already passed
the check that got it there. Preserve that comment when you add one.

### Ids are client-generated

`BaseEntity` assigns `UUID.randomUUID()` in its constructor rather than letting the database
do it, so a phone offline can generate the id and re-send the same draft three times without
creating three rows. This is the foundation of the offline queue — do not replace it with a
generated identity. `@Version` gives optimistic locking; a stale version on update is a 409.

### Invariants that shape the data model

These are business rules the schema and services enforce together. Breaking one is a bug even
if the tests pass:

- **Stock is a ledger.** `stock_transactions` is append-only; nobody types a balance. The
  balance table is a cache the ledger overrules.
- **Purchase is not consumption.** Cost incurred counts consumption. Material appears as
  three separate figures — purchased, consumed, in inventory — that add up.
- **History does not move.** Attendance freezes the wage rate at verification, so a later
  revision cannot change last month's labour cost. A DPR freezes its figures when sent.
  Approved financial records are voided with a reason, never deleted.
- **Rolled-up figures are derived, not stored.** Dashboard tiles and DPR prefill are computed
  per call through each module's read API. A cached total is a second version of the truth.
- **Claiming work is its own act.** A quantity reaches the measurement book only when an
  engineer verifies the report, and only once.
- **A head count is not attendance.** On a site flagged `uses_outsourced_labour`, the day is
  recorded as counts per trade in `site_labour_counts` — no worker, no wage rate, no ledger
  posting, because the contractor bills for the work. The DPR prints them beside the muster
  roll and never inside its head count; `dpr_labour.outsourced` is what keeps the two apart
  once the report is frozen.

### Error contract

`GlobalExceptionHandler` owns every error body (RFC 7807); `server.error.include-message` is
`never` so nothing leaks around it. Throw `BusinessException` — 422 by default, with
`notFound`, `forbidden` and `conflict` factories for the other codes. Business rule
violations (negative stock, locked period, editing an approved record) are 422, not 400.

### Frontend

`features/<module>/` holds `api.ts` (TanStack Query hooks), Zod schemas reused by both the
form and the offline draft, routes, and components used only by that feature.

`shared/apiClient.ts` — a 401 triggers exactly **one** refresh attempt shared by every request
that raced into the failure. The shared promise is load-bearing: rotating the refresh token
twice reads as theft to the server and revokes the whole family.

`offline/` — Dexie queue, `saveOrQueue.ts` is the entry point for any mutation that must
survive no signal. A record that has gone out is marked as gone out and is not resent. A
server refusal, duplicate or locked month is surfaced to the supervisor as a question with two
answers (keep mine with a reason, or drop mine), not retried silently. A send that got **no
answer** never counts against the give-up budget: that budget is for a server saying something
unhelpful, and silence is not the server saying anything.

### The app opens without a network, and that shapes the session

Start-up does not depend on reaching the server. `shared/session.ts` keeps the last profile
the server confirmed in `localStorage`, and `restoreSession` returns `VERIFIED`, `CACHED` or
`SIGNED_OUT` rather than a nullable user — because the caller has to do three different
things. The distinction the whole feature turns on is *how* a refresh failed: an answer that
refuses signs the device out, and no answer at all means a valley and must leave the stored
refresh token exactly where it is. Clearing it on a network failure was the original bug, and
it stranded the queue as well as the session.

`OFFLINE_SESSION_GRACE_MS` (14 days) caps how long a cached profile opens the app. The cached
profile grants nothing — every permission on it was already advisory, and the server
re-checks each one — so treat it as UX, exactly like `shared/roles.ts`.

Two things follow that are easy to get wrong:

- **`navigator.onLine` is not evidence.** A document loaded while the connection was already
  gone reports itself online for its whole life, and no `online` event ever fires. Anything
  that must recover from that needs the foreground (`visibilitychange`) and timer triggers
  too — `SyncProvider` and `AuthProvider` both carry all three.
- **Every refresh shares one promise**, inside `refreshSession`. The queue drain and the
  session re-check fire on the same reconnect, and rotating one refresh token twice reads as
  theft to the server, which revokes the whole family.

Read caching is an allow-list in `vite.config.ts`: reference data and the attendance roster,
nothing else. Stock, dashboards, expenses and DPRs are never cached — a stale figure is worse
than a missing one. The caches are cleared in `forgetSession`, since the worker keys responses
by URL and a site handset changes hands. React Query queries run in `offlineFirst` so an
offline read actually reaches that cache instead of being paused before it fires.

## Database changes

Additive migrations only — an applied migration is **never** edited. Add `V15__…sql` in
`backend/src/main/resources/db/migration/` and restart the backend.

Before a migration has been applied anywhere, `./scripts/dev.sh reset-db` replays from the
baseline (it runs `mvnw clean` first, because stale migration copies in `target/classes`
otherwise collide with renamed ones).

`db/seed/V900`–`V903` is dev sample data, loaded only by the `dev` and `test` profiles'
Flyway locations. The `prod` profile never sees the folder. Tests run migrations **and** the
seed, so a broken seed fails the build rather than a deployment — and tests authenticate as
the seeded users (`viplove`, `uttam`, `vivek`; password `Nirman@123`, local only).

That split is why `V14` exists. Units, materials and the expense taxonomy lived only in the
seed, no screen creates them, and the `prod` profile skips the seed — so a deployed
organisation had empty material and expense-head pickers on every screen with no way to fill
them. `V14__starter_master_data.sql` gives **every organisation that lacks them** the CPWD
starting catalogue, guarded by code so it never overwrites an organisation's own rows and
inserts nothing in dev and test (where the organisation itself only arrives in `V900`,
afterwards). Org-specific data — users, projects, BOQs — still never goes in a migration.

Hibernate is `ddl-auto: validate`. Flyway owns the schema; an entity that drifts from a
migration fails at startup.

## API conventions

Base path `/api/v1`, JSON only, OpenAPI at `/swagger-ui.html`. Full spec in
`docs/05-api-spec.md`. Worth knowing before writing an endpoint:

- Transaction-creating POSTs accept `Idempotency-Key`; repeats return the original response.
- Mutating PUT/PATCH requires `version` in the body.
- Pagination is `?page=0&size=25&sort=createdAt,desc`, returned as `PageResponse`.
- Filtering uses explicit named params per endpoint. No generic query DSL is exposed.
