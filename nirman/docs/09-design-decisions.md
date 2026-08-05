# 09 — Design decisions after the field-data review

Three workbooks from the Kausani project (Dec 2024 – Jun 2025) were checked against the
schema: an expense sheet (330 rows, ₹25,20,120), a labour attendance sheet (5 months,
workers 100–118) and a material sheet (32 stock receipts plus steel and cement take-offs).

Two things came out of it: some of that data **could not have been entered** against the
schema as first drafted, and two capabilities the business needs had nowhere to live.

Both are folded directly into the `V1__initial_schema.sql` baseline rather than carried as
correction migrations — possible only because nothing had been applied or published at the
time. From the first `spring-boot:run` that file is frozen and a correction is `V2`. This
document records what changed and why, what was deliberately left out, and what is still open.

---

## What the field data proved

| Finding | Evidence | Outcome |
|---|---|---|
| Bill numbers are placeholders, repeatedly | `-`, `NIL`, `Local`, `Haldwani` — rows 8 and 9 are both `Local` + Shri Ji Traders | placeholders excluded from the uniqueness rule |
| Overtime is daily, not exceptional | one worker logged **102 OT hours in February** | mandatory per-record OT reason replaced by a threshold |
| Standard shift is 7 hours, not 8 | ₹17,321 = (194 ÷ 7) × ₹625 | `sites.standard_shift_hours` = 7.00 for this site (already configurable) |
| Overtime carries no premium | OT income ÷ OT hours = ₹89.00 = ₹625 ÷ 7 | assumption 7 confirmed — never hard-code 1.5× |
| Wage settlement is already practised | `Advance` and `Balance Payment` columns per worker per month | payroll settlement moves **into** scope |
| Labour payments are booked as expenses | `Karam 1000`, `Daily Labour Payment 3200` inside Labour ₹4,99,528 | `is_labour_payment` flag, or wages count twice |
| Quintal is the working unit | 591 qtl aggregate, 1,626 qtl coarse sand | seed a `QTL` unit (see Seed data below) |
| Material estimates exist but have no home | steel take-off 4,550 kg vs 5,075 kg received | `material_estimates` |
| Estimates are derived from coefficients | `Cement/Cum` = 5.0 for RCC, 0.63 for brickwork | `material_consumption_norms` |

---

## A. Worker settlement

The sheet computes, per worker per month, `Total Amount − Advance = Balance Payment`.
Karam, February: earned ₹26,399, advance ₹9,594, payable ₹16,805.

**Model.** `worker_ledger_entries` is append-only, exactly like `stock_transactions`. Nobody
types a worker's balance; it is what he earned less what he has drawn. `worker_balances` is
a derived cache, and the ledger overrules it.

- `WAGE_EARNED` / `OT_EARNED` are posted **at attendance verification**, from the rates
  already frozen in `applied_normal_rate` / `applied_ot_rate`. That snapshot was built so
  history cannot move; the ledger inherits it for free, and a wage revision still cannot
  rewrite a settled month.
- `uq_wle_attendance_posting` makes verification idempotent — verifying twice cannot pay
  twice, the same guarantee the stock ledger already gives.
- `worker_advances` is separate from `site_advances`, which issues petty cash to a **user**
  with a login. A worker is not a user.
- `is_recoverable` decides whether ration, footwear and medicine are deducted from the
  worker or borne by the contractor. It varies per item, so it is recorded per advance
  rather than assumed. This was the one question the sheets could not answer.

**The double-counting rule.** Money handed to a worker is settlement of wages already
counted as cost through attendance. A category flagged `is_labour_payment` is excluded from
cost incurred and reconciled against the worker ledger instead — precisely how
`is_material_purchase` already keeps purchase out of consumption. Without it, the Kausani
project overstates cost by most of ₹4,99,528.

*Delivered in Phase 5, and it is the shape of the report rather than a note on it.* Both
flags sat unused from Phase 2 until the expense register gave them a caller.
`ExpenseRegisterReport` returns four figures under an identity the spreadsheet spells out —
`costIncurred + materialPurchases + labourDisbursements = totalBooked` — and every row is
labelled with which of the three it is. `ExpenseReportIntegrationTest` asserts the identity
by name against ₹10,000 of site expenses, ₹40,000 of cement and ₹25,000 paid to a labour
contractor: booked ₹75,000, cost incurred ₹10,000.

---

## B. Material estimated vs actual

A NIT gives **work quantities**, not material quantities. A BOQ line reads *RCC M25 in
columns, 5.94 cum*; it does not read *30 bags of cement*. The bridge is a consumption
coefficient, and the field sheet already applies one by hand.

### Three levels, not two

| Level | Source | Question it answers |
|---|---|---|
| `TENDER_BOQ` | NIT, via tender-intelligence | Did we quote enough? |
| `EXECUTION_TAKEOFF` / `BBS` | working drawings | Did the tender under-quantify? |
| actual | stock ledger | Did site consume what we planned? |

