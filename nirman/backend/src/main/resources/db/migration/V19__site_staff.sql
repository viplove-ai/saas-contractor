-- Nirman — a site is run by however many people run it.
--
-- Two columns on `sites` said one engineer and one supervisor, and a site with a day shift
-- and a night shift, or a big block split between two supervisors, had no way to say so.
-- The register was forced to name one of them, which then decided who the access guard let
-- in: the second supervisor was not merely unlisted, he was locked out. Naming somebody on
-- a site is what grants them the site, so a column that holds one name is a rule about how
-- many people may work there.
--
-- So the post becomes a row. `site_staff` carries one row per person per post per site, and
-- the same person may hold both posts on a site that has only him — which the two columns
-- also allowed, and which is common enough on a small job that it is worth keeping.
--
-- The old columns go. Keeping them as a summary of the first name in the list would be a
-- second version of the truth, and the version the guard did not read.

CREATE TABLE site_staff (
    id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id     uuid        NOT NULL REFERENCES organisations(id),
    site_id    uuid        NOT NULL REFERENCES sites(id),
    user_id    uuid        NOT NULL REFERENCES users(id),
    post       varchar(20) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    created_by uuid,
    -- The two posts a site names. Not the role table: a role is what somebody may do
    -- anywhere, and this is what they are called here. An administrator may stand in for
    -- either, and does so under the post, not under ADMIN.
    CONSTRAINT ck_site_staff_post CHECK (post IN ('ENGINEER', 'SUPERVISOR')),
    -- One row per person per post. Naming the same man supervisor twice is not two
    -- supervisors, and the second row would make removing him take two attempts.
    CONSTRAINT uq_site_staff UNIQUE (site_id, user_id, post)
);

COMMENT ON TABLE site_staff IS
    'Who is named on a site, and as what. Distinct from user_site_assignments, which says '
    'who may reach it: naming somebody here opens an assignment there, never the reverse.';

-- The register draws one site's names; the posting guard asks what one member is named on.
CREATE INDEX ix_site_staff_site ON site_staff (site_id);
CREATE INDEX ix_site_staff_user ON site_staff (user_id);

-- ---------------------------------------------------------------- what the columns held
-- Deleted sites included. Their names are what a restore puts back, and dropping them here
-- would quietly empty the register of every site somebody takes off the books and returns.
INSERT INTO site_staff (id, org_id, site_id, user_id, post)
SELECT gen_random_uuid(), s.org_id, s.id, s.site_engineer_id, 'ENGINEER'
  FROM sites s
 WHERE s.site_engineer_id IS NOT NULL;

INSERT INTO site_staff (id, org_id, site_id, user_id, post)
SELECT gen_random_uuid(), s.org_id, s.id, s.supervisor_id, 'SUPERVISOR'
  FROM sites s
 WHERE s.supervisor_id IS NOT NULL;

ALTER TABLE sites DROP COLUMN site_engineer_id;
ALTER TABLE sites DROP COLUMN supervisor_id;
