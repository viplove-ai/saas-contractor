-- Nirman — the people on the payroll, as an employer has to hold them.
--
-- `users` is a login. It carries a name, a mobile and a password hash, which is everything
-- needed to let somebody in and nothing an employer needs to keep about them: no address to
-- send a letter to, no next of kin to telephone, no bank account to pay into, and no record
-- of what was agreed about their pay. All of that lived in a diary.
--
-- Two tables, because they answer two different kinds of question.
--
-- `staff_profiles` is who somebody is and what was agreed when they were taken on. One row
-- per member, edited in place — an address that changes is not history, it is a correction.
--
-- `staff_salary_revisions` is what they are actually paid, from when. Append-only, because
-- history does not move: a raise in April must not rewrite March's cost, exactly as
-- attendance freezes a wage rate at verification so a later revision cannot change last
-- month's labour. The agreed figures on the profile are the *offer*; these rows are the
-- *record*, and they are allowed to disagree — an offer that was never honoured is a fact
-- worth being able to see.

-- ---------------------------------------------------------------- who they are
CREATE TABLE staff_profiles (
    id                       uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id                   uuid NOT NULL REFERENCES organisations(id),
    user_id                  uuid NOT NULL REFERENCES users(id),

    -- Identity and reach. The mobile that signs them in stays on `users`; this is the
    -- second number, for when the first one is in a pocket on a scaffold.
    alternate_mobile         varchar(20),
    date_of_birth            date,

    -- Four digits, never the whole number, exactly as `workers.aadhaar_last4` already does.
    -- Enough to tell two men with the same name apart on a payroll, which is the only reason
    -- the field needs to exist; storing the full number would make this table something an
    -- employer has a legal duty to protect and no reason to hold.
    aadhaar_last4            char(4),
    pan                      varchar(10),

    -- Two addresses because they differ for most site staff, and each is needed for a
    -- different thing: the current one to reach somebody this week, the permanent one for
    -- the records and for where they go when the job ends.
    current_address          text,
    permanent_address        text,

    -- Next of kin. The one field on this table that is read in a hurry.
    emergency_contact_name   varchar(150),
    emergency_contact_mobile varchar(20),
    emergency_contact_relation varchar(60),

    -- Where the salary goes.
    bank_account_name        varchar(150),
    bank_account_no          varchar(30),
    bank_ifsc                varchar(15),
    bank_name                varchar(100),

    -- The terms. PROBATION is a state somebody leaves; PERMANENT and CONTRACTUAL are not.
    employment_type          varchar(20) NOT NULL DEFAULT 'PERMANENT',
    joined_on                date,
    -- How long the probation was agreed to run. The date it ends is joined_on plus this and
    -- is derived, never stored — a stored end date is a second version of the same fact and
    -- the one that stops matching when a joining date is corrected.
    probation_days           int,
    probation_monthly_salary numeric(14,2),
    confirmed_monthly_salary numeric(14,2),
    -- When probation actually ended, which is a fact and not a calculation: probation gets
    -- extended, and it gets cut short for somebody who is obviously good.
    confirmed_on             date,
    -- Contract staff have an end date; permanent staff do not, and a null here is not an
    -- omission.
    contract_ends_on         date,

    exit_date                date,
    exit_reason              varchar(500),
    notes                    text,

    created_at               timestamptz NOT NULL DEFAULT now(),
    updated_at               timestamptz NOT NULL DEFAULT now(),
    created_by               uuid,
    updated_by               uuid,
    version                  bigint NOT NULL DEFAULT 0,

    CONSTRAINT uq_staff_profile_user UNIQUE (user_id),
    CONSTRAINT ck_staff_employment_type
        CHECK (employment_type IN ('PERMANENT', 'CONTRACTUAL', 'PROBATION')),
    -- A probation with no length is not a probation, it is an indefinite one, and that is
    -- the arrangement this refuses to let anybody record by accident.
    CONSTRAINT ck_staff_probation_has_a_length
        CHECK (employment_type <> 'PROBATION' OR probation_days IS NOT NULL),
    CONSTRAINT ck_staff_probation_days CHECK (probation_days IS NULL OR probation_days BETWEEN 1 AND 730),
    CONSTRAINT ck_staff_salaries_non_negative CHECK (
        (probation_monthly_salary IS NULL OR probation_monthly_salary >= 0)
        AND (confirmed_monthly_salary IS NULL OR confirmed_monthly_salary >= 0)),
    CONSTRAINT ck_staff_aadhaar_last4 CHECK (aadhaar_last4 IS NULL OR aadhaar_last4 ~ '^[0-9]{4}$')
);

COMMENT ON TABLE staff_profiles IS
    'What an employer holds about somebody on the payroll: how to reach them, who to '
    'telephone, where the salary goes, and what was agreed when they were taken on. The '
    'login itself stays on users.';

COMMENT ON COLUMN staff_profiles.aadhaar_last4 IS
    'The last four digits only, never the whole number — the same rule workers.aadhaar_last4 '
    'follows. Four digits tell two people of the same name apart, which is all it is for.';

COMMENT ON COLUMN staff_profiles.probation_days IS
    'Agreed length. The end date is joined_on + this, derived on read; confirmed_on records '
    'when probation actually ended, which is frequently not the same day.';

-- The dashboard reads by organisation and groups by state; the profile screen reads by user.
CREATE INDEX ix_staff_profiles_org ON staff_profiles (org_id, employment_type);

-- ---------------------------------------------------------------- what they are paid
-- Append-only, and not edited: a revision that replaced the last one would rewrite what a
-- month cost. The row that applies on a date is the newest one whose effective_from is not
-- after it.
CREATE TABLE staff_salary_revisions (
    id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id         uuid NOT NULL REFERENCES organisations(id),
    user_id        uuid NOT NULL REFERENCES users(id),
    monthly_amount numeric(14,2) NOT NULL,
    effective_from date NOT NULL,
    -- Why it moved: a confirmation off probation, an annual raise, a change of role. Kept
    -- because six months on a figure that changed for no recorded reason is a figure
    -- somebody has to go and ask about.
    reason         varchar(300) NOT NULL,
    created_at     timestamptz NOT NULL DEFAULT now(),
    created_by     uuid,

    CONSTRAINT ck_staff_salary_non_negative CHECK (monthly_amount >= 0),
    -- One figure per person per day. Two rows effective the same morning is a pay rate
    -- decided by whichever the query happened to sort first.
    CONSTRAINT uq_staff_salary_effective UNIQUE (user_id, effective_from)
);

COMMENT ON TABLE staff_salary_revisions IS
    'What a member is actually paid, from when. Append-only: a raise in April must not '
    'rewrite what March cost.';

CREATE INDEX ix_staff_salary_user ON staff_salary_revisions (user_id, effective_from DESC);

-- ---------------------------------------------------------------- who may read it
-- Two permissions, not one, and both admin-only to begin with. Reading somebody's home
-- address and their bank account is not the same act as changing their salary, and the day
-- an office manager needs the first without the second, the split is already there.
INSERT INTO permissions (code, module, description) VALUES
    ('staff:read',  'identity', 'View staff records: contact, bank and employment terms'),
    ('staff:write', 'identity', 'Maintain staff records and record salary revisions');

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
  FROM roles r
  JOIN permissions p ON p.code IN ('staff:read', 'staff:write')
 WHERE r.code = 'ADMIN'
   AND r.is_system
   AND NOT EXISTS (SELECT 1 FROM role_permissions rp
                    WHERE rp.role_id = r.id AND rp.permission_id = p.id);
