# 08 — Implementation roadmap

Phases 1 and 2 are complete. Each later phase ends with working code, migrations, tests, seed data and an updated README before the next one starts.

| Phase | Scope | Exit criteria |
|---|---|---|
| **1 — Architecture and scaffolding** ✅ | Assumptions, architecture, ER model, schema (one baseline migration), API spec, permission matrix, screen list, folder structure, Docker Compose, backend and frontend scaffolds | `./scripts/dev-db.sh` then `./mvnw spring-boot:run` applies the baseline; both apps build |
| **2 — Foundation** ✅ | Auth with refresh rotation, roles and permissions, projects, sites, stores, user-site assignment, master data CRUD, audit framework, attachment service, seed data | Login works end to end; a supervisor is provably blocked from an unassigned site by an integration test; Swagger lists every foundation endpoint |
| **3 — Labour** ✅ | Workers, wage history, roster, bulk attendance, hour and overtime calculation, verification workflow, period lock, **worker advances, wage ledger and settlement**, labour reports, Excel export | Attendance calculation tests pass, including night shift and half-day; duplicate attendance is rejected; wage revision provably does not alter a locked month; **verifying the same attendance row twice does not pay the worker twice; earned − advance = payable reconciles against the field sheet** |
| **4 — Inventory** ✅ | Material master and conversions, opening stock, goods receipt, issue, transfer lifecycle, wastage, physical count, ledger, weighted-average valuation, **material estimates and estimated-vs-actual variance**, stock reports | Negative stock is rejected and logged; moving-average rate is correct after mixed-rate receipts; a transfer is counted at neither site while in transit; **variance is computed only over the BOQ scope an estimate covers, with uncovered scope shown separately** |
| **5 — Expenses and cash** ✅ | Expense submission, bill upload with threshold rule, duplicate detection, two-level approval, partial payments, vendor balances, site advances, settlement, reports | Approved expense cannot be silently edited; duplicate invoice returns 409 with candidates; approved cost, cash paid and payable reconcile |
| **6 — DPR and dashboards** ✅ | DPR with auto-prefill from the other three modules, engineer verification, PDF, admin and site and data-quality dashboards, charts | DPR prefill matches the underlying records exactly; dashboard shows material purchased, consumed and inventory value as three separate figures that add up |
| **7 — Offline and hardening** | IndexedDB drafts, sync queue, idempotency, image compression, conflict resolution UI, security review, indexes and query tuning, deployment guide | The same draft sent twice creates one row; a conflict surfaces a resolvable prompt; Playwright covers mark-attendance and add-expense offline then online |

## Working agreement for each phase
1. I state what the phase implements and list every file created or modified.
2. Complete files only — no snippets, no `// TODO: implement`.
3. Migrations are additive. An applied migration is never edited; a correction is a new version.
4. Tests ship with the code, including the named business-rule tests above.
5. Seed data is extended so the new module is usable immediately after `docker compose up`.
6. Architecture changes get proposed and agreed, never made silently.

## What Phase 2 actually shipped

Identity and site scoping, which every other module now builds on.

- **Auth** — `POST /auth/login` with BCrypt-12, lockout after 5 failures, 15-minute access
  token, 30-day opaque refresh token stored only as a SHA-256 hash. Refresh is single-use
  with rotation; presenting a rotated token revokes the entire family and writes a
  `TOKEN_REUSE` audit row.
- **Authorisation, three layers.** Permission codes are rows (migration `V2`), granted to
  the four system roles, carried in the JWT and enforced by `@PreAuthorize` on services.
  Site scope is a separate fence: `SiteAccessGuard` reads the token's `sites` claim, then
  **re-validates against `user_site_assignments`**, because an assignment can be revoked
  inside a live token's lifetime. The claim alone can say no; only the database can say yes.
- **Admin and accountant carry an `ALL` sites claim** rather than an enumerated list — their
  visibility is company-wide by the matrix, and their writes are fenced by permission codes.
- **Modules** — identity (users, roles, assignments), project (projects, sites, stores),
  masterdata (units, materials with unit conversions, vendors, contractors, categories),
  audit (append-only, plain JDBC, `REQUIRES_NEW` so a rolled-back business transaction
  cannot erase the evidence of the attempt), attachment (MinIO, signed URLs issued only
  after a fresh site-access re-check).
