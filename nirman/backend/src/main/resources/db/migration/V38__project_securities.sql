-- ==============================================================================================
-- V38 — the money that is ours and is not with us
--
-- A CPWD contract is funded twice. Once for the work, which the running account bills pay for,
-- and once for the department's confidence, which the contractor pays for out of his own
-- working capital and gets back years later:
--
--   EMD    2.5% of the tender value, lodged as an FDR before the bid is even opened. On a win
--          it comes back once the allotment letter is issued; on a loss it comes back sooner.
--          Either way it is the same fixed deposit, and the contractor's next tender is funded
--          by releasing it — which is why `redeployed_to_project_id` exists and why "what can
--          I re-use" is a question this table has to be able to answer.
--   PG     5% of the estimated cost put to tender *or* the contract amount, whichever is
--          higher. Lodged after the allotment letter, released a year after completion on a
--          construction contract and six months after the department's completion letter on a
--          maintenance one.
--   APG    the additional guarantee a low bid triggers. V33 already keeps the *rule* off the
--          notice; this keeps the amount actually lodged against it.
--   SD     2.5% withheld from every running account bill, released after the defect liability
--          period.
--
-- Fifteen lakh of a one-crore contract, on a deep bid, sitting in a bank for two years. The
-- office tracked it in a notebook and found out an FDR had matured unreleased when it needed
-- the money for the next tender.
--
-- ---------------------------------------------------------- the notice proposes, the register records
-- Every figure above can be computed: V11 keeps the EMD amount and the PG and SD percentages,
-- V33 keeps the APG rule, V32 keeps the bid. None of it is copied here. The service reads the
-- notice through `NitLookup` and *proposes* an amount, exactly as `expense_categories
-- .default_allocation` proposes an allocation — and what this table stores is what was actually
-- lodged, which is a different fact. An FDR is bought for a round figure, a bank rounds its own
-- way, and a project created by hand has no notice to read at all. A register that recomputed
-- its rows would report the amount the rule says rather than the amount in the bank.
--
-- ---------------------------------------------------------- a lodged instrument is not a retention
-- `amount` is the whole liability and `held_amount` is what is actually with them today, and
-- the two columns are not redundancy. An FDR is bought once for its whole value, so held equals
-- amount from the day it is lodged. A security deposit arrives 2.5% at a time as each bill is
-- passed, so on any given day part of it exists and part of it does not. One column cannot hold
-- both without lying about one of them — and the company total has to print its two halves
-- beside it for the same reason `DprResponse.menOnSite` does: money we placed came out of our
-- pocket, money withheld never reached it, and adding them without saying which is which
-- reports a bank balance we never had.
-- ==============================================================================================

-- ---------------------------------------------------------------- the contract's own calendar
-- Dates, not durations. Every release date below hangs off one of these, and each one is a
-- letter somebody holds in his hand — which is why the administrator types them as they arrive
-- rather than the system inferring them from a programme that slipped.
ALTER TABLE projects
    -- The estimated cost put to tender. PG is five per cent of this *or* of the contract,
    -- whichever is higher, so bidding low does not shrink the guarantee — and the contract
    -- value alone therefore cannot answer what the guarantee is. Nullable: the notice carries
    -- it when one was read, and it is derivable from the contract and the bid when it was not.
    ADD COLUMN estimated_cost           numeric(18,2),

    -- Which release rule the guarantee follows. A construction contract's PG is released a year
    -- after completion; a maintenance contract's, six months after the department's letter.
    -- Null means nobody has said, and the register then proposes no release date rather than
    -- guessing at one — a guessed release date is worse than a blank, because the office stops
    -- chasing the FDR that a wrong date says is not due yet.
    ADD COLUMN work_nature              varchar(20),

    -- When the bids were opened. The allotment letter is due within ten days of it, which makes
    -- this the first date on which the EMD becomes a question.
    ADD COLUMN bid_opening_date         date,

    -- The letter that turns a bid into a contract. It releases the EMD and starts the PG clock.
    ADD COLUMN allotment_letter_date    date,

    -- The department's completion letter, which is not the day the work finished. Kept apart
    -- from actual_completion_date on purpose: the contractor knows when he stopped, and only
    -- the department's letter starts the clock the guarantee is released against. Months have
    -- gone by between the two.
    ADD COLUMN completion_certificate_date date,

    -- How long the security deposit is held after completion. Varies with the item and the
    -- work, which is why it is a column and not a constant.
    ADD COLUMN defect_liability_months  int,

    ADD CONSTRAINT ck_projects_estimated_cost
        CHECK (estimated_cost IS NULL OR estimated_cost >= 0),
    ADD CONSTRAINT ck_projects_work_nature
        CHECK (work_nature IS NULL OR work_nature IN ('CONSTRUCTION', 'MAINTENANCE')),
    ADD CONSTRAINT ck_projects_defect_liability
        CHECK (defect_liability_months IS NULL
               OR (defect_liability_months >= 0 AND defect_liability_months <= 120));

