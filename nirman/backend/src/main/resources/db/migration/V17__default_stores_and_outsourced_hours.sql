-- Nirman — a site always has somewhere to put the cement, and the contractor's men worked
-- for a length of time.
--
-- Two changes, both closing a gap between what the screens ask for and what a site actually
-- has on the day.
--
-- 1. Every site gets a store the moment it is created. A store is not a decision anybody
--    was making — it is the shed by the gate — but until now nothing created one, so a
--    freshly added site had an empty store picker on the receive and issue screens and no
--    obvious way to fix it. The default is derived from the site rather than asked for, and
--    the Stores screen is where an organisation that keeps three lockups per site says so.
--
-- 2. Outsourced labour records hours. V13 left them out deliberately, on the ground that
--    the contractor bills against his own bill and an hour here would be a number nobody
--    checks. That holds for *money* and still does — no rate, no wage, no ledger posting
--    follows from this column. It does not hold for the day's record: a site that reports
--    "eleven masons" and a site that reports "eleven masons for four hours" have not had
--    the same day, and the daily report is where that difference is supposed to show.

-- ---------------------------------------------------------------- store names fit a site
-- A default store's code and name are the site's own, prefixed. The site columns are
-- varchar(40) and varchar(200), so the old store widths could not hold the prefixed value
-- of a site sitting at its own limit.
ALTER TABLE stores ALTER COLUMN code TYPE varchar(60);
ALTER TABLE stores ALTER COLUMN name TYPE varchar(210);

COMMENT ON COLUMN stores.code IS
    'Unique per organisation. A store created with its site takes the site code prefixed '
    'with "site-"; an administrator may rename it afterwards.';

-- ---------------------------------------------------------------- the stores that are missing
-- Every live site that has no store at all gets the one it should have had. Guarded twice:
-- a site that already has a store is left exactly as it is, and a code already taken inside
-- the organisation is skipped rather than colliding — that store is somebody's own naming
-- and this migration has no business overwriting it.
--
-- Derived from rows that are already there, not seeded: nothing here invents an
-- organisation, a site or a name.
INSERT INTO stores (id, org_id, site_id, code, name, is_default, is_active)
SELECT gen_random_uuid(), s.org_id, s.id, 'site-' || s.code, 'site-' || s.name, true, true
  FROM sites s
 WHERE s.deleted_at IS NULL
   AND NOT EXISTS (SELECT 1 FROM stores st WHERE st.site_id = s.id)
   AND NOT EXISTS (SELECT 1 FROM stores st
                    WHERE st.org_id = s.org_id AND st.code = 'site-' || s.code);

-- ---------------------------------------------------------------- hours on a head count
-- Hours each man of the trade worked that day, not the gang's total: eleven masons for
-- eight hours is what a supervisor knows at the gate, and the man-hours are that multiplied
-- by the count wherever a total is wanted.
--
-- Nullable, and null is not zero. A day recorded before this column existed, or by somebody
-- who only counted heads, says nothing about hours — and "nobody said" must not print as
-- "they worked no hours".
ALTER TABLE site_labour_counts
    ADD COLUMN hours numeric(4,2);

ALTER TABLE site_labour_counts
    ADD CONSTRAINT ck_site_labour_counts_hours CHECK (hours IS NULL OR (hours >= 0 AND hours <= 24));

COMMENT ON COLUMN site_labour_counts.hours IS
    'Hours each man of this trade worked that day. Null means nobody recorded hours, which '
    'is not the same as zero. No money is derived from it — the contractor bills for the work.';

-- ---------------------------------------------------------------- on the frozen report
-- Beside the muster roll's hours, never inside them — the same rule outsourced_head_count
-- follows, and for the same reason. These hours have no wage behind them, so folding them
-- into labour_regular_hours would put unpriced time into a figure every cost reading
-- divides by.
ALTER TABLE daily_progress_reports
    ADD COLUMN outsourced_man_hours numeric(10,2) NOT NULL DEFAULT 0;

COMMENT ON COLUMN daily_progress_reports.outsourced_man_hours IS
    'Contractor men-hours for the day: head count times hours each, summed over the trades. '
    'Zero when the site recorded counts without hours.';
