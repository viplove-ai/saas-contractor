-- Nirman — deleting a project or a site.
--
-- The columns for this arrived in V1: projects.deleted_at and sites.deleted_at have been
-- there since the baseline, and every read in the codebase already filters on them. What
-- was missing was the act — nothing could set them, see past them, or undo them.
--
-- Two things are added here. The permissions, because a permission is a migration and not
-- a constant. And the *reason*, because a deletion an administrator cannot explain six
-- months later is indistinguishable from data loss; the audit log records the act, these
-- columns put it on the screen next to the row without joining audit_logs (which is
-- append-only and read by plain JDBC on purpose).

-- ---------------------------------------------------------------- who deleted, and why
ALTER TABLE projects
    ADD COLUMN deleted_by     uuid REFERENCES users(id),
    ADD COLUMN deleted_reason varchar(500);

ALTER TABLE sites
    ADD COLUMN deleted_by     uuid REFERENCES users(id),
    ADD COLUMN deleted_reason varchar(500);

-- The deleted list is the rare read, so it gets the partial index the common read already
-- has in reverse. Both stay small: deletion is for mistakes, not for closing work down.
CREATE INDEX ix_projects_deleted ON projects (org_id, deleted_at DESC) WHERE deleted_at IS NOT NULL;
CREATE INDEX ix_sites_deleted    ON sites (org_id, deleted_at DESC)    WHERE deleted_at IS NOT NULL;

-- ---------------------------------------------------------------- permissions
INSERT INTO permissions (code, module, description) VALUES
    ('project:delete', 'project', 'Delete and restore projects'),
    ('site:delete',    'project', 'Delete and restore sites');

-- V2 gave ADMIN every permission by CROSS JOIN, but that ran once against the catalogue as
-- it stood then. A permission added later has to be granted explicitly.
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON p.code IN ('project:delete', 'site:delete')
WHERE r.code = 'ADMIN' AND r.is_system;

-- Deliberately granted to nobody else. An engineer closes a site; only an administrator
-- takes one off the books.
