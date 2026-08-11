-- Nirman — the supervisor's day, and the labour that has no muster roll.
--
-- Two unrelated-looking changes, both from the same decision: the supervisor's job is to
-- record what happened at site today, and nothing else.
--
-- 1. Outsourced labour. On a site where the work is let to a labour contractor, nobody
--    marks individual attendance — the contractor brings eleven masons and six helpers and
--    takes them away again, and what the site knows is the count. That is a real record of
--    the day and it belongs in the daily report, but it is *not* attendance: there is no
--    worker, no wage rate, no muster roll, and no money follows from it. Forcing it through
--    attendance_records would mean inventing a worker row per head per day, which would
--    then turn up in wage runs and worker ledgers as a person who does not exist. So it
--    gets its own small table, and the DPR reads both.
--
-- 2. The site dashboard stops being a supervisor's screen. Cost per BOQ line, budget burn
--    and variance are the engineer's and the accountant's questions. The supervisor's
--    screens are the ones he enters, plus the report he sends.

-- ---------------------------------------------------------------- the site flag
-- Off by default: a site staffed by our own workers keeps the muster roll it has, and the
-- counts section stays off its supervisor's screen entirely.
ALTER TABLE sites
    ADD COLUMN uses_outsourced_labour boolean NOT NULL DEFAULT false;

COMMENT ON COLUMN sites.uses_outsourced_labour IS
    'Site lets work to labour contractors, so the day is recorded as head counts per trade '
    'rather than as attendance against named workers.';

-- ---------------------------------------------------------------- the counts
-- One row per site, day, trade and contractor. Counts, not hours: the contractor is paid
-- against his own bill, not against this, so hours here would be a number nobody checks.
CREATE TABLE site_labour_counts (
    id                   uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id               uuid NOT NULL REFERENCES organisations(id),
    site_id              uuid NOT NULL REFERENCES sites(id),
    count_date           date NOT NULL,
    skill_category_id    uuid NOT NULL REFERENCES skill_categories(id),
    labour_contractor_id uuid REFERENCES labour_contractors(id),
    head_count           int  NOT NULL,
    remarks              varchar(300),
    created_at           timestamptz NOT NULL DEFAULT now(),
    updated_at           timestamptz NOT NULL DEFAULT now(),
    created_by           uuid,
    updated_by           uuid,
    version              bigint NOT NULL DEFAULT 0,
    -- Zero is a legitimate entry — "the bar benders did not come today" is worth recording
    -- and is not the same as leaving the trade off the list.
    CONSTRAINT ck_site_labour_counts_head CHECK (head_count >= 0)
);

-- One count per trade per contractor per day. The COALESCE is the usual trick for a
-- nullable column in a unique key: without it, "masons, no contractor named" could be
-- entered twice and the day would silently double.
CREATE UNIQUE INDEX uq_site_labour_counts_day ON site_labour_counts (
    site_id, count_date, skill_category_id,
    COALESCE(labour_contractor_id, '00000000-0000-0000-0000-000000000000'::uuid));

-- The read is always "one site, one day" (the entry screen) or "one site, a month" (the
-- report), so the site and date lead.
CREATE INDEX ix_site_labour_counts_site_date ON site_labour_counts (site_id, count_date);

-- ---------------------------------------------------------------- on the report
-- The report freezes its labour table, and it now freezes two kinds of row. They share a
-- table because they print as one list — "six masons (ours), eleven masons (Karam Singh)" —
-- and a flag because they must never be added together: the first row has hours and a wage
-- behind it and the second has neither.
ALTER TABLE dpr_labour
    ADD COLUMN outsourced boolean NOT NULL DEFAULT false;

-- Frozen alongside labour_present_count rather than added to it, for the same reason. A
-- reader of last April's report needs to see that fourteen of the forty men were somebody
-- else's, or the productivity figure they derive is nonsense.
ALTER TABLE daily_progress_reports
    ADD COLUMN outsourced_head_count int NOT NULL DEFAULT 0;

-- ---------------------------------------------------------------- supervisor scope
-- Not a permission being retired — the engineer and the admin still hold it. It is being
-- taken off one role, so the DELETE names both sides.
DELETE FROM role_permissions rp
      USING roles r, permissions p
      WHERE rp.role_id = r.id
        AND rp.permission_id = p.id
        AND r.code = 'SUPERVISOR'
        AND r.is_system
        AND p.code = 'dashboard:site';
