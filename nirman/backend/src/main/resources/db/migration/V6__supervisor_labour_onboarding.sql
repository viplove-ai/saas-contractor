-- Nirman — the supervisor onboards his own labour.
--
-- The field reality this follows: men turn up at the gate and start the same morning. The
-- supervisor is the only person there to take their name, so requiring an admin at a desk
-- to create the worker first means the first day's attendance is marked against nobody.
--
-- He gets worker:write, which also carries the posting: creating a worker at his site and
-- transferring one away are both worker:write in WorkerService.
--
-- He deliberately does NOT get wage:write. Onboarding a man and deciding what he is paid
-- are different acts, and the second one stays with the admin — a supervisor who could set
-- his own crew's rates could raise them. WorkerService.create enforces the same split: a
-- creation carrying a rate is rejected without wage:write.
--
-- wage:read comes with it so the worker list can show the rate in force. Seeing what the
-- office has set is not the same as setting it.

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON p.code IN ('worker:write', 'wage:read')
WHERE r.code = 'SUPERVISOR' AND r.is_system
  AND NOT EXISTS (
        SELECT 1 FROM role_permissions rp
        WHERE rp.role_id = r.id AND rp.permission_id = p.id);