- **Frontend** — real login form, in-memory access token plus persisted refresh token,
  a single shared refresh promise so eight concurrent 401s cannot rotate the token eight
  times and trip the server's reuse detection, and a route guard over every screen.

**Deferred deliberately.** Two-level approval flows and the period-lock write paths have
their guards in place (`PeriodLockGuard`, `approval_rules`) but no business callers yet —
they arrive with the modules that need them, in Phases 3 through 5.

## Phase 3 — what shipped

Every exit criterion named above is met and covered by a test that says so by name.
58 backend tests and 36 frontend tests. What is built:

- **The calculation engine** (`AttendanceCalculator`) is a pure function with no Spring and
  no database, so all twenty of its rules are unit-tested in milliseconds. Per-site shift
  length, night-shift rollover, half-day, the three wage types, and overtime at the stored
  rate with no invented premium.
- **Worker master** with pay history and postings, both as revisions rather than edits: a
  rate change closes the open row and opens a new one, and a worker has at most one open
  posting.
- **Attendance** roster → bulk save → submit → verify. The client generates the record id,
  so a batch re-sent over a bad connection reports `UNCHANGED` instead of duplicating.
  A different id for a worker already marked that day is a genuine duplicate and is
  rejected per entry, so one bad row cannot sink a roster of forty.
- **The wage ledger** is append-only. Verification freezes the rate onto the row and posts
  earnings once — guarded twice over, by a check and by `uq_wle_attendance_posting`.
- **Advances** post to the ledger on approval, and only when recoverable: footwear the
  contractor has decided to bear is recorded as handed over without being charged to the
  worker.
- **Period lock** closes a month; verified rows become `LOCKED`, and the response reports
  how many rows were still unverified so nobody closes a month over incomplete data by
  accident.
- **Two reports**, split along the permission matrix rather than by convenience: the
  attendance register carries days and hours and needs `report:operational`, which every
  field role holds; the wage summary carries money and needs `report:financial`, which only
  the administrator and accountant hold. Both export to xlsx. Putting wages on the register
  would have quietly handed every supervisor the site payroll.
- **The mark-attendance screen**, phone first. Four-way toggle per worker with a
  mark-all-present shortcut, because the common case is forty men present and nobody should
  open forty dialogs to say so; times live behind a drawer for the few who need them. The
  drawer previews hours — including the midnight rollover — so a supervisor sees what he is
  about to record instead of discovering it at verification.

**Resolved along the way.** Document numbering — open question 1 in docs/09, and the one
flagged highest-risk — is settled for this module and ready for the rest. `V3` adds a
counter table and `DocumentNumberService` assigns `ADV-2025-0001` style numbers atomically
on arrival, because a disconnected device cannot invent a per-org sequential label without
two phones eventually choosing the same one.

**Corrections, and what was actually built.** Amending a row *after* verification now works,
without the bespoke approval flow that docs/09 open question 2 warns against. The permission
is the gate: `attendance:correct` (Admin and Engineer only) allows a direct edit that
recalculates against the rate frozen on the row, posts the difference to the worker's ledger
as an `ADJUSTMENT`, and writes a field-by-field trail to `attendance_corrections`. Rows are
written `APPROVED` by whoever made them; when the generic approval engine lands in Phase 5
they become `PENDING` on arrival with no schema change, which is what the unused approval
columns are for.

Before verification there is nothing to correct: draft, rejected and submitted rows stay
editable by the supervisor outright, because no wage is frozen and nothing has been paid.

## Phase 4 — what shipped

Every exit criterion named above is met and covered by a test that says so by name.
123 backend tests and 87 frontend tests. What is built:

- **One write path into stock.** `StockLedgerService` is the only thing in the codebase that
  writes `stock_transactions` or `stock_balances`, and it writes them together under
  `SELECT … FOR UPDATE`. That single choke point is what makes four separate guarantees hold
  at once: the balance cannot drift from the ledger, stock cannot go negative, the moving
  average is arithmetic rather than bookkeeping, and a document line posts once.
- **Weighted average, and the rule behind it.** The average moves on the way in and is frozen
  on the way out. Cement bought at ₹410 and ₹425 is the same heap and nobody at site can
  tell you which bag came from which lorry, so FIFO would be a fiction the software
  maintained alone. Deriving the average from value ÷ quantity, rather than accumulating it,
  is what stops a year of deliveries drifting by rounding.
