-- Belongs in nirman/backend/src/main/resources/db/migration/, not here. It lives in this repo
-- so the contract Sandesh depends on is version-controlled beside the thing that depends on it;
-- copy it across and let Nirman's Flyway apply it.
--
-- Renumber if V43 is taken by the time you apply it. Nothing else about it is order-sensitive.
--
-- Two views and a role. They are the entire surface Sandesh has on Nirman's data: no tables, no
-- writes, ever. Refactoring `users` or `user_site_assignments` behind these breaks nothing in
-- the messenger, which is the point of a view rather than a grant on the tables.

CREATE OR REPLACE VIEW chat_directory_v AS
SELECT u.id       AS user_id,
       u.org_id,
       u.full_name,
       u.username,
       u.session_epoch,
       u.is_active
  FROM users u;

COMMENT ON VIEW chat_directory_v IS
    'Published contract for the Sandesh messenger. Adding a column is safe; removing or '
    'renaming one breaks it. session_epoch is what lets a password reset in Nirman close a '
    'live message stream.';

-- Live postings only. `assigned_from <= CURRENT_DATE` is deliberate: an assignment that starts
-- tomorrow is not a membership today, and somebody added to a site sees nothing said before
-- they arrived — which is the retention rule expressed as a join.
CREATE OR REPLACE VIEW chat_site_membership_v AS
SELECT a.user_id,
       a.site_id,
       s.project_id,
       s.org_id,
       s.name AS site_name,
       p.name AS project_name
  FROM user_site_assignments a
  JOIN sites    s ON s.id = a.site_id    AND s.deleted_at IS NULL
  JOIN projects p ON p.id = s.project_id AND p.deleted_at IS NULL
 WHERE a.assigned_from <= CURRENT_DATE
   AND (a.assigned_to IS NULL OR a.assigned_to >= CURRENT_DATE);

COMMENT ON VIEW chat_site_membership_v IS
    'Published contract for the Sandesh messenger. Who may reach which site today, joined to '
    'the project that names it. Deleted sites and projects are excluded here rather than in '
    'the caller.';

-- ---------------------------------------------------------------- the reader
-- Created without a password; set one out of band, because this file is committed:
--
--   ALTER ROLE chat_reader WITH PASSWORD '...';
--
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'chat_reader') THEN
        CREATE ROLE chat_reader LOGIN;
    END IF;
END
$$;

GRANT USAGE ON SCHEMA public TO chat_reader;
GRANT SELECT ON chat_directory_v, chat_site_membership_v TO chat_reader;

-- Belt and braces: if a future migration grants SELECT on new tables by default, chat_reader
-- must not inherit it. The views are the contract; nothing else is.
REVOKE ALL ON ALL TABLES IN SCHEMA public FROM chat_reader;
GRANT SELECT ON chat_directory_v, chat_site_membership_v TO chat_reader;
