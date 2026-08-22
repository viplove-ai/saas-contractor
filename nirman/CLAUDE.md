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
  `dpr:verify` may not write work items or the narrative, and a `SUBMITTED` report is writable by
  both of them at once — the engineer for the work, because a handover he could not finish would
  leave every report half-written, and any `dpr:draft` holder posted to the site for the day's
  account. The two halves are applied separately rather than checked together — that is what
  stops a supervisor's save, sending an empty work list because his screen has no work step, from
  deleting lines the engineer put on a report he sent back. The "a report that says nothing"
  check sits on `decide` rather than `submit`: nobody has written the work half at handover time.
- **The day's account stays with the site until the report is signed; the figures freeze at the
  handover.** Two different promises, and only the second one is about the snapshot. Whoever
  holds `dpr:draft` at that site may still correct the weather, the operational status and the
  plant on a `SUBMITTED` report — the same supervisor or the one who relieved him, because a
  site is a shift roster and the second mixer that arrived at four is a thing he can see and the
  office cannot. It used to freeze at the handover, and the correction went by telephone. The
  **engineer** may not write that half at any point (`assertSupervisorHalfUntouched`): he signs
  somebody else's account of a day he may not have been standing on. `Workflow.acceptsTheDaysAccount()`
  is the one place the rule lives. The rolled-up figures still freeze at submission, and
  `refreshSnapshot` runs only before the handover — a muster corrected afterwards shows up as a
  difference between the document and today's records, which is information, not a fault.
