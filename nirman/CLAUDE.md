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
- **An expense is typed at a site; that is not the same as being the site's cost.** The lorry
  of sand is, the accountant's salary is not, and both are typed by somebody standing at a
  site — so booking every expense against its site overstates the job, the same double-count
  docs/09 chased out of material and wages arriving through a different door. `cost_allocation`
  (V36) carries the answer: SITE, COMPANY, or SPLIT, and the split stores **one** number
  (`site_share`), the company's being the remainder, because two stored halves disagree the
  day a total is corrected. **The decision belongs to the approver**, in the same call — deciding
  to spend the company's money and deciding that it was the company's are one question, which is
  why the approval carries it and no permission was minted for it. The head *proposes*
  (`expense_categories.default_allocation`, COMPANY for office and staff heads), so the question
  costs nothing on the two hundred obvious rows and is still in front of him on the diesel bill.
  Re-deciding it afterwards is a different act by a different person — the accountant reading the
  month holds no approval permission at all — and is the one new permission, `expense:allocate`,
  refused once the site is CLOSED. Two things it may not do: a split of the whole amount or of
  none of it (that is SITE and COMPANY, and two spellings of one fact make a register disagree
  with itself), and anything but SITE on a material-purchase or wage head, whose value is already
  carried by that site's store and that site's muster.
