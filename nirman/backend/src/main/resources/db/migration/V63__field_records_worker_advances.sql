-- Nirman — the field may record an advance to a worker.
--
-- Recording a worker's advance sat behind advance:issue, which is the accountant's: it is
-- also what hands a member of staff a site float, and a supervisor who could hand himself
-- petty cash is the one thing that register exists to rule out. But the advance to a
-- worker is handed over at the gate, by the man who took him on — ration on a Tuesday,
-- five hundred rupees before a festival — and an office that could not hear of it until
-- somebody telephoned had the man's wages overstated until it did. The two acts were behind
-- one permission because both are called "advance"; they are handed to different people
-- from different pockets, and the row that records one is safe to widen for the reason the
-- worker advance already carries a workflow: it is DRAFT until somebody holding
-- advance:settle:approve agrees that it comes out of his wages, and nothing reaches the
-- ledger before that. The supervisor states what changed hands; the engineer or the office
-- decides what it means. That is the same split the muster roll runs on.
--
-- So one permission, worker:advance, for recording the hand-over and nothing else — not the
-- approval, and not the float. Granted to every system role: the two at the gate, the
-- accountant who held the act already under advance:issue, and the administrator, whose V2
-- CROSS JOIN ran against the catalogue as it stood then. advance:issue keeps the float and
-- loses nothing; the accountant's screen does not change.
INSERT INTO permissions (code, module, description) VALUES
    ('worker:advance', 'labour', 'Record cash or goods handed to a worker against his wages');

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
  FROM roles r
  JOIN permissions p ON p.code = 'worker:advance'
 WHERE r.code IN ('ADMIN', 'ENGINEER', 'SUPERVISOR', 'ACCOUNTANT')
   AND r.is_system
   AND NOT EXISTS (SELECT 1 FROM role_permissions rp
                    WHERE rp.role_id = r.id AND rp.permission_id = p.id);