- **The engineer's signature claims the work; the office's approval ends the document.** `VERIFIED`
  is no longer terminal: `dpr:approve` (V37, the office's alone) countersigns it and the report
  becomes `APPROVED`. The approval **claims nothing** — the measured quantities reached the
  measurement book at verification and stay exactly where they were, because the man who measured
  the work is the man who can answer for it, and holding the claim back until an office acted
  would put the contract's progress with somebody who was not there. What it buys is the recorded
  moment of acceptance the office never had: before it, a fortnight of signed figures was first
  met in a monthly return. It is refused on anything but a `VERIFIED` report and refused twice.
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
  them beside the muster roll and never inside its wage cost or its hours —
  `dpr_labour.outsourced` and `daily_progress_reports.outsourced_man_hours` are what keep the
  two apart once the report is frozen. **The men, though, add.** "How many men stood on the
  site" is one question, and the department asks it of the site and not of the payroll — so
  `DprResponse.menOnSite` is `labour_present_count` plus `outsourced_head_count`, derived per
  call like every other roll-up and stored nowhere, and it is the head count the register, the
  panel, the wizard and the PDF lead with. What refuses to add is anything with a rate behind
  it: a man-hour on the muster has one and a man-hour at the gate has none, so the hours stay
  in their own columns and the total prints with its two halves beside it rather than in place
  of them. Do not reuse the name — `DayCountsResponse.totalHeadCount` is the suppliers' men
  alone. The screen calls this **external labour** and asks which supplier sent the men, off
  the one supplier register (V23); naming him stays optional, because a count with no name on
  it is still a true count.
- **A labour payment settles a wage only where a wage was costed.** `is_labour_payment` keeps
  money handed over for wages out of cost incurred, because verified attendance already
  counted it. On a site flagged `uses_outsourced_labour` there is no muster and nothing was
  costed, so the same head means the opposite: the supplier's bill is the only record of what
  the labour cost, and excluding it reports a site whose men were free. So the flag is read
  against the site — `SiteLookup.outsourcedLabourSites` in `ExpenseLookupService` and
  `ExpenseReportService`, and the register row is named `wageSettlement` for what it decides
  rather than for the flag it starts from. The bill lands in `costIncurred` instead of
  `labourDisbursements`; the four figures still add to total booked, which is what keeps the
  omission checkable. Its sibling, `is_material_purchase`, is untouched — a bag of cement
  becomes inventory whoever laid it.
- **The plant on the report is priced by whoever it went to, never by the site.** `dpr_machinery`
  carried hours and no money until V39, and the supervisor still writes only the hours: he
  watched the mixer run, but the hire agreement is not his and a rate box on his screen is a
  number guessed at seven in the evening — the same line V15 and V24 draw, that the field may
  name a thing and never value it. `PUT /dprs/{id}/plant-rates` is its own call because the
  plant rows are the supervisor's half and the rate on them is not, and it carries **no new
  permission**: `dpr:verify` and `dpr:approve` already name the two people a handed-over report
  goes to. Refused on a draft (nobody has been given it, and the list is still moving) and on an
  APPROVED report (a figure that can still move is not a signed one). The amount is derived per
  call from the rate and the row's own hours, so a corrected seventh hour carries into it; an
  hourly rate charges the hours **run** and not the hours stood, because standby is agreed
  separately and charging it at the running rate would invent the figure docs/09 refused to
  invent. And the rate survives the supervisor's later correction of the plant list —
  `replaceMachinery` rebuilds the table and carries rates across by the machine's name, because
  otherwise the second mixer arriving at four deletes the office's figure. A machine renamed
  loses its rate, which is right: what was priced was "JCB 3DX".
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
- **A measurement is not a daily report, and the split is the whole billing feature.**
  `boq_progress_entries` says what a day *reported* — the supervisor describes it, the engineer
  signs it in the evening, and it drives the dashboards. `measurement_sheets` say what a tape
  later *measured*, and they drive the bill. Forcing the two into one act means either the daily
  record is signed late or the bill inherits a number recalled at seven in the evening, because
  the tape comes out days later — usually the week the AE is due. The two ledgers are allowed to
  differ and the difference is information, not a fault; reconciling them by posting automatic
  adjustments between them is the part that can quietly rewrite a signed figure, and it is
  deliberately **not built**. Nothing in `billing` writes `boq_progress_entries`.
- **The sheet is the unit, and its two totals must agree before it is signed.** A measurement
  sheet is a piece of paper: one contract item, a dozen ruled rows, and a total the engineer
  worked out by hand at the foot. `written_total` is that figure and `computed_total` is what the
  rows come to; signing while they differ is refused in `MeasurementService.sign` and by
  `ck_sheet_signed_agrees` under it. That one number catches a typing slip, a skipped row, a
  transposed 5.8 for 8.5, and his own arithmetic, and it needs no cleverness — only subtraction.
  **The browser computes the same total** so he sees the answer while typing, which makes
  `types.ts`'s `round2` a second implementation of `MeasurementLine.computeContents`: it rounds
  half-up on the decimal a human would have written, because `Math.round(v * 100) / 100` sends
  1.005 *down* and the server sends it up — and a client that disagrees tells him his sheet
  balances and then watches the signature be refused. Six shapes cover every line in a real bill
  (`nos × mult × L × B × H` and its four subsets, plus a length taken against a tested kg/m), and
  **a blank dimension is not a zero**: a linear item has no breadth.
- **A quantity is paid once, and a passed bill is what was paid.** `measurement_sheets.ra_bill_id`
  holds one value, so the second bill cannot see a sheet the first claimed — the double-payment
  guard is the column, not a rule somebody has to keep remembering. `ra_bill_items` is a snapshot
  written at the moment a bill is passed and read for ever after, so measuring more work moves the
  next bill and never this one. Everything the CPWA-26 form needs falls out of those two: "since
  previous" is this bill's own sheets, "up to date" is those plus every earlier bill's, and the
  previous-bill amount comes off the earlier snapshot — which is how a hundred and fifteen
  hand-typed figures per bill stop existing. A correction after a bill is passed is a **fresh
  sheet with negative rows** in the next bill; a bill sent back before it is passed re-opens
  through the same chain and counts the revision, exactly as an expense does.
- **The tender's details are asked once, at its first bill.** `agreements` holds what prints on
  every page of every bill of that tender — contractor, the officer who measured, who prepared
  and checked it, the division — and the rate chain. `RaBillService.create` refuses the first bill
  without them rather than printing a CPWA-26 with blanks on it. **The chain is stored, never the
  rate**: schedule rate × coefficient, + cost index, − tender percentage, each step rounded as the
  printed analysis rounds it. A cost index revised by circular then reprices every derived rate
  instead of needing forty cells found and changed, and the derivation can be *shown* when the AE
  questions it. That the source workbook retyped these into forty-three sheets is why it says
  "3rd RA Bill" on its measurement pages, "4th RA Bill" on its abstract, and carries a sheet named
  `MB1st RA`.
- **A tender is priced under an edition, and stays priced under it.** `reference_documents` is
  the shelf — schedules of rates, cost index circulars, specifications — with the file itself in
  `attachments` beside the row, so a rate questioned in March can be opened at its own page.
  What matters is not holding the latest edition but being able to say **which one a given
  tender was let under**, so `agreement_documents` stores that link rather than resolving
  "whichever is current" at read time: an agreement let in 2025 is a DSR 2023 agreement for as
  long as it runs, and a bill that repriced itself the day DSR 2026 was published would invent
  money in one direction or the other. `supersede` therefore moves a status and touches no
  agreement, and withdrawing an edition a tender still cites is refused — a document that
  vanished from under a live agreement would leave its bills unable to say what priced them.
  Registering an edition **without a file is allowed**: the office knows the notice cites DSR
  2023 the moment it reads it and may not have a copy for weeks. Only a `COST_INDEX` carries a
  single percentage (`ck_refdoc_index_percent`); a schedule has thousands of rates and no one
  number. **No new permission** — `dsr:manage` was minted for exactly this custody, and deciding
  which edition governs a tender *is* deciding its rates.
- **The notice already knew.** `NitPdfParser` has extracted `civil_dsr_year` and
  `civil_cost_index_percent` since the tender module was built, and `nit_documents` has been
  storing them with nothing able to use them — the agreement's rate chain was typed in by hand
  at the first bill, off the same PDF the system had already read. `NitLookup.rateBasis` closes
  that loop: the agreement form is *offered* the year and percentage the notice stated and the
  matching edition on the shelf, and the office confirms. It is a suggestion and never a
  decision, applied once and only into boxes still holding their defaults — typing over
  somebody's correction because a query resolved late is worse than not offering at all.
- **A BILLING_ONLY project still gets a site.** `projects.mode` marks a tender imported from a NIT
  to prepare bills and nothing else — no muster, no store, no daily report. `NitImportService`
  already produced exactly that shape (a project with `boq_items` and no sites), and the flag only
  decides what the screens offer. But `SiteAccessGuard` takes a site id, and a project with no
  sites has nothing to scope on; the alternative to giving it one is a second access model beside
  the first, which is how a system gets a hole in whichever of the two nobody updates. So it is
  given one site and the billing screens never show a picker — the same argument `SiteService`
  already makes about stores. **Assign the importing user to it in the same act**, or he creates a
  project he cannot read and the failure looks like a permissions bug.
- **The paper is generic, and its serial is the duplicate guard.** One master sheet design,
  printed in bulk, used on any tender — the engineer writes the item number and picks the project
  and item in the app when he enters the page. A per-project register would need a print pipeline
  and would go stale the day a deviation item was approved. The pre-printed serial is unique, and
  entering a page twice is refused: two people each assuming the other had not is how a quantity
  gets paid twice. The corner marks and dropout-coloured boxes on `measurement-sheet.html` cost
  nothing now and are what would let a photograph be read automatically later without reprinting
  the books — **nothing reads them yet**, and the photograph on a sheet is evidence, not input.
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

`shared/appUpdate.tsx` — **one owner of the service worker registration, and the app looks for
a new version itself.** `registerType: 'prompt'` parks a new version rather than swapping it in
under somebody mid-entry, which is right and is only half the story: a browser checks for a new
worker on a navigation, and an installed PWA on a site phone does not navigate. It is opened in
the morning and reopened onto the same document the next morning, so a version published on
Tuesday could sit unnoticed for a fortnight. So `AppUpdateProvider` asks by itself on the same
three triggers `SyncProvider` and `AuthProvider` use, and the profile screen carries the same
offer on a screen that does not go anywhere — the snackbar is dismissed once, and "Later" is not
"no". A second `useRegisterSW` anywhere is two Workbox instances racing to register one script,
and the offer would then be answered by whichever of them heard about it.

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

**A read cache belongs to the session, not to the tab, and there are two of them.** The one on
disk is the worker's, cleared at both ends of a session. The other is React Query's, held in
memory by a client created once for the life of the tab and keyed by what was asked rather than
by who asked — so signing out and signing in as somebody else handed the second person the
first one's sites, projects and figures, each fresh for a minute apiece and none of them
refetched. `AuthProvider` empties it on `signIn` and on `signOut`; on sign-out **after** the
state is dropped, because clearing under a mounted table is a refetch nobody is waiting for
sent with a token that has just been thrown away. Whether the handset has changed hands is
decided in `login()` against `nirman.lastUser`, which is deliberately the one thing
`forgetSession` does not clear: signing out erases the cached profile, so the sign-in that
followed had nothing to compare against and cleared nothing.

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

`V39` gives `dpr_machinery` a rate and the unit it is per — `HOUR` or `DAY`, and no `MONTH`,
because this is one day's report and a monthly rate needs a divisor that two screens would
assume differently. Checks keep the number and its unit together (one without the other is a
figure nobody can multiply) and keep a rate signed by somebody at a time. It stores **no
total**: the amount is derived on read, since the hours can still be corrected before the
signature. **No new permission** — see the plant-pricing rule above.

`V37` gives the report the step above the engineer. It widens `ck_dpr_workflow` to admit
`APPROVED`, adds `approved_at`/`approved_by` with a check that an approval is by somebody at a
time, and mints **one permission**, `dpr:approve`, for the office alone — not folded into
`dpr:verify`, because verifying is the engineer saying what was built and approving is the
office accepting it, and an organisation that got the second by granting the first would have a
two-signature document carrying one signature. It moves no measurement-book row: the quantities
were claimed at the signature and V37 does not touch them.

`V46` is the billing module: `projects.mode`, `agreements` (the tender's details and its rate
chain), `dsr_schedules`/`dsr_items`, `measurement_sheets`/`measurement_lines`, and
`ra_bills`/`ra_bill_items`. **Five permissions**, split between four different people —
`billing:read`, `billing:measure` (the engineer's, and deliberately not `dpr:verify`),
`billing:prepare`, `billing:sign` (separate from preparing for the same reason `dpr:approve` is
separate from `dpr:verify`), and `dsr:manage` (an administrator's alone: a rate is the multiplier
on every quantity in the document). ADMIN gets all five, ENGINEER read and measure, ACCOUNTANT
read and prepare — so no existing user's screens change until a permission is granted.

`V47` is the document vault: `reference_documents` (the shelf, with the PDF in `attachments`),
`agreement_documents` (which editions govern a tender — a stored fact, never a lookup), and
`dsr_schedules.document_id` tying parsed rates back to the edition they were read out of.
**No new permission**: `dsr:manage` already holds this custody, and `billing:read` covers
reading the shelf. See the two rules above for why superseding moves nothing.

**A note on JPQL and optional parameters.** `(:param IS NULL OR column = :param)` expands to two
placeholders, and Postgres cannot infer a type for the one standing alone in `? IS NULL` — it
refuses to prepare the statement with `could not determine data type of parameter`. The billing
repositories pass a typed boolean flag beside an always-bound value instead
(`:ignoreCutoff = TRUE OR s.measuredOn <= :cutoff`), so every parameter appears in a typed
comparison. Older queries elsewhere still use the `IS NULL` form; they work only because nothing
calls them down the null path.

Hibernate is `ddl-auto: validate`. Flyway owns the schema; an entity that drifts from a
migration fails at startup.

## API conventions

Base path `/api/v1`, JSON only, OpenAPI at `/swagger-ui.html`. Full spec in
`docs/05-api-spec.md`. Worth knowing before writing an endpoint:

- Transaction-creating POSTs accept `Idempotency-Key`; repeats return the original response.
- Mutating PUT/PATCH requires `version` in the body.
- Pagination is `?page=0&size=25&sort=createdAt,desc`, returned as `PageResponse`.
- Filtering uses explicit named params per endpoint. No generic query DSL is exposed.