COMMENT ON COLUMN projects.estimated_cost IS
    'Estimated cost put to tender. The performance guarantee is five per cent of this or of '
    'the contract amount, whichever is higher, so a low bid does not shrink it.';
COMMENT ON COLUMN projects.completion_certificate_date IS
    'The department''s completion letter — not the day work stopped. The guarantee release '
    'clock runs from this one.';

-- ---------------------------------------------------------------- the register
CREATE TABLE project_securities (
    id                        uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id                    uuid NOT NULL REFERENCES organisations(id),
    project_id                uuid NOT NULL REFERENCES projects(id),

    security_type             varchar(24) NOT NULL,
    instrument                varchar(20) NOT NULL,
    status                    varchar(20) NOT NULL DEFAULT 'DUE',

    -- The whole liability, and what is actually with them today. See the header: an FDR fills
    -- both on the day it is lodged, a retention fills the second one bill by bill.
    amount                    numeric(18,2) NOT NULL,
    held_amount               numeric(18,2) NOT NULL DEFAULT 0,

    -- How the amount was arrived at, in words, written by whoever recorded the row. The rule
    -- that proposed it is reproducible; the reason a bank issued an FDR for a rounder figure
    -- is not, and six months on it is the only thing that explains the difference.
    basis                     varchar(500),

    reference_no              varchar(80),
    bank_name                 varchar(160),
    branch                    varchar(160),

    lodged_on                 date,
    -- The deposit's own maturity, which is not the date the department releases it. An FDR that
    -- matures while the guarantee still has eight months to run has to be renewed, and the
    -- office finding that out from the bank is how a guarantee lapses.
    maturity_on               date,
    -- Proposed from the contract's calendar, then owned by the administrator. Once he has
    -- touched it the proposal never overwrites it again: the department's own timetable beats
    -- any rule this system holds.
    expected_release_on       date,

    released_on               date,
    release_reference         varchar(120),
    -- Where a released EMD went next. The whole reason the field is worth having: a contractor
    -- bidding four tenders a quarter funds each one by releasing the last, and "which deposit
    -- is free" is the question the treasury screen exists to answer.
    redeployed_to_project_id  uuid REFERENCES projects(id),

    forfeited_reason          varchar(500),
    notes                     text,

    created_at                timestamptz NOT NULL DEFAULT now(),
    updated_at                timestamptz NOT NULL DEFAULT now(),
    created_by                uuid,
    updated_by                uuid,
    version                   bigint NOT NULL DEFAULT 0,

    CONSTRAINT ck_security_type CHECK (security_type IN
        ('EMD', 'PERFORMANCE_GUARANTEE', 'ADDITIONAL_PG', 'SECURITY_DEPOSIT')),
    CONSTRAINT ck_security_instrument CHECK (instrument IN
        ('FDR', 'BANK_GUARANTEE', 'DD', 'CASH', 'BILL_RETENTION')),
    CONSTRAINT ck_security_status CHECK (status IN
        ('DUE', 'LODGED', 'RELEASED', 'FORFEITED')),

    CONSTRAINT ck_security_amounts CHECK (
        amount >= 0 AND held_amount >= 0 AND held_amount <= amount),

    -- An amount withheld from a bill is a security deposit and nothing else. Earnest money is
    -- never deducted from a payment — it is placed before there is a payment to deduct from —
    -- and a row claiming otherwise would put money in the "we placed it" half of the company
    -- total that was never ours to place.
    CONSTRAINT ck_security_retention_is_deposit CHECK (
        instrument <> 'BILL_RETENTION' OR security_type = 'SECURITY_DEPOSIT'),

    -- Each status is the whole of itself or it is not that status. These are the writes that
    -- never go through the service.
    CONSTRAINT ck_security_lodged_is_whole CHECK (
        status <> 'LODGED' OR lodged_on IS NOT NULL),
    CONSTRAINT ck_security_released_is_whole CHECK (
        status <> 'RELEASED' OR (released_on IS NOT NULL AND held_amount = 0)),
    CONSTRAINT ck_security_forfeited_is_whole CHECK (
        status <> 'FORFEITED' OR (forfeited_reason IS NOT NULL AND held_amount = 0)),

    -- Money cannot be redeployed before it comes back.
    CONSTRAINT ck_security_redeploy_after_release CHECK (
        redeployed_to_project_id IS NULL OR status = 'RELEASED')
);

