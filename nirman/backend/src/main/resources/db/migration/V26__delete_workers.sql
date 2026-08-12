-- Nirman — taking a worker off the books.
--
-- The worker master could be created and edited from the day it existed; it could never be
-- corrected by removal. A man entered twice at the gate — the same face under two numbers —
-- stayed on the roll for good, and the only tool against him was the Left flag, which says
-- something quite different: that he worked here and has gone. A duplicate never worked here.
--
-- Deletion is for that mistake and for nothing else. A man who has been marked present, drawn
-- an advance or been paid carries frozen money in figures that have already been reported, so
-- WorkerService refuses him outright and says what is holding him — a man whose work is over
-- is marked Left, and stays visible on purpose.
--
-- The reason is stored on the row, not only in audit_logs. Six months on, a name that vanished
-- without an explanation is indistinguishable from data loss, and audit_logs is append-only and
-- read by plain JDBC, so it cannot be joined to the register the question will be asked from.

-- ---------------------------------------------------------------- who deleted, and why
-- workers.deleted_at has been there since the baseline and every read already filters on it.
-- What was missing was the act, and the two columns that make the act answerable.
ALTER TABLE workers
    ADD COLUMN deleted_by     uuid REFERENCES users(id),
    ADD COLUMN deleted_reason varchar(500);

CREATE INDEX ix_workers_deleted ON workers (org_id, deleted_at DESC) WHERE deleted_at IS NOT NULL;

-- ---------------------------------------------------------------- permission
INSERT INTO permissions (code, module, description) VALUES
    ('worker:delete', 'labour', 'Delete workers');

-- V2 gave ADMIN every permission by CROSS JOIN, but that ran once against the catalogue as it
-- stood then. A permission added later has to be granted explicitly.
--
-- The engineer holds it as well as the administrator, and that is the whole point of adding it
-- here rather than following V12: the duplicate is spotted at the muster roll, by the man who
-- verifies it, and a correction that has to travel to head office and back is a correction that
-- does not get made. He is still fenced to his own sites by SiteAccessGuard on the way through.
-- The supervisor keeps worker:write — he takes men on and corrects what he typed — and cannot
-- delete: removing a name is not a field decision.
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON p.code = 'worker:delete'
WHERE r.code IN ('ADMIN', 'ENGINEER') AND r.is_system;
