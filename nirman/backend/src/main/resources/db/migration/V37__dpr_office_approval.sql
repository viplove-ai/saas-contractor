-- Nirman — the office's countersignature on the day's report, and the supervisor's half kept
-- open until it is given.
--
-- ---------------------------------------------------------------- what changes, and what does not
-- The report had two authors and two acts: the supervisor recorded what the day was and handed
-- it over; the engineer recorded what was built and signed, and signing posted the measured
-- quantities to the measurement book. That second act was the end of the document.
--
-- It is no longer the end. The office reads what was signed and approves it, and until it does
-- the report is a signed claim that nobody upstairs has seen. That is a real gap on a site
-- running to a department's programme — the man in the office is answering for figures he
-- first meets in a monthly return.
--
-- **The measurement book still moves on the engineer's signature.** Approval is a
-- countersignature on a document whose quantities already count, not the moment they begin to.
-- Anything else would move the invariant the whole module is built on: a quantity is claimed by
-- the man who measured it and can say what it is, and holding the claim back until an office
-- approval would put the contract's progress in the hands of somebody who was not there.
--
-- ---------------------------------------------------------------- one new permission
-- `dpr:approve`, the office's. Not folded into `dpr:verify`: verifying is the engineer saying
-- what was built, approving is the office accepting it, and an organisation that granted one
-- by granting the other would have a two-signature document with one signature on it.

ALTER TABLE daily_progress_reports
    ADD COLUMN approved_at timestamptz,
    ADD COLUMN approved_by uuid REFERENCES users(id);

COMMENT ON COLUMN daily_progress_reports.approved_at IS
    'When the office countersigned. The measured quantities reached the measurement book '
    'earlier, at verification — approval accepts a document whose figures already count.';

-- V1 wrote the closed list of states; a fifth one has to be let in explicitly.
ALTER TABLE daily_progress_reports DROP CONSTRAINT ck_dpr_workflow;
ALTER TABLE daily_progress_reports ADD CONSTRAINT ck_dpr_workflow
    CHECK (workflow_status IN ('DRAFT', 'SUBMITTED', 'VERIFIED', 'REJECTED', 'APPROVED'));

-- An approval is an approval by somebody at a time, or it is not one. The write that never
-- goes through the service is refused here.
ALTER TABLE daily_progress_reports ADD CONSTRAINT ck_dpr_approval_is_whole
    CHECK (workflow_status <> 'APPROVED' OR (approved_at IS NOT NULL AND verified_at IS NOT NULL));

-- The office's queue: signed and waiting, across every site it runs.
CREATE INDEX ix_dpr_awaiting_office ON daily_progress_reports (org_id, report_date DESC)
    WHERE workflow_status = 'VERIFIED' AND deleted_at IS NULL;

-- ---------------------------------------------------------------- permission
INSERT INTO permissions (code, module, description) VALUES
    ('dpr:approve', 'dpr',
     'Give the office''s final approval to a report the engineer has signed');

-- V2 granted ADMIN everything by CROSS JOIN over the catalogue as it stood then; anything
-- added later has to be granted explicitly. The office alone: the engineer already signed it,
-- and a second signature by the same man is not a second pair of eyes.
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
  FROM roles r
  JOIN permissions p ON p.code = 'dpr:approve'
 WHERE r.code = 'ADMIN' AND r.is_system
   AND NOT EXISTS (SELECT 1 FROM role_permissions rp
                    WHERE rp.role_id = r.id AND rp.permission_id = p.id);
