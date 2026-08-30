-- Nirman — what a salary is made of, and the month's document that says it was paid.
--
-- V22 gave a member one figure: `staff_salary_revisions.monthly_amount`, effective from a
-- date, append-only. That is the right shape and the wrong resolution. A salary in India is
-- not one number, because the law does not treat it as one: provident fund is computed on
-- basic and dearness allowance, employees' state insurance on the whole of what is paid,
-- gratuity on the same wage the fund uses, and the Code on Wages then overrules all three by
-- saying that when the allowances exceed half of what somebody is paid, the excess counts as
-- wages anyway. A single gross cannot answer any of those questions, so the office answered
-- them in a spreadsheet and the system held a figure that agreed with the payslip by luck.
--
-- Three things here.
--
--   1. The structure, on the revision that already carries the date it applies from. Not a
--      new table: `staff_salary_revisions` is *already* the append-only record of what
--      applies from when, and a second effective-dated table beside it would be two answers
--      to one question, disagreeing the first time somebody edited one of them.
--   2. The enrolment numbers, on the profile. A UAN and an ESIC number are facts about the
--      person that get corrected in place — a mistyped UAN is not history.
--   3. `payroll_runs` and `payslips`: the month, and the document it produced.
--
-- ---------------------------------------------------------------- the statutory rates
-- Nothing here stores a rate or a ceiling. 12%, 8.33%, 0.75%, 3.25%, the ₹15,000 provident
-- fund ceiling and the ₹21,000 insurance ceiling live in one Java class with the date they
-- were last checked against the notifications. They are national law, identical for every
-- organisation on the system, and a column for them would be an invitation to type one
-- wrong — which is the one mistake in this whole feature that nobody would ever notice,
-- because a payslip with the wrong provident fund rate looks exactly like a correct one.


-- ================================================================ 1. the structure
--
-- Five components and no more. Basic, dearness allowance, house rent allowance, conveyance
-- and one bucket for everything else. Employers invent allowances endlessly — washing,
-- education, special, city compensatory — and a table with a column per invention is a
-- migration every time an office renames one. The five here are the ones the *law* can
-- tell apart: basic and dearness allowance are "wages", house rent allowance and conveyance
-- are the named exclusions the Code on Wages lists, and everything else is a lump the
-- fifty-per-cent rule swallows together anyway. An office that pays a washing allowance
-- puts it in `other_allowance` and loses nothing a statute cares about.
--
-- Nullable, because V22's rows are not wrong — they are older. A revision with no basic is
-- a gross somebody recorded before the structure existed, and it is still the true answer
-- to "what was he paid in March". What it cannot do is produce a payslip, and the service
-- says so in a sentence rather than inventing a split.
ALTER TABLE staff_salary_revisions
    ADD COLUMN basic             numeric(14,2),
    ADD COLUMN dearness_allowance numeric(14,2),
    ADD COLUMN hra               numeric(14,2),
    ADD COLUMN conveyance        numeric(14,2),
    ADD COLUMN other_allowance   numeric(14,2),
    -- A deduction, not a component, so it is deliberately outside the sum below. Professional
    -- tax is state law on a slab of the salary, so it moves when the salary moves, which is
    -- why it belongs on the revision rather than on the profile. The slabs themselves are not
    -- modelled: they are twenty different state schedules amended by notification, and a
    -- wrong slab hardcoded here would be deducted from somebody's pay every month with great
    -- confidence. The office types what its state charges.
    ADD COLUMN professional_tax  numeric(14,2);

COMMENT ON COLUMN staff_salary_revisions.basic IS
    'The structure this revision put in place. Null on rows written before V54 — a gross with '
    'no breakdown, which is a true record of what was paid and cannot produce a payslip.';

-- The components must come to the gross. This is the one arithmetic mistake that would
-- otherwise reach a printed document: a structure whose parts do not add up produces a
-- payslip whose earnings do not match the salary it claims to be paying, and the employee is
-- the one who notices.
ALTER TABLE staff_salary_revisions
    ADD CONSTRAINT ck_salary_components_sum CHECK (
        basic IS NULL OR monthly_amount = basic
            + COALESCE(dearness_allowance, 0) + COALESCE(hra, 0)
            + COALESCE(conveyance, 0) + COALESCE(other_allowance, 0));

