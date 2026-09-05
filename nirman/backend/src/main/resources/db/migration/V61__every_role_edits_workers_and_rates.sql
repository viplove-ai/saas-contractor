-- Nirman — every role may edit a worker and set what he is paid.
--
-- V6 gave the supervisor worker:write and kept wage:write with the administrator, on the
-- argument that a man who could set his own crew's rates could raise them. That held while
-- the office was reachable and rates were agreed in an office. The firm decided otherwise:
-- the rate is agreed at the gate, by whoever takes the man on, and a worker who stands on the
-- roll for a week with "no rate yet" against him carries days that cost nothing — which is
-- the figure that reaches the dashboard. A rate typed by the man who agreed it is a truer
-- record than none, and every revision is still audited and frozen onto the attendance it
-- priced, so a rate raised after the fact repays nothing that was already verified.
--
-- So the four labour permissions go to every system role. The engineer already held three
-- of them; the supervisor two; the accountant, who keeps the wage ledger, held only wage:read
-- and could not so much as open the register. Each grant is guarded, because V2 and V6 have
-- already written some of these rows.
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON p.code IN ('worker:read', 'worker:write', 'wage:read', 'wage:write')
WHERE r.code IN ('ADMIN', 'ENGINEER', 'SUPERVISOR', 'ACCOUNTANT') AND r.is_system
  AND NOT EXISTS (
        SELECT 1 FROM role_permissions rp
        WHERE rp.role_id = r.id AND rp.permission_id = p.id);
