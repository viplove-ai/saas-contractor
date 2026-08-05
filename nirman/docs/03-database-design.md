# 03 — PostgreSQL table design

The schema is the migration, not a document. One baseline — `V1__initial_schema.sql` —
creates **67 tables**. Flyway runs it in a single transaction, so the schema either lands
whole or not at all; there is no half-applied state to repair.

| Section | Tables | Covers |
|---|---|---|
| Identity, organisation, projects | 20 | organisations, users, roles, permissions, projects, sites, stores, period locks, approvals, attachments, audit, notifications, imports, idempotency, refresh tokens |
| Labour | 12 | labour contractors, skill categories, workers, allocations, wage history, attendance, corrections, overtime, labour settings, worker advances, worker wage ledger and balance cache |
| Inventory | 19 | units, vendors, material master and conversions, purchase orders, goods receipts, issues, transfers, physical counts, stock ledger, balance cache, violation log, consumption norms |
| Expenses and cash | 8 | expense categories and settings, expenses, attachments, payments, site advances, settlements |
| BOQ and DPR | 8 | BOQ items, progress entries, material estimates, DPR and its child tables |

Sections follow the module boundaries in [01 Architecture](01-architecture.md). The nine
foreign keys that cross a circular dependency — `goods_receipts` ↔ `expenses`,
`expenses` ↔ `site_advances`, `worker_advances` → `expenses`, and the five `boq_item_id`
columns — are collected at the end of the file rather than scattered.

The findings in [09 Design decisions](09-design-decisions.md) are folded in rather than
carried as correction migrations. Squashing was possible only because nothing had been
applied or published yet. **From the first `spring-boot:run` onwards this file is frozen: a
correction is `V2`, never an edit here.**

## Conventions applied everywhere
- `uuid` primary keys. Transaction tables accept a **client-generated** id so an offline device can re-send safely.
- `org_id` on every business table. No query is written without it.
- `created_at`, `updated_at`, `created_by`, `updated_by`, `version` on every mutable table.
- `numeric(18,2)` money, `numeric(18,4)` quantities and rates. No floats.
- Soft delete (`deleted_at`) on master data only. Transactions are cancelled or voided, never deleted.

## The five constraints that carry the business rules

**1. Attendance cannot be duplicated.**
```sql
CREATE UNIQUE INDEX uq_attendance_worker_site_date
  ON attendance_records (worker_id, site_id, attendance_date)
  WHERE workflow_status <> 'CANCELLED';
```
A partial index, so a mistaken entry can be cancelled and re-entered without deleting history.

**2. Stock cannot go negative.** `stock_balances` carries `CHECK (quantity_base >= 0)`. The service layer rejects first and logs the attempt to `stock_violation_log`; the constraint is the backstop if a code path ever forgets.

**3. Stock cannot be typed in.** There is no updatable balance column anywhere a user can reach. `stock_transactions` is insert-only, and `stock_balances` is written only by the ledger service inside the same transaction under `SELECT … FOR UPDATE`.

**4. An approved expense has a bill or a written reason.**
```sql
CHECK (workflow_status NOT IN ('APPROVED','L1_APPROVED')
       OR bill_number IS NOT NULL OR no_bill_reason IS NOT NULL)
```

**5. The same vendor invoice cannot be booked twice.** A unique partial index on `(org_id, vendor_id, upper(bill_number))` excluding voided and rejected rows, plus a fuzzy amount-and-date check in the service that returns 409 with candidates before the constraint ever fires.

## Wage history
`wage_rates` is effective-dated with a unique partial index guaranteeing one open row per worker. Attendance freezes `applied_normal_rate`, `applied_ot_rate` and the computed amounts at verification. A wage revision therefore cannot rewrite last month's labour cost.

## Ready for CPWD billing later
`boq_items` is hierarchical (`parent_id`) and progress is recorded as dated `boq_progress_entries` rather than an overwritten total. Measurement Book entries and RA bills become two more child tables referencing the same items — no restructuring.

## Reset and re-apply locally
```bash
docker compose down -v && docker compose up -d postgres
docker compose exec postgres psql -U nirman -d nirman -c '\dt'
```