ALTER TABLE staff_salary_revisions
    ADD CONSTRAINT ck_salary_components_non_negative CHECK (
        (basic IS NULL OR basic >= 0)
        AND (dearness_allowance IS NULL OR dearness_allowance >= 0)
        AND (hra IS NULL OR hra >= 0)
        AND (conveyance IS NULL OR conveyance >= 0)
        AND (other_allowance IS NULL OR other_allowance >= 0)
        AND (professional_tax IS NULL OR professional_tax >= 0));


-- ================================================================ 2. the enrolment
ALTER TABLE staff_profiles
    -- What the payslip calls him. The screenshot's "Employee Number" — a short number the
    -- office already uses on its muster and its bank advice, and which is not the UUID.
    ADD COLUMN employee_number   varchar(20),
    ADD COLUMN designation       varchar(100),
    -- Twelve digits, issued once and carried between employers; ten for the insurance number.
    -- Both are printed on the payslip and both are how a claim is made, so a wrong one is
    -- money that reaches the fund and not the person.
    ADD COLUMN uan               varchar(12),
    ADD COLUMN esic_number       varchar(17),
    -- Whether the two statutes reach this member at all. Stored decisions rather than tests
    -- run every month, and that is the point for insurance especially: coverage is decided
    -- for a whole contribution period (April–September, October–March) and does *not* stop
    -- mid-period because a raise carried somebody over the ceiling. A monthly re-test would
    -- drop him out in July and the establishment would be short-paid for three months.
    ADD COLUMN pf_applicable     boolean NOT NULL DEFAULT false,
    ADD COLUMN esi_applicable    boolean NOT NULL DEFAULT false,
    -- Whether the fund is paid on the whole wage or only on the statutory ceiling. Both are
    -- lawful and the choice is per member, not per organisation: an existing member who was
    -- already contributing on full wages may not be pushed down to the ceiling, and a new
    -- joiner above it may be restricted to it. Default false, which is the restricted case
    -- and the one an office that has not thought about it means.
    ADD COLUMN pf_on_full_wages  boolean NOT NULL DEFAULT false,
    -- Printed on the offer letter, and the one term a leaving argument is always about.
    ADD COLUMN notice_period_days int;

ALTER TABLE staff_profiles
    ADD CONSTRAINT ck_staff_uan CHECK (uan IS NULL OR uan ~ '^[0-9]{12}$'),
    ADD CONSTRAINT ck_staff_notice_period
        CHECK (notice_period_days IS NULL OR notice_period_days BETWEEN 0 AND 365);

-- One number per person in an organisation. Two people sharing an employee number is a bank
-- advice that pays one of them twice, and the office finds out from the other one.
CREATE UNIQUE INDEX uq_staff_employee_number
    ON staff_profiles (org_id, employee_number) WHERE employee_number IS NOT NULL;


-- ================================================================ 3. the month
--
-- A run is the month, not a button. It exists so that "has July been done" has an answer, so
-- that the twenty payslips of one month can be totalled and reconciled against one bank
-- transfer, and so that finalising is a single act with a time and a name on it rather than
-- twenty separate ones that can be half-finished.
CREATE TABLE payroll_runs (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id        uuid NOT NULL REFERENCES organisations(id),
    -- Always the first of the month. A month is not a date range anybody should be able to
    -- type: two runs for "July" that disagree about whether it ended on the 30th or the 31st
    -- is a fortnight of arguing about a spreadsheet.
    period_month  date NOT NULL,
    status        varchar(20) NOT NULL DEFAULT 'DRAFT',

    -- How the month is counted, stored on the run because it is a decision about the month
    -- and must be the same for everybody in it. Most CPWD-side offices pay against 26 days;
    -- others use the calendar. Either is fine and mixing them within one month is not.
    payable_days  int NOT NULL,

    notes         varchar(500),
    finalised_at  timestamptz,
    finalised_by  uuid REFERENCES users(id),

    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now(),
    created_by    uuid REFERENCES users(id),
    updated_by    uuid REFERENCES users(id),
    version       bigint NOT NULL DEFAULT 0,

    CONSTRAINT uq_payroll_run_month UNIQUE (org_id, period_month),
    CONSTRAINT ck_payroll_run_status CHECK (status IN ('DRAFT', 'FINALISED')),
    CONSTRAINT ck_payroll_run_month_is_first CHECK (EXTRACT(DAY FROM period_month) = 1),
    CONSTRAINT ck_payroll_run_payable_days CHECK (payable_days BETWEEN 1 AND 31),
    -- A finalisation is by somebody at a time, or it is not a finalisation. The same shape
    -- V37 put on the report's approval.
    CONSTRAINT ck_payroll_run_finalised CHECK (
        (status = 'FINALISED') = (finalised_at IS NOT NULL AND finalised_by IS NOT NULL))
);

