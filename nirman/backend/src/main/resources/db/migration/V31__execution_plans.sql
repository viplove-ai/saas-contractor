-- ==============================================================================================
-- V31 — the plan, frozen
--
-- A plan looks like a derived figure and is not one. docs/09 says rolled-up figures are computed
-- per call because a cached total is a second version of the truth — but a plan is not a total of
-- anything that happened. It is a claim about a future that has not happened yet, and its whole
-- value is that it stops changing so reality can be measured against it. A plan that silently
-- recomputed itself would show every project on target forever, because the target would follow
-- the work.
--
-- So it freezes, exactly as attendance freezes its wage rate at verification and a DPR freezes
-- its figures when sent. Variance — plan against ledger — stays derived like everything else.
--
-- Three things this data may never do, and each is a bug even if it passes review:
--   * a planned quantity never reaches the measurement book
--   * a planned material requirement is never a stock transaction
--   * a planned cost is never an expense, payable, approvable or bookable
--
-- project_id is NULLABLE on purpose. A pre-award plan belongs to no project: deciding whether to
-- bid, and at what percentage, is the whole exercise, and it happens before there is anything to
-- attach to. Winning the tender attaches it, and nothing is recomputed on the way — what was
-- decided is what is kept.
--
-- See docs/10-planning-and-execution-strategy.md §1 and §8.3.
-- ==============================================================================================

CREATE TABLE execution_plans (
    id                      uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id                  uuid NOT NULL REFERENCES organisations(id),
    project_id              uuid REFERENCES projects(id),      -- null = a pre-award bid case
    nit_document_id         uuid REFERENCES nit_documents(id),
    work_type_profile_id    uuid REFERENCES work_type_profiles(id),

    name                    varchar(200) NOT NULL,
    source                  varchar(20)  NOT NULL,             -- PROJECT | NIT_UPLOAD
    -- A named variation on the same input: the bid screen's whole purpose is moving one number
    -- and watching the funding peak move with it.
    scenario                varchar(60)  NOT NULL DEFAULT 'BASE',

    -- The inputs, kept beside the outputs. Six months on the question is not what the norms say,
    -- it is what they said when this plan was made.
    commencement_date       date         NOT NULL,
    allowed_days            int          NOT NULL,
    quoted_percent          numeric(7,3) NOT NULL DEFAULT 0,
    contract_value          numeric(18,2),
    payment_lag_days        int          NOT NULL DEFAULT 45,

    -- The headline: the deepest point of the cumulative trough, and when it happens. Not the
    -- first month's cost — money keeps going out through the whole payment lag.
    peak_funding_required   numeric(18,2),
    peak_month              varchar(7),
    money_before_day_one    numeric(18,2),
    break_even_month        varchar(7),
    total_retention_held    numeric(18,2),
    retention_released_on   date,
    total_outflow           numeric(18,2),
    total_net_receipts      numeric(18,2),

    -- A draft may be replaced freely. Baselining is the irreversible act and the audited one.
    baselined_at            timestamptz,
    baselined_by            uuid REFERENCES users(id),
    revision                int NOT NULL DEFAULT 1,
    superseded_at           timestamptz,

    engine_version          varchar(20) NOT NULL,
    deleted_at              timestamptz,
    created_at              timestamptz NOT NULL DEFAULT now(),
    updated_at              timestamptz NOT NULL DEFAULT now(),
    created_by              uuid,
    updated_by              uuid,
    version                 bigint NOT NULL DEFAULT 0,

    CONSTRAINT ck_plan_source CHECK (source IN ('PROJECT', 'NIT_UPLOAD')),
    CONSTRAINT ck_plan_days CHECK (allowed_days > 0)
);
-- One live baseline per project. A re-plan supersedes rather than stacks, so "the plan this
-- project runs under" always has exactly one answer — the rule V11 applies to the tender itself.
CREATE UNIQUE INDEX uq_plan_project_baseline ON execution_plans (project_id)
    WHERE project_id IS NOT NULL AND baselined_at IS NOT NULL
      AND superseded_at IS NULL AND deleted_at IS NULL;
CREATE INDEX ix_plan_org ON execution_plans (org_id) WHERE deleted_at IS NULL;

-- ---------------------------------------------------------------- phases
-- The tender's own milestones, with what the programme actually reaches by each date. The
-- description is kept verbatim: where a milestone is physical it names the activities the
-- department expects finished, and that text is what a submission prints.
CREATE TABLE plan_phases (
    id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    plan_id          uuid NOT NULL REFERENCES execution_plans(id),
    sequence_no      int  NOT NULL,
    description      text,
    start_date       date NOT NULL,
    end_date         date NOT NULL,
    target_percent   numeric(6,3),
    planned_value    numeric(18,2),
    planned_percent  numeric(6,3),
    withheld_percent numeric(6,3),
    physical         boolean NOT NULL DEFAULT false,
    on_target        boolean NOT NULL DEFAULT true,
    CONSTRAINT uq_plan_phase UNIQUE (plan_id, sequence_no)
);

-- ---------------------------------------------------------------- work packages
CREATE TABLE plan_work_packages (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    plan_id       uuid NOT NULL REFERENCES execution_plans(id),
    work_category varchar(80) NOT NULL,
    work_part     varchar(40),
    value         numeric(18,2) NOT NULL DEFAULT 0,
    start_date    date NOT NULL,
    end_date      date NOT NULL,
    gangs         int  NOT NULL DEFAULT 1,
    line_count    int  NOT NULL DEFAULT 0,
    -- False where no productivity norm matched: the block carries money but no men or dates
    -- anybody should act on, and saying so is better than a confident zero.
    normed        boolean NOT NULL DEFAULT true
);
CREATE INDEX ix_plan_packages ON plan_work_packages (plan_id);

