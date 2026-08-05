-- Nirman — Phase 6: the guards that make DPR verification and measured progress safe.
--
-- Same shape as V7 for stock and V8 for cash: the service layer checks first and answers in a
-- sentence, and these are the backstop for the race the check cannot see and for the code path
-- that one day forgets to check at all.

-- 1. Verifying a DPR twice does not claim the work twice.
--
-- This is the measurement-book version of the rule the labour module already lives by — that
-- verifying the same attendance row twice does not pay the worker twice — and it matters for
-- the same reason: boq_items.completed_quantity is a cache of the sum of these entries, and a
-- double post would inflate the running bill by whatever the second one claimed. A double
-- click is enough to cause it.
--
-- BoqProgressService checks existsByDprIdAndBoqItemId before posting; this makes the check
-- unnecessary rather than merely usual. Partial, because dpr_id is null for a measurement
-- recorded directly against a line, and two of those on one day are perfectly legitimate — a
-- morning and an afternoon measurement of the same pour.
CREATE UNIQUE INDEX uq_boq_entry_dpr_item
    ON boq_progress_entries (dpr_id, boq_item_id)
    WHERE dpr_id IS NOT NULL;

COMMENT ON INDEX uq_boq_entry_dpr_item IS
    'One claim per work item per DPR. Makes a second verification a no-op instead of a double count.';

-- 2. A measurement of zero says nothing.
--
-- ck_boq_entry_qty (V1) already refuses it. Recorded here only as the reason the entity
-- constructor refuses it too: a zero row would occupy the unique index slot above and make the
-- real claim, arriving later, look like a duplicate.

-- 3. The site-and-date index the dashboards read progress by.
--
-- ix_boq_entries_item (V1) covers "the measurement book for this line", which is the running
-- bill's question. A dashboard asks the other one — what was measured at this site this month —
-- and would otherwise scan the table for it.
CREATE INDEX ix_boq_entries_site_date ON boq_progress_entries (site_id, entry_date);

-- 4. The verification queue.
--
-- ix_dpr_site_date (V1) serves the list screen. The queue count asks only for submitted rows,
-- of which there are a handful out of a year of reports, so it gets a partial index rather than
-- a scan that grows with the project.
CREATE INDEX ix_dpr_awaiting_verification
    ON daily_progress_reports (org_id, site_id)
    WHERE workflow_status = 'SUBMITTED';

-- 5. A verified or rejected report records who decided it.
--
-- Both transitions are signatures: verifying is what claims work against the contract, and
-- returning a report is what a preparer has to answer to. A row in either state with no
-- verified_by is a decision nobody made, which is exactly the thing an audit asks about.
--
-- Written as NOT VALID so an existing database is not held to it retrospectively, then
-- validated — on a fresh schema this is immediate, and on a populated one it is the difference
-- between a migration that deploys and one that fails on somebody's history.
ALTER TABLE daily_progress_reports ADD CONSTRAINT ck_dpr_decided_by
    CHECK (workflow_status NOT IN ('VERIFIED', 'REJECTED') OR verified_by IS NOT NULL) NOT VALID;
ALTER TABLE daily_progress_reports VALIDATE CONSTRAINT ck_dpr_decided_by;

-- 6. A submitted report has a submitted-at.
--
-- The frozen snapshot is only meaningful with the moment it was frozen beside it. Without this
-- a report could carry figures nobody can date, which defeats the point of freezing them.
ALTER TABLE daily_progress_reports ADD CONSTRAINT ck_dpr_submitted_at
    CHECK (workflow_status = 'DRAFT' OR submitted_at IS NOT NULL) NOT VALID;
ALTER TABLE daily_progress_reports VALIDATE CONSTRAINT ck_dpr_submitted_at;