COMMENT ON TABLE payroll_runs IS
    'One month of payroll for an organisation. Draft while the figures are being collected, '
    'finalised once, and never reopened — see payslips for why.';

CREATE INDEX ix_payroll_runs_org ON payroll_runs (org_id, period_month DESC);


-- ---------------------------------------------------------------- the document itself
--
-- Every figure here is frozen at the moment the slip is drawn, and that is deliberate against
-- the rule that says rolled-up figures are derived. A dashboard tile is a question about
-- today and must be recomputed; a payslip is a *document issued to a person*, kept for three
-- years by statute, and reconciled against a bank transfer that has already happened. A
-- payslip that recomputed itself would change the day somebody corrected a salary revision,
-- and the copy in the employee's hand would stop matching the copy in the office — which is
-- the one disagreement no employer can afford. The DPR froze its snapshot at handover for
-- exactly this reason and this is the same rule one register along.
--
-- The identity fields are frozen too, and for the same reason: a woman who marries in August
-- and changes her name on the record has not changed the name on July's payslip, because
-- July's payslip has been printed.
CREATE TABLE payslips (
    id                 uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id             uuid NOT NULL REFERENCES organisations(id),
    run_id             uuid NOT NULL REFERENCES payroll_runs(id) ON DELETE CASCADE,
    user_id            uuid NOT NULL REFERENCES users(id),
    -- Repeated off the run so a payslip can be read, indexed and printed without it. It is
    -- the one denormalisation here and it is safe because a run's month never moves.
    period_month       date NOT NULL,

    -- ------------------------------------------------ who, as at the moment it was drawn
    employee_name      varchar(200) NOT NULL,
    employee_number    varchar(20),
    designation        varchar(100),
    uan                varchar(12),
    esic_number        varchar(17),

    -- ------------------------------------------------ what was agreed (the full month)
    struct_basic       numeric(14,2) NOT NULL,
    struct_da          numeric(14,2) NOT NULL DEFAULT 0,
    struct_hra         numeric(14,2) NOT NULL DEFAULT 0,
    struct_conveyance  numeric(14,2) NOT NULL DEFAULT 0,
    struct_other       numeric(14,2) NOT NULL DEFAULT 0,
    struct_gross       numeric(14,2) NOT NULL,

    -- ------------------------------------------------ what the month came to
    payable_days       int NOT NULL,
    paid_days          numeric(5,2) NOT NULL,

    earned_basic       numeric(14,2) NOT NULL,
    earned_da          numeric(14,2) NOT NULL DEFAULT 0,
    earned_hra         numeric(14,2) NOT NULL DEFAULT 0,
    earned_conveyance  numeric(14,2) NOT NULL DEFAULT 0,
    earned_other       numeric(14,2) NOT NULL DEFAULT 0,
    overtime_hours     numeric(7,2) NOT NULL DEFAULT 0,
    overtime_amount    numeric(14,2) NOT NULL DEFAULT 0,
    -- Whether that amount was typed rather than worked out. The Code on Wages sets overtime at
    -- twice the ordinary rate, so the amount normally follows from the hours and nobody types
    -- it; an office that has agreed a different rate types it, and this column is what stops a
    -- redraw quietly replacing his figure with the statutory one. Without it, "redrawing keeps
    -- what the office typed" would be true of four fields and false of the fifth, which is the
    -- kind of exception nobody remembers until a month goes out wrong.
    overtime_overridden boolean NOT NULL DEFAULT false,
    total_earnings     numeric(14,2) NOT NULL,

    -- ------------------------------------------------ the wage the statutes actually use
    -- The Code on Wages figure: basic plus dearness allowance, lifted to half of the whole
    -- remuneration when the allowances have been let run past that. Stored rather than
    -- recomputed because it is the basis every contribution below was worked out on, and a
    -- rule that changes by notification must not silently restate a slip already issued.
    statutory_wages    numeric(14,2) NOT NULL,

    -- ------------------------------------------------ deducted from him
    pf_wages           numeric(14,2) NOT NULL DEFAULT 0,
    pf_employee        numeric(14,2) NOT NULL DEFAULT 0,
    esi_wages          numeric(14,2) NOT NULL DEFAULT 0,
    esi_employee       numeric(14,2) NOT NULL DEFAULT 0,
    professional_tax   numeric(14,2) NOT NULL DEFAULT 0,
    -- Named for the section of the Income-tax Act 2025 that a slip issued now must cite.
    -- Typed, never computed: the deduction depends on the member's election between the two
    -- regimes, on declarations he makes and proofs he produces, and this system holds none of
    -- the three. A figure guessed here is money deposited to the government in somebody
    -- else's name, and the mistake surfaces a year later at his return.
    tds                numeric(14,2) NOT NULL DEFAULT 0,
    -- Recovery of an advance against pay. Typed for now and not drawn from a register: a
    -- loan against salary is its own ledger — a principal, a schedule, a balance that must
    -- reach zero — and half of one, recovered by a number retyped every month, would be
    -- worse than none. `site_advances` is deliberately not it: that is petty cash for the
    -- site's expenses, cleared by producing bills, and netting a man's wages against the
    -- float in his pocket would confuse two entirely different debts.
    salary_advance     numeric(14,2) NOT NULL DEFAULT 0,
    other_deduction    numeric(14,2) NOT NULL DEFAULT 0,
    other_deduction_note varchar(200),
    total_deductions   numeric(14,2) NOT NULL,

    net_amount         numeric(14,2) NOT NULL,

    -- ------------------------------------------------ what the employer paid on top
    -- Not deducted from anybody and not part of the net. It is on the row because the cost of
    -- employing somebody is the figure the office needs and the payslip is the only place all
    -- of its parts are known at once; it prints on the employer's copy and not on his.
    pf_employer        numeric(14,2) NOT NULL DEFAULT 0,
    eps_employer       numeric(14,2) NOT NULL DEFAULT 0,
    esi_employer       numeric(14,2) NOT NULL DEFAULT 0,

    remarks            varchar(300),

    created_at         timestamptz NOT NULL DEFAULT now(),
    updated_at         timestamptz NOT NULL DEFAULT now(),
    created_by         uuid REFERENCES users(id),
    updated_by         uuid REFERENCES users(id),
    version            bigint NOT NULL DEFAULT 0,

    -- One slip per person per run. Two is two documents saying different things about one
    -- month, and the employee has whichever was printed first.
    CONSTRAINT uq_payslip_member UNIQUE (run_id, user_id),
    CONSTRAINT ck_payslip_days CHECK (paid_days >= 0 AND paid_days <= payable_days),
    CONSTRAINT ck_payslip_earnings_add_up CHECK (
        total_earnings = earned_basic + earned_da + earned_hra + earned_conveyance
            + earned_other + overtime_amount),
    CONSTRAINT ck_payslip_deductions_add_up CHECK (
        total_deductions = pf_employee + esi_employee + professional_tax + tds
            + salary_advance + other_deduction),
    CONSTRAINT ck_payslip_net CHECK (net_amount = total_earnings - total_deductions),
    CONSTRAINT ck_payslip_struct_adds_up CHECK (
        struct_gross = struct_basic + struct_da + struct_hra + struct_conveyance + struct_other),
    CONSTRAINT ck_payslip_non_negative CHECK (
        struct_basic >= 0 AND struct_gross >= 0 AND total_earnings >= 0
        AND pf_employee >= 0 AND esi_employee >= 0 AND professional_tax >= 0
        AND tds >= 0 AND salary_advance >= 0 AND other_deduction >= 0
        AND overtime_hours >= 0 AND overtime_amount >= 0),
    -- A note explains a figure; a note with no figure explains nothing, and a figure with no
    -- note is the deduction the employee telephones about. The same one-way pairing V34 put
    -- on a lost day's cause.
    CONSTRAINT ck_payslip_other_deduction_explained CHECK (
        other_deduction = 0 OR other_deduction_note IS NOT NULL)
);