- **An approved expense is re-opened, not edited and not always voided.** Voiding and re-booking
  is right when the expense should not have existed and wrong when a figure was typed badly: the
  replacement carries a new number, the vendor's bill and the system stop agreeing about what the
  record is called, and the supervisor who typed 45,000 for 4,500 learns to telephone the office
  instead of using the screen. `POST /expenses/{id}/revise` keeps the number, cancels the
  approval, and sends it back through the same chain — so what stands is what somebody signed
  again, and `revision` says how many times. It is refused once **cash has gone out** (paid and
  payable are computed against the total, and moving it under a payment already made is how a
  supplier's ledger stops matching his bills), to anybody but the author or an administrator, and
  into a closed period or site. The allocation resets to the head's proposal, because an
  allocation is a decision about an amount and the amount is what is being changed.
- **Rolled-up figures are derived, not stored.** Dashboard tiles and DPR prefill are computed
  per call through each module's read API. A cached total is a second version of the truth.
- **Claiming work is its own act.** A quantity reaches the measurement book only when an
  engineer verifies the report, and only once.
- **The daily report has two authors, and the split is enforced.** The supervisor records what
  the day *was* — whether the site worked, in what conditions, what plant stood on the site, and
  through the entry cards the muster, material and bills still missing — and hands it over; the
  engineer records what was *built* and signs. **The line runs where claiming does**: a quantity
  is a claim against the contract and a mixer's running hours are not, which is why plant is the
  supervisor's and sits on step one beside the weather rather than under the work.
  `dpr:draft` and `dpr:verify` already named those two people, so the line
  needed no new permission, only enforcement in `DprService.update`: a caller without
  `dpr:verify` may not write work items or the narrative, and a `SUBMITTED` report is
  writable **only** by a `dpr:verify` holder, because a handover he could not finish would leave
  every report half-written. The two halves are applied separately rather than checked together
  — that is what stops a supervisor's save, sending an empty work list because his screen has no
  work step, from deleting lines the engineer put on a report he sent back. The supervisor's half
  freezes at the handover along with the snapshot. The "a report that says nothing" check moved
  from `submit` to `decide` with it: nobody has written the work half at handover time.
- **The observations step asks two questions, and used to ask seven.** What survives is
  instructions received from the department and the plan for tomorrow — the two things the
  office cannot learn from any other record. The summary of work restated the work rows above
  it; delays, safety, quality and the note to management were prose the site was asked for every
  evening and answered with "nil", and a form that asks more than it needs teaches the man
  filling it that nothing on it is worth reading. **The columns stay.** Reports were signed with
  those fields in them, and the PDF and the register panel print each one only when a report
  carries it — so the old reports read as they always did and the new ones are not a page of
  dashes.
- **A day the site did not work is a different report, not an empty one.** It carries a cause off
  a closed list (`NonOperationalCause`) and no work items at all, and it is handed over and
  signed like any other. Free text would not do: "nine days to rain in July" is a claim against
  the department and "some days, see the notes" is not, so the cause is the countable half and
  the note beside it is the half a person reads. Records behind such a day — a watchman on the
  muster, a lorry at a shut gate — are **shown and not refused**; the man on the site is the one
  who knows. Only the work item is refused, because it is the row that reaches the measurement
  book. And the refusal runs both ways: a day already carrying measured work cannot be turned
  into a day nobody worked, because if a quantity was measured the site worked.
- **A head count is not attendance.** On a site flagged `uses_outsourced_labour`, the day is
  recorded as counts per trade in `site_labour_counts` — no worker, no wage rate, no ledger
  posting, because the supplier bills for the work. Hours are recorded (per man, nullable,
  and null is not zero) and stay unpriced: nothing multiplies them by a rate. The DPR prints
  them beside the muster roll and never inside its head count or its hours —
  `dpr_labour.outsourced` and `daily_progress_reports.outsourced_man_hours` are what keep the
  two apart once the report is frozen. The screen calls this **external labour** and no
  longer asks which contractor supplied it; the column is still stored and still carried
  through a re-save, waiting on contractor onboarding.
- **Plant is held, not consumed.** A mixer is at the site in March and in June, so
  `site_equipment` is its own register and no equipment row ever reaches
  `stock_transactions` — a posting would report the mixer as used up by the slab it poured
  and leave the store's balance claiming there is nothing to pour with. Anybody posted to the
  site may enter a machine (`equipment:create`) and only an administrator accepts it
  (`equipment:approve`) or removes it (`equipment:write`); an administrator's own entry
  arrives accepted, because a queue of his own rows is one he learns to click through.
  **Correcting is shared, and re-opens.** Anybody holding `equipment:create` corrects any row
  standing at a site he is posted to — its description or its photograph — because an entry he
  may create and never amend leaves him reporting a broken mixer by telephone. It was his own
  entries only until the shift roster made a nonsense of that: the man who wrote the mixer down
  in March is on another site in June, and the one who can see the broken jaw was the one
  refused the row. His correction is not quiet: `SiteEquipment.reopen()` puts the row back to
  PENDING and clears the decision with it, so nothing reaches the register unread — which is
  what makes widening it safe. The office's own correction does not re-open, for the same
  reason its own entry arrives accepted.
- **The field may name a thing, never value it.** A material off a challan
  (`POST /materials/field`) and an expense head off a bill (`POST /expense-categories/field`)
  both create real rows marked `provisional`, both refuse everything that carries a number or
  a consequence — rate, HSN, the two expense cost flags — and both answer a name the
  organisation already holds with the row it already holds, because two rows for one thing
  split one figure into two that never add up. **Naming has a second half: unnaming.**
  `PUT /materials/{id}/field` lets the same permission correct the name it gave — the name and
  nothing else, not even the unit, because changing what a material is measured in re-reads
  every quantity ever booked against it. The correction marks the row `provisional` again, so
  the office reads the new name; an office caller does not re-open it. Without that half,
  "celment" sits on every picker until somebody notices and the man who typed it routes round
  his own mistake by naming a second row — the split balance reached from the inside.
- **A wrong stock figure is reported, never edited.** `stock_transactions` is append-only, and
  an ADJUSTMENT is `inventory:adjust`, the office's, because a role that can move a balance can
  hide a loss. That left the one man who can see the shed unable to say anything about it. So
  `stock_correction_requests` (V35) holds a *request*: `inventory:correct` raises one, it posts
  nothing, and an administrator accepting it calls `StockAdjustmentService.adjust` — the same
  period lock, the same refusal to go negative, the same signed row in the store's history. The
  accepted request keeps `posted_txn_id`, so "what did the office do about my count" has an
  answer, and a refused one keeps its reason, because a request that vanishes when it is turned
  down is a shed nobody counts twice. One open request per store and material: two are how the
  same correction gets posted twice.
- **A site is never without a store.** `SiteService.create` gives every new site one, named
  `site-<site code>` after it, because a store is not a decision anybody was making and an
  empty store picker strands a lorry at the gate. The Stores screen is for the second store
  and the third. A store the ledger has touched is refused deletion by `StoreDeletionGuard`
  and goes inactive instead.

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

`shared/siteSelection.ts` — **the selected site is app-wide, not per screen.** Every screen
with a site picker calls `useSelectedSite(sites)` and gets back the `[value, onChange]` pair a
`useState` used to give it; the choice is shared through one store, persisted in
`localStorage`, and dropped by `forgetSession` because a site handset changes hands. Screens
that can also show every site pass `{ allowAll: true }`, and choosing "every site" there is
local — widening a register for a minute is not a statement about where somebody is working.
The id is a convenience and never an authorisation: every request carrying it is scoped by
`SiteAccessGuard` on the way through.

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

Additive migrations only — an applied migration is **never** edited. Add `V16__…sql` in
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

`V15` is the other half of the same problem: a catalogue is never complete, and a lorry at the
gate does not wait for the office. `POST /materials/field` lets somebody holding
`masterdata:provisional` name a material with nothing but a name and a unit; the row is marked
`materials.provisional` until an administrator edits it, and a name the org already holds comes
back as the material it already holds rather than as a second row — two rows for one cement
would split its stock into two balances, neither of them the amount in the shed.

`V24` and `V25` are the same argument in two places: the field has to be able to say a thing
exists without being able to price it. `V24` adds `expense_categories.provisional` and
`masterdata:provisional:head` (V15's twin, one screen along); `V25` adds `site_equipment` and
its four permissions.

`V34` gives the daily report the day it did not happen: `site_operational`,
`non_operational_cause` and `non_operational_note`, with `ck_dpr_operational_cause` keeping the
flag and the cause agreeing for the write that never goes through the service. It adds **no
permission** — the two-author split it comes with is drawn between `dpr:draft` and `dpr:verify`,
which V2 already seeded.

`V36` adds whose cost it is — `expenses.cost_allocation` and `site_share`, the revision columns
behind re-opening, and `expense_categories.default_allocation` with the office heads and a new
`OFFICE-STAFF` set to COMPANY. **One new permission**, `expense:allocate`, for re-deciding
afterwards and not for deciding: the approver's answer rides on his approval. The catalogue half
is guarded by code exactly as V14 is, and repeated in `db/seed/V905` because the dev organisation
only arrives in V900 — after every migration — and a demo whose office bill defaults to the site
is a demo of the bug.

`V35` is the third turn of the same screw, on the one register where the field could not be
given the write at all. `stock_correction_requests` lets somebody holding the new
`inventory:correct` say a stock figure is wrong; it stores no stock, and accepting one posts an
ordinary ADJUSTMENT through `StockAdjustmentService`. **One new permission, not two** —
approving a correction *is* posting the adjustment, so it is held by `inventory:adjust`, which
is already exactly that question. A second one would let an organisation grant the decision to
somebody who cannot make the posting it commits them to.

Hibernate is `ddl-auto: validate`. Flyway owns the schema; an entity that drifts from a
migration fails at startup.

## API conventions

Base path `/api/v1`, JSON only, OpenAPI at `/swagger-ui.html`. Full spec in
`docs/05-api-spec.md`. Worth knowing before writing an endpoint:

- Transaction-creating POSTs accept `Idempotency-Key`; repeats return the original response.
- Mutating PUT/PATCH requires `version` in the body.
- Pagination is `?page=0&size=25&sort=createdAt,desc`, returned as `PageResponse`.
- Filtering uses explicit named params per endpoint. No generic query DSL is exposed.