Collapsing these into one "estimated" column hides the most commercially useful number
available — the gap between what was bid and what the drawings actually demand. Note the
field steel figure of 4,550 kg came from a **bar bending schedule**, not from the NIT.

*Delivered in Phase 4.* `MaterialEstimateService` keeps all three levels apart and never
collapses them; `GET /material-estimates/variance?level=` chooses which question is being
asked. `POST /material-estimates/derive` turns a BOQ line's work quantity into a material
quantity through a `material_consumption_norms` coefficient, which is the bridge this
section is about.

### The completeness trap

The cement take-off totals 263 bags; 460 were received. That is not a 75% overrun — the
take-off covered RCC and two brickwork lines only, and never touched plaster, flooring or
the rest of the masonry. **Variance must be computed only over the BOQ scope an estimate
actually spans, with uncovered scope shown explicitly.** Ship it otherwise and the first
dashboard tells the engineer he wasted 197 bags; he stops trusting it and does not come
back. Steel is the clean comparison because the BBS covered the whole structure.

*Delivered in Phase 4, and it is the shape of the response rather than a note on it.*
`MaterialVariance` carries `estimatedQty` and `issuedWithinScope` measured across the covered
BOQ items alone, `issuedOutsideScope` and `issuedWithoutBoqItem` beside them, an itemised
`uncovered` list, and a `scopeComplete` flag with a plain-English caveat written server-side
so every client says the same thing about it. The uncovered list is itemised rather than
totalled on purpose: "197 bags went somewhere else" is an accusation, and "197 bags went into
plaster, flooring and the boundary wall, none of which were estimated" is an explanation.
`MaterialEstimateVarianceIntegrationTest` asserts each part by name, against a seed
(`V901`) built to reproduce exactly this situation.

### Full variance chain

```
Estimated 4,550 → Received 5,075 (+11.5%) → Issued X → Wasted Y → Balance Z
```

Received, wasted and balance fall out of the existing ledger. **Issued does not** — the site
records receipts only and has never recorded a single issue. That is an adoption problem,
not a schema gap, and the issue screen has to be worth ten seconds of a supervisor's time
or the consumption half of every dashboard stays empty.

*Delivered in Phase 4.* The whole chain comes back on every `MaterialVariance` row —
`receivedQty`, `issuedWithinScope`, `wastedQty`, `balanceQty`. The issue screen was built to
that ten-second budget: the material list is the store's own stock with the quantity
alongside, the unit is the base unit and is never asked for, and the work item is chosen
once for the whole slip. An overdraw is flagged as it is typed rather than on submit.

---

## C. Integrity fixes made at the same time

- **Composite `(site_id, project_id)` foreign keys.** Nothing prevented a row naming a site
  belonging to a different project. In the planned multi-tenant mode that is how a
  service-layer bug becomes cross-project leakage. Free now, a backfill later.
- **Vendorless expenses escaped duplicate detection entirely** — `vendor_id` is nullable and
  NULLs compare as distinct, so the index never fired on them. `COALESCE` gives them a
  common key.
- **`boq_items` gains `work_part`, `category`, `source`, `is_synthetic`.** The tender parser
  classifies every line into Civil/E&M and one of fifteen categories; without these columns
  that work is discarded on import. `is_synthetic` marks the parser's `UNALLOCATED`
  reconciliation placeholders so labour, material and cash can never be charged to a
  rounding gap.

---

## Still open — decide before Phase 2 writes entity code

**1. ~~Document numbering (highest risk).~~ RESOLVED in Phase 3.** `expense_number`,
`grn_number`, `issue_number`, `dpr_number`, `payment_number`, `advance_number`,
`transfer_number`, `count_number` and `settlement_number` are all `NOT NULL` under a per-org
unique constraint, and nothing generated them. The offline design has devices creating
records while disconnected: two phones would both produce `EXP-0042`, and the second sync
fails a unique constraint that idempotency cannot resolve, because the UUID primary keys
differ — it is not a replay, it is a genuine collision over a human-readable label.

*Resolved as recommended:* server-assigned on arrival. `V3` adds
`document_number_counters (org_id, doc_type, year, next_value)` and
`common/DocumentNumberService` hands out `ADV-2025-0001` style numbers with a single atomic
`INSERT … ON CONFLICT DO UPDATE … RETURNING`, so concurrent callers serialise on the counter
row instead of racing to read-then-write. It runs in the caller's transaction, so a number
issued to a record that then fails to save rolls back with it — a gap in a document series
is a question an auditor will ask. Wired up for `WORKER_ADVANCE`; the other eight document
types use the same service as their modules land.