COMMENT ON TABLE payslips IS
    'One member, one month, frozen. Not derived like a dashboard tile: this is a document '
    'issued to a person and reconciled against a transfer that has already left the bank.';

CREATE INDEX ix_payslips_run ON payslips (run_id);
CREATE INDEX ix_payslips_member ON payslips (org_id, user_id, period_month DESC);


-- ================================================================ 4. who may do it
--
-- Two, split the way `staff:read` and `staff:write` are split and for the same reason:
-- reading what the office paid last month is a question a manager may need answered, and
-- deciding what it pays this month is not the same act.
--
-- What is deliberately *not* here is a third permission for issuing the payslip. Drawing the
-- month, checking it and finalising it are one job done by one person in one sitting, and an
-- organisation that could grant the drawing without the finalising would have somebody able
-- to produce twenty documents that nobody is able to make final.
INSERT INTO permissions (code, module, description) VALUES
    ('payroll:read',    'payroll', 'View payroll runs and payslips'),
    ('payroll:process', 'payroll', 'Draw a month''s payroll, enter its figures and finalise it');

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
  FROM roles r
  JOIN permissions p ON p.code IN ('payroll:read', 'payroll:process')
 WHERE r.code IN ('ADMIN', 'ACCOUNTANT')
   AND r.is_system
   AND NOT EXISTS (SELECT 1 FROM role_permissions rp
                    WHERE rp.role_id = r.id AND rp.permission_id = p.id);

