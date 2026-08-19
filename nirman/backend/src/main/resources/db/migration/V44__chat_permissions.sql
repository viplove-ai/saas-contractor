-- Belongs in nirman/backend/src/main/resources/db/migration/, not here. Renumber if V44 is taken.
--
-- Two permissions the messenger asks about. They are seeded rows rather than constants in code
-- because that is Nirman's rule — a new permission is a migration — and the rule is worth
-- keeping across the service boundary rather than only inside it.
--
-- Note the grant is written out explicitly. V2 gives ADMIN every permission with a CROSS JOIN
-- and its comment says "present and future migrations included", but that statement is a
-- one-time insert: it cannot grant a permission that did not exist when it ran. Every later
-- migration that adds one grants it again (see V25__site_equipment.sql), and so does this.
-- Without it, nobody can block a user or post an announcement and nothing says why.

-- `module` is NOT NULL and every other seeding migration passes it — V2 groups permissions by
-- the module that owns them ('expense', 'inventory', 'identity'), and V25 adds equipment under
-- 'inventory'. Omitting it here is what took the API down on 19 August: Flyway runs before the
-- port opens, so a migration that cannot apply is not a failed migration, it is a service that
-- does not start.
INSERT INTO permissions (id, code, module, description)
SELECT gen_random_uuid(), v.code, v.module, v.description
  FROM (VALUES
      ('chat:restrict', 'chat', 'Mute or block a user in the Sandesh messenger'),
      ('chat:announce', 'chat', 'Post to the organisation-wide announcements channel')
  ) AS v(code, module, description)
 WHERE NOT EXISTS (SELECT 1 FROM permissions p WHERE p.code = v.code);

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
  FROM roles r
  JOIN permissions p ON p.code IN ('chat:restrict', 'chat:announce')
 WHERE r.code = 'ADMIN' AND r.is_system
   AND NOT EXISTS (SELECT 1 FROM role_permissions rp
                    WHERE rp.role_id = r.id AND rp.permission_id = p.id);

-- Deliberately not granted to ENGINEER, SUPERVISOR or ACCOUNTANT. Announcing to the whole
-- company and locking somebody out of it are administration, not site work — and under Nirman's
-- grant model these reach every ADMIN, so withholding one from some administrators and not
-- others would need a new role rather than a new permission.