-- A project has one retention pot, not several. The guarantee may be split across three FDRs
-- because three banks issued them; the 2.5% withheld from the bills is one running total, and
-- two rows for it are two answers to how much the department is holding.
CREATE UNIQUE INDEX uq_security_one_retention_per_project
    ON project_securities (project_id)
    WHERE security_type = 'SECURITY_DEPOSIT';

CREATE INDEX ix_security_project ON project_securities (project_id, security_type);

-- The release calendar: what unlocks when, across every project the company runs. This is the
-- one query the treasury dashboard is built on, so it gets its own partial index rather than
-- scanning a register that grows a row per contract per instrument forever.
CREATE INDEX ix_security_release_calendar
    ON project_securities (org_id, expected_release_on)
    WHERE status = 'LODGED';

-- "What is free to re-use." A released deposit with nowhere named is money the office can spend
-- on the next tender and frequently does not know it has.
CREATE INDEX ix_security_redeployable
    ON project_securities (org_id, released_on DESC)
    WHERE status = 'RELEASED' AND redeployed_to_project_id IS NULL;

-- ---------------------------------------------------------------- permissions
-- Two, and the split is the same one V36 drew across an expense. Reading the register is the
-- accountant's daily work — he is the man who chases a matured FDR and reconciles what the
-- department is holding. Deciding that a deposit has been lodged, released or forfeited is a
-- statement about the company's money to a department, and that is the administrator's.
INSERT INTO permissions (code, module, description) VALUES
    ('security:read',  'project', 'View the deposits and guarantees lodged against contracts'),
    ('security:write', 'project', 'Record, amend, lodge, release or forfeit a deposit');

-- V2 granted ADMIN everything by CROSS JOIN over the catalogue as it stood then; anything
-- added later has to be granted explicitly.
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
  FROM roles r
  JOIN permissions p ON p.code IN ('security:read', 'security:write')
 WHERE r.code = 'ADMIN' AND r.is_system
   AND NOT EXISTS (SELECT 1 FROM role_permissions rp
                    WHERE rp.role_id = r.id AND rp.permission_id = p.id);

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
  FROM roles r
  JOIN permissions p ON p.code = 'security:read'
 WHERE r.code = 'ACCOUNTANT' AND r.is_system
   AND NOT EXISTS (SELECT 1 FROM role_permissions rp
                    WHERE rp.role_id = r.id AND rp.permission_id = p.id);