-- The accountant gets the staff record as well, which V22 gave to the administrator alone.
--
-- That was right when the record held an address and a next of kin. It stopped being right
-- the moment the record became the thing salaries are computed from: the man who pays twenty
-- people needs the bank account he is paying into, the structure he is paying against and the
-- provident fund number the money is filed under, and an accountant who has to ask the
-- administrator for each of those is an accountant who keeps his own copy in a spreadsheet —
-- which is the second version of the truth this whole table exists to abolish. He gets the
-- write for the same reason: in a contractor's office the accountant is the person who
-- actually types the structure the proprietor decided.
--
-- An organisation that disagrees revokes it; the permission is a row, which is the whole
-- point of permissions being rows.
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
  FROM roles r
  JOIN permissions p ON p.code IN ('staff:read', 'staff:write')
 WHERE r.code = 'ACCOUNTANT'
   AND r.is_system
   AND NOT EXISTS (SELECT 1 FROM role_permissions rp
                    WHERE rp.role_id = r.id AND rp.permission_id = p.id);


-- ================================================================ 5. the offer letter
--
-- It is filed on the member's record as a paper, through V51's register, and needs a name of
-- its own on the closed list. An offer and an appointment letter are two documents and two
-- moments — one is made and may be declined, the other confirms a person who has started —
-- and an office asking "who has not returned a signed offer" cannot be answered by a list
-- that calls both of them APPOINTMENT.
--
-- No new permission. Generating it states terms that are already on the record and files the
-- result beside the papers those terms were typed off; `staff:write` is exactly that act of
-- custody, which is the argument V51 made about holding the passbook and the account number.
ALTER TABLE staff_documents DROP CONSTRAINT ck_staff_document_type;
ALTER TABLE staff_documents ADD CONSTRAINT ck_staff_document_type CHECK (doc_type IN (
    'AADHAAR', 'PAN', 'BANK', 'OFFER_LETTER', 'APPOINTMENT', 'EDUCATION',
    'POLICE_VERIFICATION', 'PHOTOGRAPH', 'OTHER'));
