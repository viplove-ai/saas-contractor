-- Nirman — deleting a daily progress report.
--
-- The wizard has always been able to empty a draft and write the day again ("start fresh"),
-- which is the right answer when the day happened and was written up badly. It is the wrong
-- answer to a report that should not exist at all — a day opened on the wrong site, a report
-- started on Sunday out of habit — because an empty draft is still a report: it holds a DPR
-- number, it sits in the register as a draft, it counts on "waiting on you", and it occupies
-- the one-report-per-site-per-day slot so the day cannot be written properly later.
--
-- Deleted the way a project is deleted: the row stays, marked, with a reason on it. What is
-- deletable is a draft or a report the engineer sent back. A submitted report is in front of
-- somebody, and a verified one has posted its measured quantities to the measurement book —
-- history does not move, and neither of those is a mistake this is for.

ALTER TABLE daily_progress_reports
    ADD COLUMN deleted_at     timestamptz,
    ADD COLUMN deleted_by     uuid REFERENCES users(id),
    ADD COLUMN deleted_reason varchar(500);

-- One report per site per day, but only among the live ones. A deleted report keeps its site
-- and its date — that is what makes it findable afterwards — so the constraint has to stop
-- counting it, or the day it was deleted from could never be written again.
ALTER TABLE daily_progress_reports DROP CONSTRAINT uq_dpr_site_date;
CREATE UNIQUE INDEX uq_dpr_site_date ON daily_progress_reports (site_id, report_date)
    WHERE deleted_at IS NULL;

-- The rare read, given the partial index the common one already has in reverse.
CREATE INDEX ix_dpr_deleted ON daily_progress_reports (org_id, deleted_at DESC)
    WHERE deleted_at IS NOT NULL;

-- ---------------------------------------------------------------- permission
INSERT INTO permissions (code, module, description) VALUES
    ('dpr:delete', 'dpr', 'Delete a draft or returned daily report');

-- V2 gave ADMIN every permission by CROSS JOIN, but that ran against the catalogue as it
-- stood then; a permission added later has to be granted explicitly.
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON p.code = 'dpr:delete'
WHERE r.code IN ('ADMIN', 'ENGINEER', 'SUPERVISOR') AND r.is_system;

-- Granted to the supervisor as well as the engineer, unlike site:delete. The report he is
-- deleting is the one he opened by mistake half an hour ago, on a site he is posted to, and
-- it has been nowhere: sending him to find an administrator to unstick his own morning is
-- how a wrong-day report ends up submitted instead.
