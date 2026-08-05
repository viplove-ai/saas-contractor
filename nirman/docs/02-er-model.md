# 02 — Entity relationship model

Every business table carries: `id uuid pk`, `org_id`, `created_at`, `updated_at`, `created_by`, `updated_by`, `version`. Soft delete (`deleted_at`) exists only on master data, never on transactions.

## Core spine
```
organisations 1─┬─* users ─* user_roles *─ roles ─* role_permissions *─ permissions
                ├─* projects ─* sites ─┬─* stores
                │                      ├─* user_site_assignments *─ users
                │                      └─* period_locks
                └─* boq_items (─ project, optional ─ site)
```

## Labour
```
labour_contractors 1─* workers 1─┬─* wage_rates          (effective_from / effective_to)
                                 ├─* worker_site_allocations ─ sites
                                 └─* attendance_records ─┬─ sites
                                                         ├─ boq_items (nullable)
                                                         └─* attendance_corrections
attendance_records 1─0..1 overtime_approvals
```
`attendance_records` has a unique key on `(worker_id, site_id, attendance_date)` where not cancelled. Wage snapshot columns (`applied_normal_rate`, `applied_ot_rate`, `computed_wage_amount`) are frozen at verification so later wage changes never rewrite history.

## Inventory
```
materials ─┬─* material_unit_conversions ─ units
           └─ preferred vendor ─ vendors

purchase_orders 1─* purchase_order_items ─ materials
goods_receipts  1─* goods_receipt_items  ─ materials   (─ purchase_orders, nullable)
stock_transfers 1─* stock_transfer_items ─ materials   (from store → to store)
physical_stock_counts 1─* physical_stock_count_items

stock_transactions  ← the single append-only ledger
   ├─ store, material, txn_type, quantity_base, rate, value
   ├─ source_type + source_id  (GOODS_RECEIPT | ISSUE | TRANSFER | COUNT | OPENING)
   └─ boq_item (nullable), issued_to, direction (+1 / −1)

stock_balances (derived cache: store × material → qty, moving_avg_rate, value)
```
Every inbound and outbound movement, whatever its document, produces exactly one `stock_transactions` row. Reports read the ledger. The cache exists only so dashboards stay fast.

## Expense and cash
```
expense_categories (self-referencing parent_id for subcategory)
expenses ─┬─ project, site, vendor, boq_item (nullable), category
          ├─* expense_attachments ─ attachments
          ├─* payments            (partial payments allowed)
          └─ 0..1 site_advances   (when settled against an advance)

site_advances 1─* advance_settlements ─ expenses
```
Three amounts are deliberately separate and never merged: `total_amount` (approved cost), the sum of `payments.amount` (cash paid), and the derived payable (`approved − paid`).

## DPR
```
daily_progress_reports ─┬─ project, site, report_date  (unique per site+date)
                        ├─* dpr_work_items ─ boq_items
                        ├─* dpr_labour      (snapshot rolled from attendance)
                        ├─* dpr_machinery
                        └─* dpr_photos ─ attachments
```

## Cross-cutting
```
approvals (entity_type, entity_id, level, role, user, status, prev/next status)
approval_rules (org, entity_type, condition, level, role, amount bounds)
attachments (owner_entity_type, owner_entity_id, object_key, mime, bytes, checksum)
audit_logs (entity_type, entity_id, action, old_values jsonb, new_values jsonb, ip, device)
notifications, import_batches, import_batch_rows, idempotency_records, refresh_tokens
```

## The rule that keeps this from being three Excel sheets
Attendance, stock issues, expenses and DPR quantities all point at `site_id` **and** optionally `boq_item_id`. That single shared pair is what makes "cost incurred against work item X" answerable across labour, material and cash in one query. Any new module must adopt the same pair.

## Double-counting guard
A material purchase writes an `expenses` row (category `Material purchase`) **and** a `stock_transactions` receipt. Dashboards therefore report *purchased* from expenses, *consumed* from issue-type ledger rows valued at moving average, and *inventory value* from the balance cache. Total project cost = labour cost + material **consumed** + non-material expenses. Purchase value never enters cost incurred.