**2. ~~Two approval systems coexist.~~ RESOLVED in Phase 5.** docs/01 promises one engine
(`approvals` + `approval_rules`), but the schema also carries `overtime_approvals`,
`attendance_corrections.approval_status`, `physical_stock_counts.approved_by`,
`advance_settlements.status` and `expenses.workflow_status` with `L1_APPROVED`. Thresholds
live in **both** `expense_settings.admin_approval_above` and `approval_rules.min/max_amount`.
*Recommendation:* the generic engine is authoritative; per-module columns become a
denormalised cache written by the engine's event, never by a business service.

*Resolved as recommended.* `ApprovalService` is the only code that decides an approval. It
publishes `ApprovalDecided` and `ExpenseApprovalListener` is the only caller of
`Expense.applyDecision` and `AdvanceSettlement.applyDecision` — no business service sets a
workflow status by hand, which is what stops the two systems drifting the day somebody adds
a third level. The event fires **inside** the deciding transaction, because a status column
and the chain behind it have to commit together.

Thresholds have one home: `approval_rules`. `expense_settings.admin_approval_above` is left
in place as a historical column and is not read by the flow. `V8` adds
`approvals.entity_amount`, frozen at submission, so the engine can tell whether a further
level applies without asking the business module — the coupling a generic engine exists to
avoid — and so a threshold raised later cannot retroactively excuse a record already queued.

Still on per-module columns and not yet migrated: `overtime_approvals`,
`attendance_corrections.approval_status` and `physical_stock_counts.approved_by`. Each is a
single-level decision with no threshold, so nothing is currently drifting; they move onto the
engine when one of them grows a second level.

**3. Soft-delete convention is not applied consistently.** docs/02 says master data carries
`deleted_at`; `stores`, `units`, `skill_categories`, `material_categories` and
`expense_categories` do not have it.

**4. `org_id` is not on every business table**, contrary to docs/02 and docs/03. Child tables
(`goods_receipt_items`, `material_issue_items`, `purchase_order_items`, `dpr_*`,
`expense_attachments`, `advance_settlement_expenses`) omit it. Defensible via the parent
join, but it blocks Postgres row-level security — which is the stated multi-tenant plan.

**5. Not modelled at all.** Week-off per worker (`Sun` / `No OFF Day` in the sheet);
piece-rate subcontract payments (Carpenter ₹4,600, Hari electrician ₹4,250, Mangeram
₹12,000 — none appear on the attendance roster); machinery rental cost (`dpr_machinery`
records hours but no money).

**6. Material identity mapping.** The NIT says *"Thermo-Mechanically Treated bars of grade
Fe-500D"*; the stock register says *"Steel TMT"*. The tender parser resolves items to
categories, never to a material master. That mapping table is unglamorous and unavoidable
before estimates can be compared to receipts automatically.

---

## Seed data this implies (delivered in Phases 2, 3 and 4)

- `units`: add **QTL** (quintal), with a `material_unit_conversions` row of 100 kg per
  quintal for every material stocked in kg.
- `expense_categories`: the field taxonomy is two-level — Labour / Material / Miscellaneous
  / Office × Travel / Site / Tools / Petrol / Office. Flag the labour-disbursement
  subcategories `is_labour_payment = true` and material purchase `is_material_purchase = true`.
- `labour_settings`: one row per org; the Kausani default for
  `overtime_reason_required_above_hours` is 4.00.
- `sites.standard_shift_hours` = **7.00** for Kausani, not the 8.00 default.
- `material_consumption_norms`: seed the CPWD coefficients — 5.0 bags/cum for RCC, 0.63 and
  0.625 bags/cum for brickwork are the observed values.
- `boq_items`, opening stock and `material_estimates` (Phase 4, `V901`): eight work items on
  the Kausani project, the cement take-off scoped to RCC and the two brickwork lines only —
  262.75 bags, the field sheet's 263 to within a rounding — and plaster and flooring left
  deliberately unestimated, so the completeness trap has a fixture rather than a paragraph.
  Steel carries both a `TENDER_BOQ` figure of 4,200 kg and a `BBS` figure of 4,550, because
  that gap is the point of keeping the levels apart. Opening stock is seeded as ledger rows
  plus the balance they produce, never as a balance alone: seeding one without the other
  would seed the very inconsistency the module exists to prevent.

---

## Roadmap impact

Worker settlement belongs in **Phase 3 (Labour)**, not Phase 5, because attendance
verification is what generates the ledger entries — splitting it means building the
settlement screen twice. *Done: verification posts `WAGE_EARNED` and `OT_EARNED` in the same
transaction that freezes the rate, and `GET /workers/{id}/settlement` returns earned,
advance and payable with the ledger lines behind them.* Material estimates belong in **Phase 4 (Inventory)**, with the
tender-side `TENDER_BOQ` level filled in later when the tender module merges. docs/00's
"payroll disbursement out of scope" line no longer holds and has been amended.
