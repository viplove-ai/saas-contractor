# 00 — Assumptions

Every gap in the brief is filled here. Challenge any of these before Phase 2 starts; changing them later costs migrations.

## Organisation and tenancy
1. **Single contractor now, SaaS later.** Every business table carries `org_id`. Phases 1–7 run with one row in `organisations`, but no query is written without an org filter, so multi-tenant mode becomes a config flag later, not a rewrite.
2. A user belongs to exactly one organisation. No cross-org users.

## Roles
3. Four roles ship seeded: `ADMIN`, `SITE_ENGINEER`, `SITE_SUPERVISOR`, `ACCOUNTANT`. Roles are database rows, not enums in code. Permissions are string codes (`attendance:verify`) checked by Spring Security expressions, so adding a fifth role needs no code change.
4. A user may hold more than one role. Effective permissions are the union.
5. `ADMIN` implicitly reaches every site in its org. Every other role needs an explicit row in `user_site_assignments`.

## Time, shifts, wages
6. **Standard shift = 8 hours** by default, stored per site (`sites.standard_shift_hours`) so a site can override it. Overtime starts after that. *Field check: Kausani ran a **7-hour** shift — ₹17,321 = (194 ÷ 7) × ₹625 — so the override is not hypothetical and must be set per site at setup.*
7. Overtime pays the worker's `overtime_rate` per hour from the wage-rate row effective on the attendance date. No 1.5x multiplier is hard-coded. *Field check: confirmed — OT income ÷ OT hours = ₹89.00, identical to the regular hourly rate. Overtime carries no premium here, and hard-coding one would have been wrong.*
8. Half-day = 0.5 day wage, no overtime. Leave and absent = 0 wage. Paid-leave policy is out of scope for v1. *Half-day and leave are no longer offered when marking — the roster asks present or absent, then how many hours — but both remain in the schema and the calculator, because rows already carry them and the register still reads them.*
8a. **Hours are typed, not clocked.** The supervisor enters hours worked directly (`attendance_records.entered_hours`), prefilled with the site's standard shift, and everything past that shift is overtime — 9 hours on a 7-hour site is 7 regular and 2 over. Check-in/check-out remain supported by the API and the calculator for rows that have them, and typed hours override them when both are present. *Rationale: nobody on the slab carries a clock, and times were being invented to express a figure the supervisor already knew.*
9. Monthly-wage workers get a derived daily rate = monthly rate ÷ `sites.monthly_wage_days` (default 26) for costing only. Payroll disbursement is out of scope.
10. All timestamps are `timestamptz` in UTC. Application timezone is `Asia/Kolkata`. A business date (attendance date, expense date) is a plain `date` meaning the IST calendar day.
11. Night shifts crossing midnight: a check-out earlier than check-in means the shift rolled over, so add 24h. The record belongs to the check-in date.

## Money
12. Currency is INR only. Amounts `numeric(18,2)`, quantities `numeric(18,4)`, rates `numeric(18,4)`. No floating point anywhere.
13. GST is stored as a percentage plus a computed amount. Input tax credit and return filing are out of scope.
14. Weighted-average valuation is per material **per store**, not company-wide.

## Approvals
15. Default limits (configurable rows, not constants): expense ≤ ₹25,000 needs Engineer approval only; above that, Engineer then Admin. A bill attachment is mandatory above ₹5,000.
16. Attendance always needs Engineer verification regardless of value. Admin lock closes the period.
17. A locked period means no edits to any operational record for that site and month. Unlocking is Admin-only and audited.

## Inventory
18. Stock is never an editable number. `stock_transactions` is append-only. `stock_balances` is a derived cache written inside the same transaction under a row lock. Any disagreement is a data-quality alert, and the ledger wins.
19. Negative stock is rejected in the service layer. An Admin may post a `PHYSICAL_ADJUSTMENT` with a reason to match reality. The rejected attempt is still logged for the data-quality dashboard.
20. A transfer writes two ledger rows — out at source, in at destination — but the destination row is written only on receipt. In-transit quantity is visible and belongs to neither site's closing stock.

## Files
21. MinIO in dev, any S3-compatible store in production. Downloads go through a signed-URL endpoint that re-checks site access. Buckets are never public.
22. Maximum 15 MB per upload. Images are compressed client-side to 1600px on the long edge before upload.

## Offline
23. Offline is write-only drafts. Reference data (workers and materials for assigned sites) is cached read-only. Dashboards and reports need connectivity.
24. Conflict rule: the server wins on approved or locked records, and the client draft is flagged `CONFLICT` for the user to resolve. Draft against draft, last write wins by server receipt time.
25. Idempotency: the client generates a UUID v4 per record and sends it as both the entity id and the `Idempotency-Key` header. A re-send returns the original 201 body.

## Wage settlement — moved into scope
26. Workers draw advances against wages and are settled on a balance. The field sheets track `Total Amount − Advance = Balance Payment` per worker per month, so this is existing practice, not a new feature. `worker_ledger_entries` is append-only and posted at attendance verification; `worker_balances` is a derived cache. See [09 Design decisions](09-design-decisions.md).
27. Money handed to a worker is **settlement, not cost**. Cost is what he earned from verified attendance. An expense category flagged `is_labour_payment` is excluded from cost incurred, mirroring `is_material_purchase` on the material side. Without this, wages count twice.
28. Whether ration, footwear and medicine are recovered from the worker or borne by the contractor varies per item, so it is recorded per advance (`worker_advances.is_recoverable`), never assumed.

## Out of scope for v1 — schema is ready, features are not
- CPWD Measurement Book and RA bill generation
- Statutory deductions (PF, ESI) and bank disbursement files. Settlement computes what is owed; paying it out through a bank remains manual.
- Hindi localisation (i18n scaffolding present, strings English)
- Push notifications (`notifications` table exists, delivery is in-app only)
- Biometric or GPS-fenced attendance