- **Negative stock is refused and the attempt is logged** — in its own transaction, because
  the business transaction that made it is about to roll back and taking the evidence with
  it would blind the data-quality dashboard to precisely the events it exists to show.
- **Six documents, one ledger.** Goods receipt, issue, transfer, physical count, wastage and
  opening stock are six pieces of paper and one kind of ledger row. Stock moves at the
  accepting step, never at entry: a delivery note typed at the gate is a claim, and issuing
  material on the strength of an unchecked claim is how a store ends up short.
- **A transfer is counted at neither store while it is in transit.** Dispatch takes it out of
  the sender, receipt puts what actually arrived into the receiver, and in between it is a
  quantity on a lorry that nobody can issue. Leaving it at the sender lets two storekeepers
  promise the same forty bags to two work faces; crediting the receiver early lets him issue
  material that is still on the road. A shortage is a real outcome, recorded on the line.
- **`V7` adds the two guards that make a phone safe.** A partial unique index per document
  line and movement direction, so a re-sent receipt cannot deliver the same lorry twice; and
  one on opening stock, which is the only ledger row nobody can derive and therefore the one
  that must exist at most once per store and material. Same discipline as
  `uq_wle_attendance_posting` in labour, for the same reason.
- **Estimated versus actual, honest about its own coverage.** This is docs/09's completeness
  trap, and the one failure mode that would have destroyed the feature's credibility rather
  than break it. The Kausani cement take-off totals 263 bags against 460 received; reported
  naively that reads as a 75% overrun, when in fact the take-off covered RCC and two
  brickwork lines and never touched plaster or flooring. So variance is measured **only over
  the BOQ items the estimate covers**, consumption outside that scope is itemised beside it
  rather than folded in, and a `scopeComplete` flag says in one boolean whether the number
  can be read as the whole story. Steel, from a bar bending schedule covering the whole
  structure, is the case where it can — and the report says that too.
- **Three estimate levels stay apart.** What was tendered, what the drawings demand, and what
  site consumed are three different questions. The seed carries steel at 4,200 kg quoted
  against 4,550 kg on the BBS, because that 8% gap is the most commercially useful number in
  the set and collapsing the levels would hide it.
- **Five reports** — stock position, material consumption, low stock, transfer register and
  wastage — all reading the ledger and the balance cache and nothing else, each exporting to
  xlsx. Consumption keeps issued and wasted in separate columns: both leave the store and
  both are cost, but a site losing eight per cent of its cement to breakage needs that as its
  own number rather than buried in a total.
- **Three screens, phone first.** Receiving is in the units the challan is written in, because
  asking a storekeeper to convert tonnes to kilograms at the gate is asking him to make an
  arithmetic mistake on a lorry driver's schedule. Issuing picks from the store's own stock
  with the quantity alongside, and flags an overdraw as it is typed. Stock opens each row's
  ledger, because nobody disputes a stock figure in the abstract — they dispute it standing
  in the store looking at a different number, and what settles it is the movements behind it.

**Brought forward deliberately.** BOQ items arrive here rather than in Phase 6, as a
read-mostly aggregate with create and update behind `boq:write`. A material estimate is
scoped to a work item and an issue is charged to one, so estimates were unbuildable without
them. Dated progress entries and the measurement-book structure stay in Phase 6, where
`boq:progress:record` belongs — entering the contract and claiming work against it are
different acts.

**Two module read APIs added**, following the `SiteLookup` precedent: `MaterialLookup` for
unit conversions and reorder levels, `BoqLookup` for work items. Neither carries a permission
check of its own — the caller has already passed the inventory permission that got it there,
and gating a unit conversion separately would mean a storekeeper cannot book a delivery he is
allowed to book.

## Phase 5 — what shipped

Every exit criterion named above is met and covered by a test that says so by name.
162 backend tests and 99 frontend tests. What is built:

- **One approval engine, and docs/09 open question 2 closed.** That question found two
  approval systems coexisting — the generic `approvals` chain and a scattering of per-module
  status columns — and ruled that the engine is authoritative and the columns become *a
  denormalised cache written by the engine's event, never by a business service*. That is
  now literally true: `ApprovalService` is the only thing that decides an approval, it
  publishes `ApprovalDecided`, and `ExpenseApprovalListener` is the only caller of
  `Expense.applyDecision`. No service anywhere sets `L1_APPROVED` by hand.
- **The event fires inside the deciding transaction**, not after commit. The approval row and
  the status column have to move together; anything looser leaves a window where the chain
  says approved and the expense says submitted, and a crash inside it makes that permanent.
- **Routing is data.** `approval_rules` decides how many levels a record passes and who they
  belong to. The seeded configuration sends every expense past the engineer and anything from
  ₹25,000 past the administrator too. An organisation that has configured nothing gets one
  administrator level rather than a free pass — silence in a settings table must never read
  as consent.
- **`V8` freezes the amount onto the chain.** Whether a level 2 exists is a function of the
  amount, so deciding level 1 has to know what the record was worth. Frozen at submission,
  so a threshold raised next week cannot retroactively excuse a record already sitting in
  somebody's queue — the same rule that freezes a wage at verification.
- **An approved expense cannot be silently edited.** Draft, returned and rejected rows are
  the author's; anything submitted is not. Correcting an approved expense means voiding it
  and booking the replacement, and both stay on the record.
- **Duplicate detection that catches the real mistake.** Two checks: exact vendor-and-bill
  number, and a near miss on vendor, amount and date within a week. The second is the one
  that matters, because the field writes "Local", "NIL" or "-" in the bill box for a great
  many cash purchases and a placeholder collides with nothing (docs/09). The answer is 409
  **with the candidates attached**, and `force=true` plus a stated reason books it anyway —
  refusing without saying what it collided with sends somebody hunting through a month of
  paper for a row the server already had in hand. Vendorless expenses are checked too, which
  the database index alone never managed.
- **Three amounts that reconcile and are never merged.** Approved cost, cash paid, payable.
  Partial payment is the normal case, so payments are rows rather than a running figure —
  which is what makes payable ageing possible at all. A payment cannot exceed what is still
  owed, and `ck_expense_paid_only_when_approved` (V8) is the backstop under the sentence.
- **Site floats, and the control that makes them work.** A holder submits a settlement —
  these are the bills, this is the cash back — and the office approves it. The float moves on
  the decision, never on the claim, so a supervisor cannot clear his own balance by asserting
  it. `uq_ase_expense_settled_once` (V8) stops the same ₹8,000 bill clearing two floats.
- **The double-counting guards finally have callers.** `is_material_purchase` and
  `is_labour_payment` have sat unused since Phase 2. The expense register now reports four
  figures and lets nobody merge them: `cost incurred + material purchases + labour
  disbursements = total booked`. Material purchase becomes inventory and is costed again at
  issue; money handed to a worker settles a wage already costed through attendance. Report
  one total as project cost and Kausani is overstated by most of ₹4,99,528 on the labour side
  alone.
- **Three reports** — expense register, payable ageing, advance balances — all behind
  `report:financial`, all exporting to xlsx. The operational roles get quantities; money
  stays with the administrator and the accountant, the same split that keeps the wage summary
  away from the attendance register.
- **Two screens.** Adding an expense enters the amount and the tax rate separately, as the
  bill states them, and shows the total it derives. The approvals screen puts the decision and
  the payment on one page for the people who do both, with the level and the role on every
  row — a bill past the engineer and waiting on the office looks identical to an untouched one
  without it.

**Deliberately narrow.** `expense_settings.admin_approval_above` is now a historical column
and is not read by the flow; routing lives in `approval_rules` alone, because two homes for
one threshold is exactly the drift docs/09 question 2 was about. Bill photographs attach
through the existing attachment service rather than a new upload path.

## Phase 6 — what shipped

Every exit criterion named above is met and covered by a test that says so by name.
211 backend tests and 133 frontend tests. What is built:

- **The prefill is derived, never stored, and that is the feature.** The criterion is that it
  matches the underlying records exactly, and the only way to keep that true through a late
  correction, a rejection or a worker added to the muster at five o'clock is to compute it on
  every call. A cached daily total would be a second version of the truth, and the version
  people would notice is whichever one was wrong. `DprPrefillIntegrationTest` checks every
  figure against the same figure summed straight out of the tables in SQL, because comparing
  the API against hand-written constants would only prove somebody typed the same number twice.
