-- Make room for correcting a verified attendance row.
--
-- uq_wle_attendance_posting exists to stop a re-verification paying a worker twice, and it
-- does that by allowing one ledger entry per (attendance row, entry type). But it was
-- written over *every* entry type, and correcting a verified day posts an ADJUSTMENT
-- against that same row — legitimately, and more than once if the day is corrected twice.
--
-- So the index is narrowed to the two types it was actually guarding. WAGE_EARNED and
-- OT_EARNED stay exactly as immovable as before: still one apiece, ever, per row. Only
-- ADJUSTMENT is freed to repeat, which is the whole point of an adjustment.

DROP INDEX uq_wle_attendance_posting;

CREATE UNIQUE INDEX uq_wle_attendance_posting
    ON worker_ledger_entries (source_id, entry_type)
    WHERE source_type = 'ATTENDANCE'
      AND source_id IS NOT NULL
      AND entry_type IN ('WAGE_EARNED', 'OT_EARNED');