-- ---------------------------------------------------------------- labour
CREATE TABLE plan_labour_demand (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    plan_id     uuid NOT NULL REFERENCES execution_plans(id),
    year_month  varchar(7) NOT NULL,
    skill_code  varchar(40) NOT NULL,
    skilled     boolean NOT NULL DEFAULT true,
    man_days    numeric(18,3) NOT NULL DEFAULT 0,
    -- Man-days over the month's working days, which is what a supervisor can act on.
    head_count  numeric(10,1) NOT NULL DEFAULT 0,
    cost        numeric(18,2),
    CONSTRAINT uq_plan_labour UNIQUE (plan_id, year_month, skill_code)
);

-- ---------------------------------------------------------------- material
-- Two quantities because they answer two questions. required_qty is what a month consumes;
-- procure_qty is that same material moved back by its lead time, which is what has to be ordered
-- for the month to happen at all. Conflating them is how a site runs out of cement while the
-- plan says it has plenty.
CREATE TABLE plan_material_demand (
    id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    plan_id        uuid NOT NULL REFERENCES execution_plans(id),
    year_month     varchar(7) NOT NULL,
    material_code  varchar(40) NOT NULL,
    material_name  varchar(200),
    unit_code      varchar(20),
    required_qty   numeric(18,3) NOT NULL DEFAULT 0,
    procure_qty    numeric(18,3) NOT NULL DEFAULT 0,
    procure_value  numeric(18,2),
    order_by_date  date,
    CONSTRAINT uq_plan_material UNIQUE (plan_id, year_month, material_code)
);

-- ---------------------------------------------------------------- cash
CREATE TABLE plan_cash_flow (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    plan_id             uuid NOT NULL REFERENCES execution_plans(id),
    year_month          varchar(7) NOT NULL,
    labour_cost         numeric(18,2) NOT NULL DEFAULT 0,
    material_cost       numeric(18,2) NOT NULL DEFAULT 0,
    staff_cost          numeric(18,2) NOT NULL DEFAULT 0,
    plant_transport     numeric(18,2) NOT NULL DEFAULT 0,
    setup_cost          numeric(18,2) NOT NULL DEFAULT 0,
    overhead_cost       numeric(18,2) NOT NULL DEFAULT 0,
    total_outflow       numeric(18,2) NOT NULL DEFAULT 0,
    gross_billed        numeric(18,2) NOT NULL DEFAULT 0,
    deductions          numeric(18,2) NOT NULL DEFAULT 0,
    -- What actually arrives, and what funds the next phase. The gap between this and
    -- gross_billed is where optimistic plans die.
    net_received        numeric(18,2) NOT NULL DEFAULT 0,
    net_movement        numeric(18,2) NOT NULL DEFAULT 0,
    cumulative          numeric(18,2) NOT NULL DEFAULT 0,
    CONSTRAINT uq_plan_cash UNIQUE (plan_id, year_month)
);

-- ---------------------------------------------------------------- assumptions and findings
-- Not documentation. This is what makes a plan auditable six months later when the question is
-- "why did it say we needed forty lakh", and what lets a re-plan on better norms be compared
-- with the old one rather than merely replacing it.
CREATE TABLE plan_assumptions (
    id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    plan_id    uuid NOT NULL REFERENCES execution_plans(id),
    kind       varchar(20) NOT NULL,            -- ASSUMPTION | FINDING
    severity   varchar(20),                     -- BLOCKING | WARNING | NOTE, for a finding
    subject    varchar(200),
    value      varchar(200),
    message    text NOT NULL,
    sort_order int NOT NULL DEFAULT 0,
    CONSTRAINT ck_plan_note_kind CHECK (kind IN ('ASSUMPTION', 'FINDING')),
    CONSTRAINT ck_plan_note_severity
        CHECK (severity IS NULL OR severity IN ('BLOCKING', 'WARNING', 'NOTE'))
);
CREATE INDEX ix_plan_assumptions ON plan_assumptions (plan_id, sort_order);

-- ==============================================================================================
-- Permissions. The engine now exists to honour them.
-- ==============================================================================================
INSERT INTO permissions (code, module, description) VALUES
    ('planning:generate', 'planning', 'Generate an execution plan and try scenarios'),
    ('planning:baseline', 'planning', 'Freeze a plan as the baseline the project is measured on');

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
  FROM roles r
  JOIN permissions p ON p.code IN ('planning:generate', 'planning:baseline')
 WHERE r.code = 'ADMIN' AND r.is_system
   AND NOT EXISTS (SELECT 1 FROM role_permissions rp
                    WHERE rp.role_id = r.id AND rp.permission_id = p.id);

-- The engineer plans the work and the accountant plans the money, so both may generate. Neither
-- baselines: freezing the figures the project is judged against is the contractor's own act.
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
  FROM roles r
  JOIN permissions p ON p.code = 'planning:generate'
 WHERE r.code IN ('ENGINEER', 'ACCOUNTANT') AND r.is_system
   AND NOT EXISTS (SELECT 1 FROM role_permissions rp
                    WHERE rp.role_id = r.id AND rp.permission_id = p.id);