- **Three read APIs rather than three joins.** `LabourLookup`, `InventoryLookup` and
  `ExpenseLookup` follow the `SiteLookup` precedent from Phase 4: the DPR and the dashboards
  ask each module what it knows, and neither reaches into another module's tables. That is what
  stops the dashboard becoming a fourth opinion about what a project cost.
- **The document freezes at submission, not at save.** A verified report is what an engineer put
  his name to, so from then on the figures are the document's own. A correction to an attendance
  row behind it shows up as a difference rather than by rewriting the report — tested by editing
  the muster under a verified report and checking the report did not move. Before submission
  there is nothing to protect and a draft tracks the records, which is what makes the prefill
  useful right up to the moment it is sent.
- **Verifying is what claims work.** A measured line reaches the measurement book at
  verification and never before: entering the contract and claiming against it are different
  acts (the note Phase 4 left when it brought BOQ items forward). A line described without a
  quantity is recorded and claims nothing, because shuttering struck and a site cleared are real
  work and not claims. `V9`'s `uq_boq_entry_dpr_item` is the backstop under the service's own
  check — the same discipline as `uq_wle_attendance_posting` in labour and the V7 partial
  indexes in inventory, and for the same reason: a double click must not bill the client twice.
- **Material as three figures that add up**, with every term of the identity on the response —
  `opening + received − consumed − residual = inventory value` — and the residual printed even
  when it is zero, because a reconciliation you only see when it fails is one nobody believes
  when it passes. **Purchased sits beside them and is not one of them.** It comes from the bills
  rather than the ledger, so it is not expected to equal what was received: freight is booked
  separately and a bill and its lorry rarely arrive the same day. The test books a ₹40,000
  cement bill into the window and checks it raises cash booked and leaves cost incurred alone,
  because before it the two figures coincide and a dashboard that blended them would have passed.
- **Cost and cash never share a tile.** `costIncurred` is labour plus material consumed plus the
  expenses that are neither a material purchase nor a wage payment, and it is the only total
  that may be set against a budget. `totalBooked` is what left the books. Phase 5 established
  that split for the expense register; the dashboards now carry it with the caveat attached, so
  a reader cannot mistake which one they are looking at.
- **A data-quality dashboard that is a work queue.** Every finding carries what to do about it
  and the examples behind the count, at one of two severities. "Eleven days unmarked" is a
  complaint; eleven dates are a morning's work. Two levels and not five, because a five-level
  scale invites arguments about whether something is a three or a four and nobody acts on a three.
- **Five screens.** The DPR wizard opens on what the records already say and asks only for what
  the numbers cannot know — what got built, what went wrong, what happens tomorrow — with the
  provisional wage bill flagged on its face. The reports list puts the measured lines in front of
  the engineer before it puts the verify button, because signing a report you have not read is
  easy when the screen makes it easy. The company and site dashboards draw the material identity
  as an equation a reader can check on the screen, and the cost trend is three stacked series
  rather than one line: a week where labour held steady and material consumption tripled is a
  different story from one where both rose, and the blended line looks identical in both.

**A four-phase-old bug, found by being the first caller.** Every master-data list — vendors,
contractors, materials — answered 500 to a request with no search term, which is the request
every screen makes on page load. The three search queries opened with a bare `:q IS NULL`,
giving PostgreSQL nothing to infer the parameter's type from; a null term went across untyped,
the driver settled on `bytea`, and `lower(bytea)` does not exist. Searching *for* something
worked, which is why it survived from Phase 2 — every test that existed passed a term. The fix
is `CAST(:q AS string)`, and `MasterDataListingIntegrationTest` now covers the boring case.

**Deliberately narrow.** Photographs attach through the existing attachment service rather than
a new upload path, and the wizard's fourth step waits for Phase 7's image compression. The PDF
renders from the frozen snapshot rather than from today's records, which is the whole point of
freezing it.

## Next: Phase 7

Offline and hardening: IndexedDB drafts, the sync queue and its idempotency, image compression,
conflict resolution, a security review and query tuning. The client-generated ids that labour,
inventory, expense and now the DPR all take on arrival are what that phase is built on.
