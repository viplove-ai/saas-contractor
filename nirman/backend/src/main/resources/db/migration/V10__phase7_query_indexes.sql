-- ==============================================================================================
-- V10 — Phase 7 query tuning
--
-- Indexes only. Nothing here changes what the system stores or what it will accept; every
-- statement is additive and every one of them is a query that already runs today and reads
-- more rows than it needs to. They were found by walking the repository queries against the
-- index list from V1 rather than by watching a slow log, because the seeded dataset is two
-- projects and nothing is slow at that size — which is exactly why it is worth doing now,
-- while a mistake here costs a rebuild of an empty table.
--
-- Every index below is justified against a specific caller. An index nobody's query matches
-- is not free: it is a write amplified on every insert to the busiest tables in the system.
-- ==============================================================================================


-- ---------------------------------------------------------------------------------------------
-- Expenses
-- ---------------------------------------------------------------------------------------------

-- The matrix's O: a supervisor's expense list is "the ones I raised", and ExpenseRepository
-- .search applies that as created_by = :currentUserId. There was no index on created_by at
-- all, so the single most frequent query on the table — the list that loads when a supervisor
-- opens Add expense — was a scan of every expense in the company filtered afterwards.
CREATE INDEX ix_expenses_own ON expenses (created_by, expense_date DESC)
    WHERE created_by IS NOT NULL;

-- findForPeriod and every dashboard roll-up filter org and a date range with no site. V1's
-- ix_expenses_site_date leads with site_id, which a company-wide query cannot use.
CREATE INDEX ix_expenses_org_date ON expenses (org_id, expense_date);

-- Vendor balances and the ageing report: approved, not fully paid, optionally one vendor.
-- Partial on the workflow state because approved-and-unpaid is a small and shrinking slice of
-- a table that only grows, and the whole point of the report is that the slice is small.
CREATE INDEX ix_expenses_outstanding ON expenses (org_id, vendor_id, expense_date)
    WHERE workflow_status = 'APPROVED';

-- The near-miss duplicate check — same vendor, same total, within a week — which runs on
-- every single expense creation and is the one query in the module that a supervisor waits on.
CREATE INDEX ix_expenses_similar ON expenses (org_id, vendor_id, total_amount, expense_date);


-- ---------------------------------------------------------------------------------------------
-- Inventory documents
-- ---------------------------------------------------------------------------------------------

-- The storekeeper's and engineer's lists are always "this store, in this state". V1 indexed
-- (site_id, receipt_date) and (site_id, issue_date), which is the wrong leading column for
-- both screens: a site with four stores read all four and discarded three.
CREATE INDEX ix_grn_store_status ON goods_receipts (store_id, workflow_status, receipt_date DESC);
CREATE INDEX ix_issues_store_status ON material_issues (store_id, workflow_status, issue_date DESC);

-- The duplicate-invoice check on a delivery, which compares case- and space-insensitively.
-- An expression index or nothing: upper(trim(invoice_number)) cannot use a plain column index.
CREATE INDEX ix_grn_vendor_invoice ON goods_receipts
    (org_id, vendor_id, upper(btrim(invoice_number)))
    WHERE invoice_number IS NOT NULL;


-- ---------------------------------------------------------------------------------------------
-- Daily progress reports
-- ---------------------------------------------------------------------------------------------

-- The engineer's verification queue: everything submitted, company-wide, newest first. V1's
-- ix_dpr_site_date leads with site_id and cannot serve it.
CREATE INDEX ix_dpr_status_date ON daily_progress_reports
    (org_id, workflow_status, report_date DESC);


-- ---------------------------------------------------------------------------------------------
-- Identity and audit
-- ---------------------------------------------------------------------------------------------

-- Expiring refresh tokens. V1 indexed (user_id, revoked_at), which finds one user's tokens
-- and cannot find the expired ones across all users — the direction a cleanup sweep needs.
CREATE INDEX ix_refresh_tokens_expiry ON refresh_tokens (expires_at)
    WHERE revoked_at IS NULL;

-- "What happened at this company last week", which is the question the audit trail is
-- actually asked. V1 covers by-entity and by-user; neither helps a company-wide window.
CREATE INDEX ix_audit_org_occurred ON audit_logs (org_id, occurred_at DESC);


-- ---------------------------------------------------------------------------------------------
-- Offline replay
-- ---------------------------------------------------------------------------------------------

-- Every idempotent create begins with findByIdAndOrgId, which is the primary key plus a
-- filter and needs nothing. What does need something is the retention sweep: idempotency
-- records are write-only rows that nothing reads after their window, and V1's index on
-- created_at alone is enough to find them but not to tell them apart by tenant.
CREATE INDEX ix_idempotency_org_created ON idempotency_records (org_id, created_at);

-- Kept empty in practice: the business modules are idempotent on their own client-generated
-- primary keys, which is a stronger guarantee than a replay cache because it survives the
-- cache being swept. The table stays for any future endpoint that has no natural id to be
-- idempotent on, and the index is what a retention sweep would need on the day one exists.
COMMENT ON TABLE idempotency_records IS 'Replay protection for endpoints with no client-generated primary key of their own. Rows are safe to delete once older than the client retry horizon.';
