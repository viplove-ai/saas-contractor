-- Nirman — initial schema.
--
-- One baseline migration. Flyway runs it in a single transaction, so the schema either
-- lands whole or not at all; there is no half-applied state to clean up.
--
-- Conventions used throughout:
--   uuid primary keys; transaction tables take a client-generated id so an offline device
--   can re-send safely. org_id on every business table. created_at/updated_at timestamptz,
--   created_by/updated_by uuid, version bigint for optimistic locking. numeric(18,2) money,
--   numeric(18,4) quantities and rates — no floating point anywhere. Soft delete on master
--   data only; transactions are cancelled or voided, never deleted.
--
-- Sections below follow the module boundaries in docs/01-architecture.md. Foreign keys that
-- cross a circular dependency are collected at the very end under "deferred foreign keys".
--
-- Once this has been applied anywhere, it is never edited again: a correction is V2.



-- ==============================================================================================
-- IDENTITY, ORGANISATION, PROJECTS AND CROSS-CUTTING INFRASTRUCTURE
-- ==============================================================================================

CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- ---------------------------------------------------------------- organisation
CREATE TABLE organisations (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    name            varchar(200) NOT NULL,
    code            varchar(30)  NOT NULL UNIQUE,
    gstin           varchar(15),
    pan             varchar(10),
    address         text,
    contact_email   varchar(150),
    contact_phone   varchar(20),
    currency_code   char(3)      NOT NULL DEFAULT 'INR',
    timezone        varchar(50)  NOT NULL DEFAULT 'Asia/Kolkata',
    is_active       boolean      NOT NULL DEFAULT true,
    created_at      timestamptz  NOT NULL DEFAULT now(),
    updated_at      timestamptz  NOT NULL DEFAULT now(),
    created_by      uuid,
    updated_by      uuid,
    version         bigint       NOT NULL DEFAULT 0
);

-- ---------------------------------------------------------------- identity
CREATE TABLE users (
    id                    uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id                uuid NOT NULL REFERENCES organisations(id),
    username              varchar(60)  NOT NULL,
    email                 varchar(150),
    mobile                varchar(20),
    full_name             varchar(150) NOT NULL,
    password_hash         varchar(120) NOT NULL,
    must_change_password  boolean      NOT NULL DEFAULT false,
    is_active             boolean      NOT NULL DEFAULT true,
    failed_login_count    int          NOT NULL DEFAULT 0,
    locked_until          timestamptz,
    last_login_at         timestamptz,
    created_at            timestamptz  NOT NULL DEFAULT now(),
    updated_at            timestamptz  NOT NULL DEFAULT now(),
    created_by            uuid,
    updated_by            uuid,
    version               bigint       NOT NULL DEFAULT 0,
    CONSTRAINT uq_users_org_username UNIQUE (org_id, username)
);
CREATE UNIQUE INDEX uq_users_org_email ON users (org_id, lower(email)) WHERE email IS NOT NULL;

CREATE TABLE roles (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id      uuid REFERENCES organisations(id),   -- null = system role available to all orgs
    code        varchar(40)  NOT NULL,
    name        varchar(100) NOT NULL,
    description text,
    is_system   boolean      NOT NULL DEFAULT false,
    created_at  timestamptz  NOT NULL DEFAULT now(),
    updated_at  timestamptz  NOT NULL DEFAULT now(),
    version     bigint       NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX uq_roles_code ON roles (COALESCE(org_id, '00000000-0000-0000-0000-000000000000'::uuid), code);

CREATE TABLE permissions (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    code        varchar(60) NOT NULL UNIQUE,   -- e.g. attendance:verify
    module      varchar(40) NOT NULL,
    description text
);

CREATE TABLE role_permissions (
    role_id       uuid NOT NULL REFERENCES roles(id) ON DELETE CASCADE,
    permission_id uuid NOT NULL REFERENCES permissions(id) ON DELETE CASCADE,
    PRIMARY KEY (role_id, permission_id)
);

CREATE TABLE user_roles (
    user_id     uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role_id     uuid NOT NULL REFERENCES roles(id),
    assigned_at timestamptz NOT NULL DEFAULT now(),
    assigned_by uuid,
    PRIMARY KEY (user_id, role_id)
);

CREATE TABLE refresh_tokens (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id      uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash   char(64) NOT NULL UNIQUE,          -- sha-256 hex, raw token never stored
    family_id    uuid NOT NULL,                     -- rotation family; reuse revokes the family
    issued_at    timestamptz NOT NULL DEFAULT now(),
    expires_at   timestamptz NOT NULL,
    revoked_at   timestamptz,
    revoked_reason varchar(60),
    user_agent   varchar(300),
    ip_address   inet
);
CREATE INDEX ix_refresh_tokens_user ON refresh_tokens (user_id, revoked_at);

-- ---------------------------------------------------------------- projects and sites
CREATE TABLE projects (
    id                       uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id                   uuid NOT NULL REFERENCES organisations(id),
    code                     varchar(40)  NOT NULL,
    name                     varchar(200) NOT NULL,
    client_department        varchar(200),
    agreement_no             varchar(80),
    -- The tender this project was won from. The tender module itself arrives with the
    -- tender-intelligence merge; these carry the reference until then, so a project can be
    -- traced back to the NIT its BOQ and estimates came from.
    nit_number               varchar(80),
    tender_reference         varchar(120),
    contract_value           numeric(18,2),
    budget_amount            numeric(18,2),
    start_date               date,
    expected_completion_date date,
    actual_completion_date   date,
    project_manager_id       uuid REFERENCES users(id),
    status                   varchar(20) NOT NULL DEFAULT 'ACTIVE',
    description              text,
    deleted_at               timestamptz,
    created_at               timestamptz NOT NULL DEFAULT now(),
    updated_at               timestamptz NOT NULL DEFAULT now(),
    created_by               uuid,
    updated_by               uuid,
    version                  bigint NOT NULL DEFAULT 0,
    CONSTRAINT uq_projects_org_code UNIQUE (org_id, code),
    CONSTRAINT ck_projects_status CHECK (status IN ('PLANNED','ACTIVE','ON_HOLD','COMPLETED','CLOSED')),
    CONSTRAINT ck_projects_dates CHECK (expected_completion_date IS NULL OR start_date IS NULL
                                        OR expected_completion_date >= start_date)
);

CREATE TABLE sites (
    id                    uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id                uuid NOT NULL REFERENCES organisations(id),
    project_id            uuid NOT NULL REFERENCES projects(id),
    code                  varchar(40)  NOT NULL,
    name                  varchar(200) NOT NULL,
    address               text,
    latitude              numeric(9,6),
    longitude             numeric(9,6),
    site_engineer_id      uuid REFERENCES users(id),
    supervisor_id         uuid REFERENCES users(id),
    status                varchar(20) NOT NULL DEFAULT 'ACTIVE',
    start_date            date,
    standard_shift_hours  numeric(4,2) NOT NULL DEFAULT 8.00,
    monthly_wage_days     int          NOT NULL DEFAULT 26,
    deleted_at            timestamptz,
    created_at            timestamptz NOT NULL DEFAULT now(),
    updated_at            timestamptz NOT NULL DEFAULT now(),
    created_by            uuid,
    updated_by            uuid,
    version               bigint NOT NULL DEFAULT 0,
    CONSTRAINT uq_sites_org_code UNIQUE (org_id, code),
    CONSTRAINT ck_sites_status CHECK (status IN ('PLANNED','ACTIVE','SUSPENDED','CLOSED')),
    CONSTRAINT ck_sites_shift CHECK (standard_shift_hours > 0 AND standard_shift_hours <= 24),
    -- Redundant against the primary key, but it is what lets every transaction table declare
    -- a composite (site_id, project_id) foreign key. Without it nothing stops a row naming a
    -- site that belongs to a different project, which is how a service-layer bug turns into
    -- cross-project leakage once multi-tenant mode is switched on.
    CONSTRAINT uq_sites_id_project UNIQUE (id, project_id)
);
CREATE INDEX ix_sites_project ON sites (project_id) WHERE deleted_at IS NULL;

CREATE TABLE user_site_assignments (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id      uuid NOT NULL REFERENCES organisations(id),
    user_id     uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    site_id     uuid NOT NULL REFERENCES sites(id),
    assigned_from date NOT NULL DEFAULT CURRENT_DATE,
    assigned_to   date,
    is_primary  boolean NOT NULL DEFAULT false,
    created_at  timestamptz NOT NULL DEFAULT now(),
    created_by  uuid,
    CONSTRAINT uq_user_site UNIQUE (user_id, site_id)
);
CREATE INDEX ix_usa_site ON user_site_assignments (site_id);

-- Stores live inside a site. Every stock movement is against a store, never a bare site.
CREATE TABLE stores (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id      uuid NOT NULL REFERENCES organisations(id),
    site_id     uuid NOT NULL REFERENCES sites(id),
    code        varchar(40)  NOT NULL,
    name        varchar(150) NOT NULL,
    location    varchar(200),
    is_default  boolean NOT NULL DEFAULT true,
    is_active   boolean NOT NULL DEFAULT true,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),
    created_by  uuid,
    updated_by  uuid,
    version     bigint NOT NULL DEFAULT 0,
    CONSTRAINT uq_stores_org_code UNIQUE (org_id, code)
);
CREATE INDEX ix_stores_site ON stores (site_id);

-- ---------------------------------------------------------------- period locking
CREATE TABLE period_locks (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id      uuid NOT NULL REFERENCES organisations(id),
    site_id     uuid NOT NULL REFERENCES sites(id),
    module      varchar(20) NOT NULL,      -- ATTENDANCE | INVENTORY | EXPENSE | ALL
    year_month  char(7)     NOT NULL,      -- YYYY-MM
    locked_at   timestamptz NOT NULL DEFAULT now(),
    locked_by   uuid NOT NULL REFERENCES users(id),
    unlocked_at timestamptz,
    unlocked_by uuid REFERENCES users(id),
    unlock_reason text,
    CONSTRAINT uq_period_lock UNIQUE (site_id, module, year_month)
);

-- ---------------------------------------------------------------- approvals
CREATE TABLE approval_rules (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id        uuid NOT NULL REFERENCES organisations(id),
    entity_type   varchar(40) NOT NULL,     -- EXPENSE | ATTENDANCE_CORRECTION | STOCK_ADJUSTMENT | DPR | ADVANCE_SETTLEMENT
    level         int         NOT NULL,
    role_code     varchar(40) NOT NULL,
    min_amount    numeric(18,2),
    max_amount    numeric(18,2),
    is_active     boolean NOT NULL DEFAULT true,
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now(),
    version       bigint NOT NULL DEFAULT 0,
    CONSTRAINT uq_approval_rule UNIQUE (org_id, entity_type, level, role_code),
    CONSTRAINT ck_approval_rule_amounts CHECK (min_amount IS NULL OR max_amount IS NULL OR max_amount >= min_amount)
);

CREATE TABLE approvals (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id          uuid NOT NULL REFERENCES organisations(id),
    entity_type     varchar(40) NOT NULL,
    entity_id       uuid        NOT NULL,
    site_id         uuid REFERENCES sites(id),
    level           int         NOT NULL,
    assigned_role   varchar(40) NOT NULL,
    assigned_user_id uuid REFERENCES users(id),
    status          varchar(20) NOT NULL DEFAULT 'PENDING',
    action_by       uuid REFERENCES users(id),
    action_at       timestamptz,
    remarks         text,
    previous_status varchar(30),
    next_status     varchar(30),
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    version         bigint NOT NULL DEFAULT 0,
    CONSTRAINT ck_approval_status CHECK (status IN ('PENDING','APPROVED','REJECTED','RETURNED','CANCELLED','SKIPPED'))
);
CREATE INDEX ix_approvals_entity  ON approvals (entity_type, entity_id, level);
CREATE INDEX ix_approvals_pending ON approvals (org_id, status, assigned_role, site_id) WHERE status = 'PENDING';

-- ---------------------------------------------------------------- attachments
CREATE TABLE attachments (
    id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id            uuid NOT NULL REFERENCES organisations(id),
    site_id           uuid REFERENCES sites(id),
    owner_entity_type varchar(40) NOT NULL,   -- EXPENSE | GOODS_RECEIPT | DPR | WORKER | ...
    owner_entity_id   uuid,                   -- null until the parent record is saved
    file_name         varchar(255) NOT NULL,
    content_type      varchar(120) NOT NULL,
    size_bytes        bigint       NOT NULL,
    checksum_sha256   char(64),
    bucket            varchar(80)  NOT NULL,
    object_key        varchar(400) NOT NULL UNIQUE,
    kind              varchar(30)  NOT NULL DEFAULT 'DOCUMENT',  -- BILL | CHALLAN | PHOTO | DOCUMENT
    uploaded_at       timestamptz NOT NULL DEFAULT now(),
    uploaded_by       uuid REFERENCES users(id),
    deleted_at        timestamptz,
    CONSTRAINT ck_attachment_size CHECK (size_bytes > 0)
);
CREATE INDEX ix_attachments_owner ON attachments (owner_entity_type, owner_entity_id) WHERE deleted_at IS NULL;

-- ---------------------------------------------------------------- audit, notifications, imports, idempotency
CREATE TABLE audit_logs (
    id           bigserial PRIMARY KEY,
    org_id       uuid,
    user_id      uuid,
    username     varchar(60),
    occurred_at  timestamptz NOT NULL DEFAULT now(),
    entity_type  varchar(60) NOT NULL,
    entity_id    uuid,
    action       varchar(40) NOT NULL,     -- CREATE | UPDATE | DELETE | APPROVE | REJECT | LOGIN | LOCK | ...
    old_values   jsonb,
    new_values   jsonb,
    reason       text,
    ip_address   inet,
    device_info  varchar(300),
    correlation_id varchar(60)
);
CREATE INDEX ix_audit_entity   ON audit_logs (entity_type, entity_id, occurred_at DESC);
CREATE INDEX ix_audit_user     ON audit_logs (user_id, occurred_at DESC);
CREATE INDEX ix_audit_occurred ON audit_logs (occurred_at DESC);

CREATE TABLE notifications (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id       uuid NOT NULL REFERENCES organisations(id),
    user_id      uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    title        varchar(200) NOT NULL,
    body         text,
    entity_type  varchar(40),
    entity_id    uuid,
    severity     varchar(20) NOT NULL DEFAULT 'INFO',
    read_at      timestamptz,
    created_at   timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_notifications_user ON notifications (user_id, read_at, created_at DESC);

CREATE TABLE import_batches (
    id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id         uuid NOT NULL REFERENCES organisations(id),
    entity_type    varchar(40) NOT NULL,
    file_name      varchar(255) NOT NULL,
    attachment_id  uuid REFERENCES attachments(id),
    status         varchar(20) NOT NULL DEFAULT 'PENDING',  -- PENDING|VALIDATING|PARTIAL|COMPLETED|FAILED
    dry_run        boolean NOT NULL DEFAULT false,
    total_rows     int NOT NULL DEFAULT 0,
    success_rows   int NOT NULL DEFAULT 0,
    failed_rows    int NOT NULL DEFAULT 0,
    error_file_key varchar(400),
    started_at     timestamptz,
    finished_at    timestamptz,
    created_at     timestamptz NOT NULL DEFAULT now(),
    created_by     uuid REFERENCES users(id)
);

CREATE TABLE import_batch_rows (
    id            bigserial PRIMARY KEY,
    batch_id      uuid NOT NULL REFERENCES import_batches(id) ON DELETE CASCADE,
    row_number    int  NOT NULL,
    raw_data      jsonb NOT NULL,
    status        varchar(20) NOT NULL,   -- OK | ERROR
    error_message text,
    created_entity_id uuid
);
CREATE INDEX ix_import_rows_batch ON import_batch_rows (batch_id, status);

-- Replay protection for offline sync and any idempotent POST.
CREATE TABLE idempotency_records (
    id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id         uuid NOT NULL,
    user_id        uuid NOT NULL,
    endpoint       varchar(120) NOT NULL,
    idempotency_key varchar(80) NOT NULL,
    request_hash   char(64) NOT NULL,
    response_status int NOT NULL,
    response_body  jsonb,
    created_at     timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_idempotency UNIQUE (user_id, endpoint, idempotency_key)
);
CREATE INDEX ix_idempotency_created ON idempotency_records (created_at);


-- ==============================================================================================
-- LABOUR — workers, wages, attendance, and wage settlement
-- ==============================================================================================

CREATE TABLE labour_contractors (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id        uuid NOT NULL REFERENCES organisations(id),
    code          varchar(40)  NOT NULL,
    name          varchar(200) NOT NULL,
    contact_person varchar(150),
    mobile        varchar(20),
    email         varchar(150),
    address       text,
    gstin         varchar(15),
    pan           varchar(10),
    bank_account_no varchar(30),
    bank_ifsc     varchar(15),
    is_active     boolean NOT NULL DEFAULT true,
    deleted_at    timestamptz,
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now(),
    created_by    uuid,
    updated_by    uuid,
    version       bigint NOT NULL DEFAULT 0,
    CONSTRAINT uq_contractor_org_code UNIQUE (org_id, code)
);

CREATE TABLE skill_categories (
    id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id     uuid NOT NULL REFERENCES organisations(id),
    code       varchar(40) NOT NULL,       -- MASON | HELPER | CARPENTER | BAR_BENDER | ELECTRICIAN ...
    name       varchar(100) NOT NULL,
    is_skilled boolean NOT NULL DEFAULT true,
    is_active  boolean NOT NULL DEFAULT true,
    CONSTRAINT uq_skill_org_code UNIQUE (org_id, code)
);

CREATE TABLE workers (
    id                 uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id             uuid NOT NULL REFERENCES organisations(id),
    worker_code        varchar(40)  NOT NULL,
    full_name          varchar(150) NOT NULL,
    mobile             varchar(20),
    photo_attachment_id uuid REFERENCES attachments(id),
    skill_category_id  uuid REFERENCES skill_categories(id),
    employment_type    varchar(20) NOT NULL DEFAULT 'CONTRACT',  -- PERMANENT | CONTRACT | CASUAL
    labour_contractor_id uuid REFERENCES labour_contractors(id),
    wage_type          varchar(10) NOT NULL DEFAULT 'DAILY',     -- DAILY | HOURLY | MONTHLY
    joining_date       date,
    exit_date          date,
    aadhaar_last4      char(4),
    bank_account_no    varchar(30),
    bank_ifsc          varchar(15),
    bank_name          varchar(120),
    is_active          boolean NOT NULL DEFAULT true,
    deleted_at         timestamptz,
    created_at         timestamptz NOT NULL DEFAULT now(),
    updated_at         timestamptz NOT NULL DEFAULT now(),
    created_by         uuid,
    updated_by         uuid,
    version            bigint NOT NULL DEFAULT 0,
    CONSTRAINT uq_worker_org_code UNIQUE (org_id, worker_code),
    CONSTRAINT ck_worker_wage_type CHECK (wage_type IN ('DAILY','HOURLY','MONTHLY')),
    CONSTRAINT ck_worker_employment CHECK (employment_type IN ('PERMANENT','CONTRACT','CASUAL'))
);
CREATE INDEX ix_workers_contractor ON workers (labour_contractor_id) WHERE deleted_at IS NULL;

-- A worker can move between sites over time. The allocation open on a date decides the roster.
CREATE TABLE worker_site_allocations (
    id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id         uuid NOT NULL REFERENCES organisations(id),
    worker_id      uuid NOT NULL REFERENCES workers(id),
    site_id        uuid NOT NULL REFERENCES sites(id),
    effective_from date NOT NULL,
    effective_to   date,
    created_at     timestamptz NOT NULL DEFAULT now(),
    created_by     uuid,
    version        bigint NOT NULL DEFAULT 0,
    CONSTRAINT ck_alloc_dates CHECK (effective_to IS NULL OR effective_to >= effective_from)
);
CREATE INDEX ix_alloc_site_date  ON worker_site_allocations (site_id, effective_from, effective_to);
-- Only one open allocation per worker at a time.
CREATE UNIQUE INDEX uq_alloc_open ON worker_site_allocations (worker_id) WHERE effective_to IS NULL;

-- Wage history. Historical attendance must never change when the current rate changes.
CREATE TABLE wage_rates (
    id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id         uuid NOT NULL REFERENCES organisations(id),
    worker_id      uuid NOT NULL REFERENCES workers(id),
    normal_rate    numeric(18,4) NOT NULL,     -- per day, hour or month depending on workers.wage_type
    overtime_rate  numeric(18,4) NOT NULL,     -- always per hour
    effective_from date NOT NULL,
    effective_to   date,
    remarks        text,
    created_at     timestamptz NOT NULL DEFAULT now(),
    updated_at     timestamptz NOT NULL DEFAULT now(),
    created_by     uuid,
    updated_by     uuid,
    version        bigint NOT NULL DEFAULT 0,
    CONSTRAINT ck_wage_positive CHECK (normal_rate >= 0 AND overtime_rate >= 0),
    CONSTRAINT ck_wage_dates CHECK (effective_to IS NULL OR effective_to >= effective_from)
);
CREATE INDEX ix_wage_worker_from ON wage_rates (worker_id, effective_from DESC);
CREATE UNIQUE INDEX uq_wage_open ON wage_rates (worker_id) WHERE effective_to IS NULL;

-- ---------------------------------------------------------------- attendance
CREATE TABLE attendance_records (
    id                  uuid PRIMARY KEY,          -- client-generated UUID, enables idempotent offline sync
    org_id              uuid NOT NULL REFERENCES organisations(id),
    project_id          uuid NOT NULL REFERENCES projects(id),
    site_id             uuid NOT NULL REFERENCES sites(id),
    worker_id           uuid NOT NULL REFERENCES workers(id),
    attendance_date     date NOT NULL,
    status              varchar(15) NOT NULL,      -- PRESENT | ABSENT | HALF_DAY | LEAVE
    check_in_time       time,
    check_out_time      time,
    break_minutes       int NOT NULL DEFAULT 0,
    worked_hours        numeric(6,2) NOT NULL DEFAULT 0,
    regular_hours       numeric(6,2) NOT NULL DEFAULT 0,
    overtime_hours      numeric(6,2) NOT NULL DEFAULT 0,
    boq_item_id         uuid,                      -- FK added in V5 once boq_items exists
    work_location       varchar(150),              -- block, floor, chainage
    overtime_reason     varchar(300),
    remarks             text,
    -- wage snapshot, frozen at verification so later rate changes never rewrite history
    wage_rate_id        uuid REFERENCES wage_rates(id),
    applied_normal_rate numeric(18,4),
    applied_ot_rate     numeric(18,4),
    computed_wage_amount numeric(18,2),
    computed_ot_amount   numeric(18,2),
    workflow_status     varchar(20) NOT NULL DEFAULT 'DRAFT',  -- DRAFT|SUBMITTED|VERIFIED|REJECTED|LOCKED|CANCELLED
    submitted_at        timestamptz,
    submitted_by        uuid REFERENCES users(id),
    verified_at         timestamptz,
    verified_by         uuid REFERENCES users(id),
    rejection_reason    text,
    locked_at           timestamptz,
    source              varchar(15) NOT NULL DEFAULT 'ONLINE',  -- ONLINE | OFFLINE_SYNC | IMPORT
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now(),
    created_by          uuid,
    updated_by          uuid,
    version             bigint NOT NULL DEFAULT 0,
    CONSTRAINT ck_att_status CHECK (status IN ('PRESENT','ABSENT','HALF_DAY','LEAVE')),
    CONSTRAINT ck_att_workflow CHECK (workflow_status IN ('DRAFT','SUBMITTED','VERIFIED','REJECTED','LOCKED','CANCELLED')),
    CONSTRAINT ck_att_hours CHECK (worked_hours >= 0 AND regular_hours >= 0 AND overtime_hours >= 0),
    CONSTRAINT ck_att_break CHECK (break_minutes >= 0 AND break_minutes < 1440),
    -- No constraint requiring overtime_reason. On a real site running a 7-hour shift every
    -- worker books overtime nearly every day (one logged 102 overtime hours in a month), so a
    -- per-record reason becomes 200 copies of the word "OT" and stops meaning anything. The
    -- rule is a configurable threshold in labour_settings, enforced in the service layer.
    CONSTRAINT fk_attendance_site_project FOREIGN KEY (site_id, project_id) REFERENCES sites (id, project_id)
);
-- One live attendance row per worker, site and date. Cancelled rows are excluded so a
-- mistaken entry can be cancelled and re-entered without deleting history.
CREATE UNIQUE INDEX uq_attendance_worker_site_date
    ON attendance_records (worker_id, site_id, attendance_date)
    WHERE workflow_status <> 'CANCELLED';
CREATE INDEX ix_attendance_site_date ON attendance_records (site_id, attendance_date);
CREATE INDEX ix_attendance_status    ON attendance_records (org_id, workflow_status, attendance_date);

CREATE TABLE attendance_corrections (
    id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id            uuid NOT NULL REFERENCES organisations(id),
    attendance_id     uuid NOT NULL REFERENCES attendance_records(id),
    field_name        varchar(40) NOT NULL,
    previous_value    varchar(200),
    new_value         varchar(200),
    correction_reason text NOT NULL,
    requested_by      uuid NOT NULL REFERENCES users(id),
    requested_at      timestamptz NOT NULL DEFAULT now(),
    approval_status   varchar(20) NOT NULL DEFAULT 'PENDING',
    approved_by       uuid REFERENCES users(id),
    approved_at       timestamptz,
    approval_remarks  text,
    version           bigint NOT NULL DEFAULT 0,
    CONSTRAINT ck_corr_status CHECK (approval_status IN ('PENDING','APPROVED','REJECTED'))
);
CREATE INDEX ix_corrections_attendance ON attendance_corrections (attendance_id);

-- ---------------------------------------------------------------- settlement
-- The field sheets compute, per worker per month: Total Amount - Advance = Balance Payment.
-- Wages are earned from verified attendance; advances are cash or goods handed over during
-- the month. The two are never added together as cost - money given to a worker settles a
-- wage already counted through attendance. See expense_categories.is_labour_payment in V4.

CREATE TABLE labour_settings (
    id                                   uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id                               uuid NOT NULL UNIQUE REFERENCES organisations(id),
    overtime_reason_required_above_hours numeric(4,2) NOT NULL DEFAULT 4.00,
    settlement_period                    varchar(12) NOT NULL DEFAULT 'MONTHLY',
    advance_auto_recover                 boolean NOT NULL DEFAULT true,
    updated_at                           timestamptz NOT NULL DEFAULT now(),
    updated_by                           uuid,
    version                              bigint NOT NULL DEFAULT 0,
    CONSTRAINT ck_labour_settlement_period CHECK (settlement_period IN ('WEEKLY','FORTNIGHTLY','MONTHLY')),
    CONSTRAINT ck_labour_ot_threshold CHECK (overtime_reason_required_above_hours >= 0)
);

-- Cash or goods given to a worker against wages. Distinct from site_advances, which issues
-- petty cash to a *user* holding a login. A worker is not a user.
CREATE TABLE worker_advances (
    id               uuid PRIMARY KEY,            -- client-generated, offline-safe
    org_id           uuid NOT NULL REFERENCES organisations(id),
    project_id       uuid NOT NULL REFERENCES projects(id),
    site_id          uuid NOT NULL REFERENCES sites(id),
    worker_id        uuid NOT NULL REFERENCES workers(id),
    advance_number   varchar(50) NOT NULL,
    advance_date     date NOT NULL,
    amount           numeric(18,2) NOT NULL,
    payment_mode     varchar(20) NOT NULL DEFAULT 'CASH',
    purpose          varchar(300),
    -- The field sheet mixes cash advances with ration, footwear and medicine. Whether those
    -- are recovered from the worker or borne by the contractor varies per item, so it is
    -- recorded rather than assumed.
    is_recoverable   boolean NOT NULL DEFAULT true,
    expense_id       uuid,                        -- FK added in V4 once expenses exists
    recovered_amount numeric(18,2) NOT NULL DEFAULT 0,
    balance_amount   numeric(18,2) GENERATED ALWAYS AS (amount - recovered_amount) STORED,
    status           varchar(20) NOT NULL DEFAULT 'OPEN',
    workflow_status  varchar(20) NOT NULL DEFAULT 'DRAFT',
    approved_by      uuid REFERENCES users(id),
    approved_at      timestamptz,
    remarks          text,
    source           varchar(15) NOT NULL DEFAULT 'ONLINE',
    created_at       timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz NOT NULL DEFAULT now(),
    created_by       uuid,
    updated_by       uuid,
    version          bigint NOT NULL DEFAULT 0,
    CONSTRAINT uq_worker_advance_number UNIQUE (org_id, advance_number),
    CONSTRAINT ck_worker_advance_amount CHECK (amount > 0),
    CONSTRAINT ck_worker_advance_recovered CHECK (recovered_amount >= 0 AND recovered_amount <= amount),
    CONSTRAINT ck_worker_advance_status CHECK (status IN ('OPEN','PARTIALLY_RECOVERED','RECOVERED','WRITTEN_OFF')),
    CONSTRAINT ck_worker_advance_workflow CHECK (workflow_status IN ('DRAFT','SUBMITTED','APPROVED','REJECTED','CANCELLED')),
    CONSTRAINT fk_worker_advance_site_project FOREIGN KEY (site_id, project_id) REFERENCES sites (id, project_id)
);
CREATE INDEX ix_worker_advances_worker ON worker_advances (worker_id, status);
CREATE INDEX ix_worker_advances_site   ON worker_advances (site_id, advance_date DESC);

-- Append-only, exactly like stock_transactions. Nobody types a worker's balance: it is what
-- he earned less what he has drawn. direction +1 increases what we owe him.
CREATE TABLE worker_ledger_entries (
    id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id            uuid NOT NULL REFERENCES organisations(id),
    project_id        uuid NOT NULL REFERENCES projects(id),
    site_id           uuid NOT NULL REFERENCES sites(id),
    worker_id         uuid NOT NULL REFERENCES workers(id),
    entry_date        date NOT NULL,
    period_year_month char(7) NOT NULL,           -- YYYY-MM settlement period
    entry_type        varchar(20) NOT NULL,
    direction         smallint NOT NULL,
    amount            numeric(18,2) NOT NULL,     -- always positive; direction carries the sign
    balance_after     numeric(18,2),
    source_type       varchar(30) NOT NULL,       -- ATTENDANCE | WORKER_ADVANCE | PAYMENT | MANUAL
    source_id         uuid,
    reason            text,
    created_at        timestamptz NOT NULL DEFAULT now(),
    created_by        uuid,
    CONSTRAINT ck_wle_type CHECK (entry_type IN (
        'WAGE_EARNED','OT_EARNED','ADVANCE','PAYMENT','DEDUCTION','ADJUSTMENT','OPENING')),
    CONSTRAINT ck_wle_direction CHECK (direction IN (1,-1)),
    CONSTRAINT ck_wle_amount CHECK (amount > 0),
    CONSTRAINT ck_wle_reason CHECK (entry_type <> 'ADJUSTMENT' OR reason IS NOT NULL),
    CONSTRAINT fk_wle_site_project FOREIGN KEY (site_id, project_id) REFERENCES sites (id, project_id)
);
CREATE INDEX ix_wle_worker_date ON worker_ledger_entries (worker_id, entry_date, created_at);
CREATE INDEX ix_wle_period      ON worker_ledger_entries (org_id, period_year_month, site_id);
CREATE INDEX ix_wle_source      ON worker_ledger_entries (source_type, source_id);

-- Verifying the same attendance row twice must not pay the worker twice. The worker-ledger
-- equivalent of the idempotency the stock ledger already guarantees.
CREATE UNIQUE INDEX uq_wle_attendance_posting
    ON worker_ledger_entries (source_id, entry_type)
    WHERE source_type = 'ATTENDANCE' AND source_id IS NOT NULL;

-- Derived cache, written inside the same transaction as the ledger under SELECT ... FOR
-- UPDATE. The ledger is the source of truth; a mismatch is a data-quality alert.
CREATE TABLE worker_balances (
    id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id           uuid NOT NULL REFERENCES organisations(id),
    worker_id        uuid NOT NULL REFERENCES workers(id),
    earned_amount    numeric(18,2) NOT NULL DEFAULT 0,
    advance_amount   numeric(18,2) NOT NULL DEFAULT 0,
    paid_amount      numeric(18,2) NOT NULL DEFAULT 0,
    deduction_amount numeric(18,2) NOT NULL DEFAULT 0,
    net_payable      numeric(18,2) NOT NULL DEFAULT 0,
    last_entry_at    timestamptz,
    updated_at       timestamptz NOT NULL DEFAULT now(),
    version          bigint NOT NULL DEFAULT 0,
    CONSTRAINT uq_worker_balance UNIQUE (worker_id)
);

CREATE TABLE overtime_approvals (
    id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id         uuid NOT NULL REFERENCES organisations(id),
    attendance_id  uuid NOT NULL REFERENCES attendance_records(id),
    overtime_hours numeric(6,2) NOT NULL,
    reason         varchar(300) NOT NULL,
    status         varchar(20) NOT NULL DEFAULT 'PENDING',
    approved_by    uuid REFERENCES users(id),
    approved_at    timestamptz,
    remarks        text,
    created_at     timestamptz NOT NULL DEFAULT now(),
    version        bigint NOT NULL DEFAULT 0,
    CONSTRAINT uq_ot_attendance UNIQUE (attendance_id),
    CONSTRAINT ck_ot_status CHECK (status IN ('PENDING','APPROVED','REJECTED'))
);


-- ==============================================================================================
-- INVENTORY — material master, purchasing, and the append-only stock ledger
-- ==============================================================================================

CREATE TABLE units (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id      uuid NOT NULL REFERENCES organisations(id),
    code        varchar(20)  NOT NULL,     -- BAG | KG | MT | CUM | SQM | BOX | NOS | MTR | LTR
    name        varchar(60)  NOT NULL,
    decimal_places int NOT NULL DEFAULT 3,
    is_active   boolean NOT NULL DEFAULT true,
    CONSTRAINT uq_units_org_code UNIQUE (org_id, code)
);

CREATE TABLE vendors (
    id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id         uuid NOT NULL REFERENCES organisations(id),
    code           varchar(40)  NOT NULL,
    name           varchar(200) NOT NULL,
    vendor_type    varchar(30) NOT NULL DEFAULT 'MATERIAL',  -- MATERIAL|SUBCONTRACTOR|SERVICE|TRANSPORT|OTHER
    contact_person varchar(150),
    mobile         varchar(20),
    email          varchar(150),
    address        text,
    gstin          varchar(15),
    pan            varchar(10),
    bank_account_no varchar(30),
    bank_ifsc      varchar(15),
    credit_days    int NOT NULL DEFAULT 0,
    opening_balance numeric(18,2) NOT NULL DEFAULT 0,
    is_active      boolean NOT NULL DEFAULT true,
    deleted_at     timestamptz,
    created_at     timestamptz NOT NULL DEFAULT now(),
    updated_at     timestamptz NOT NULL DEFAULT now(),
    created_by     uuid,
    updated_by     uuid,
    version        bigint NOT NULL DEFAULT 0,
    CONSTRAINT uq_vendors_org_code UNIQUE (org_id, code)
);

CREATE TABLE material_categories (
    id        uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id    uuid NOT NULL REFERENCES organisations(id),
    code      varchar(40) NOT NULL,
    name      varchar(120) NOT NULL,
    parent_id uuid REFERENCES material_categories(id),
    is_active boolean NOT NULL DEFAULT true,
    CONSTRAINT uq_matcat_org_code UNIQUE (org_id, code)
);

CREATE TABLE materials (
    id                 uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id             uuid NOT NULL REFERENCES organisations(id),
    code               varchar(40)  NOT NULL,
    name               varchar(200) NOT NULL,
    category_id        uuid REFERENCES material_categories(id),
    base_unit_id       uuid NOT NULL REFERENCES units(id),
    hsn_code           varchar(10),
    gst_percent        numeric(5,2) NOT NULL DEFAULT 0,
    min_stock_level    numeric(18,4) NOT NULL DEFAULT 0,
    standard_rate      numeric(18,4),
    preferred_vendor_id uuid REFERENCES vendors(id),
    is_consumable      boolean NOT NULL DEFAULT true,
    is_active          boolean NOT NULL DEFAULT true,
    deleted_at         timestamptz,
    created_at         timestamptz NOT NULL DEFAULT now(),
    updated_at         timestamptz NOT NULL DEFAULT now(),
    created_by         uuid,
    updated_by         uuid,
    version            bigint NOT NULL DEFAULT 0,
    CONSTRAINT uq_materials_org_code UNIQUE (org_id, code),
    CONSTRAINT ck_materials_gst CHECK (gst_percent >= 0 AND gst_percent <= 100)
);
CREATE INDEX ix_materials_category ON materials (category_id) WHERE deleted_at IS NULL;

-- Alternative units. factor_to_base converts one alt unit into base units.
-- Cement: base BAG, alt KG with factor_to_base = 1/50 = 0.02 (1 kg = 0.02 bag).
CREATE TABLE material_unit_conversions (
    id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id         uuid NOT NULL REFERENCES organisations(id),
    material_id    uuid NOT NULL REFERENCES materials(id) ON DELETE CASCADE,
    alt_unit_id    uuid NOT NULL REFERENCES units(id),
    factor_to_base numeric(18,8) NOT NULL,
    created_at     timestamptz NOT NULL DEFAULT now(),
    version        bigint NOT NULL DEFAULT 0,
    CONSTRAINT uq_muc UNIQUE (material_id, alt_unit_id),
    CONSTRAINT ck_muc_factor CHECK (factor_to_base > 0)
);

-- ---------------------------------------------------------------- purchasing
CREATE TABLE purchase_orders (
    id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id         uuid NOT NULL REFERENCES organisations(id),
    project_id     uuid NOT NULL REFERENCES projects(id),
    site_id        uuid NOT NULL REFERENCES sites(id),
    po_number      varchar(50) NOT NULL,
    vendor_id      uuid NOT NULL REFERENCES vendors(id),
    po_date        date NOT NULL,
    expected_date  date,
    status         varchar(20) NOT NULL DEFAULT 'OPEN',   -- OPEN|PARTIAL|CLOSED|CANCELLED
    total_amount   numeric(18,2) NOT NULL DEFAULT 0,
    remarks        text,
    created_at     timestamptz NOT NULL DEFAULT now(),
    updated_at     timestamptz NOT NULL DEFAULT now(),
    created_by     uuid,
    updated_by     uuid,
    version        bigint NOT NULL DEFAULT 0,
    CONSTRAINT uq_po_org_number UNIQUE (org_id, po_number),
    CONSTRAINT fk_po_site_project FOREIGN KEY (site_id, project_id) REFERENCES sites (id, project_id)
);

CREATE TABLE purchase_order_items (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    po_id         uuid NOT NULL REFERENCES purchase_orders(id) ON DELETE CASCADE,
    material_id   uuid NOT NULL REFERENCES materials(id),
    unit_id       uuid NOT NULL REFERENCES units(id),
    quantity      numeric(18,4) NOT NULL,
    rate          numeric(18,4) NOT NULL,
    gst_percent   numeric(5,2) NOT NULL DEFAULT 0,
    amount        numeric(18,2) NOT NULL,
    received_qty_base numeric(18,4) NOT NULL DEFAULT 0,
    CONSTRAINT ck_poi_qty CHECK (quantity > 0)
);
CREATE INDEX ix_poi_po ON purchase_order_items (po_id);

CREATE TABLE goods_receipts (
    id              uuid PRIMARY KEY,             -- client-generated, idempotent offline sync
    org_id          uuid NOT NULL REFERENCES organisations(id),
    project_id      uuid NOT NULL REFERENCES projects(id),
    site_id         uuid NOT NULL REFERENCES sites(id),
    store_id        uuid NOT NULL REFERENCES stores(id),
    grn_number      varchar(50) NOT NULL,
    vendor_id       uuid REFERENCES vendors(id),
    po_id           uuid REFERENCES purchase_orders(id),
    receipt_date    date NOT NULL,
    invoice_number  varchar(60),
    invoice_date    date,
    challan_number  varchar(60),
    vehicle_number  varchar(25),
    sub_total       numeric(18,2) NOT NULL DEFAULT 0,
    gst_amount      numeric(18,2) NOT NULL DEFAULT 0,
    total_amount    numeric(18,2) NOT NULL DEFAULT 0,
    expense_id      uuid,                          -- FK added in V4; links the purchase cost, never the consumption cost
    workflow_status varchar(20) NOT NULL DEFAULT 'DRAFT',  -- DRAFT|SUBMITTED|VERIFIED|REJECTED|CANCELLED
    received_by     uuid REFERENCES users(id),
    verified_by     uuid REFERENCES users(id),
    verified_at     timestamptz,
    rejection_reason text,
    remarks         text,
    source          varchar(15) NOT NULL DEFAULT 'ONLINE',
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    created_by      uuid,
    updated_by      uuid,
    version         bigint NOT NULL DEFAULT 0,
    CONSTRAINT uq_grn_org_number UNIQUE (org_id, grn_number),
    CONSTRAINT ck_grn_workflow CHECK (workflow_status IN ('DRAFT','SUBMITTED','VERIFIED','REJECTED','CANCELLED')),
    CONSTRAINT fk_grn_site_project FOREIGN KEY (site_id, project_id) REFERENCES sites (id, project_id)
);
CREATE INDEX ix_grn_site_date ON goods_receipts (site_id, receipt_date DESC);
-- The same vendor invoice must not be received twice. Placeholders are excluded: real
-- challans carry "-", "NIL", "Local" or a town name in the bill-number box, repeatedly, for
-- the same supplier, and treating those as invoice numbers rejects the second delivery of
-- the day. Comparison is case- and whitespace-insensitive so "SS/856" and " ss/856 " collide.
CREATE UNIQUE INDEX uq_grn_vendor_invoice ON goods_receipts
    (org_id, vendor_id, upper(btrim(invoice_number)))
    WHERE invoice_number IS NOT NULL
      AND workflow_status <> 'CANCELLED'
      AND upper(btrim(invoice_number)) NOT IN ('-','--','NIL','NA','N/A','LOCAL','CASH','');

CREATE TABLE goods_receipt_items (
    id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    grn_id         uuid NOT NULL REFERENCES goods_receipts(id) ON DELETE CASCADE,
    material_id    uuid NOT NULL REFERENCES materials(id),
    unit_id        uuid NOT NULL REFERENCES units(id),
    quantity       numeric(18,4) NOT NULL,
    quantity_base  numeric(18,4) NOT NULL,
    rate           numeric(18,4) NOT NULL,          -- per entered unit
    rate_base      numeric(18,4) NOT NULL,          -- per base unit, used for valuation
    gst_percent    numeric(5,2) NOT NULL DEFAULT 0,
    gst_amount     numeric(18,2) NOT NULL DEFAULT 0,
    amount         numeric(18,2) NOT NULL,
    remarks        varchar(300),
    CONSTRAINT ck_gri_qty CHECK (quantity > 0 AND quantity_base > 0)
);
CREATE INDEX ix_gri_grn ON goods_receipt_items (grn_id);

-- ---------------------------------------------------------------- issues
CREATE TABLE material_issues (
    id              uuid PRIMARY KEY,
    org_id          uuid NOT NULL REFERENCES organisations(id),
    project_id      uuid NOT NULL REFERENCES projects(id),
    site_id         uuid NOT NULL REFERENCES sites(id),
    store_id        uuid NOT NULL REFERENCES stores(id),
    issue_number    varchar(50) NOT NULL,
    issue_date      date NOT NULL,
    issued_to_name  varchar(150),
    issued_to_contractor_id uuid REFERENCES labour_contractors(id),
    boq_item_id     uuid,                        -- FK added in V5
    work_location   varchar(150),
    purpose         varchar(300),
    workflow_status varchar(20) NOT NULL DEFAULT 'DRAFT',
    approved_by     uuid REFERENCES users(id),
    approved_at     timestamptz,
    rejection_reason text,
    source          varchar(15) NOT NULL DEFAULT 'ONLINE',
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    created_by      uuid,
    updated_by      uuid,
    version         bigint NOT NULL DEFAULT 0,
    CONSTRAINT uq_issue_org_number UNIQUE (org_id, issue_number),
    CONSTRAINT ck_issue_workflow CHECK (workflow_status IN ('DRAFT','SUBMITTED','APPROVED','REJECTED','CANCELLED')),
    CONSTRAINT fk_issue_site_project FOREIGN KEY (site_id, project_id) REFERENCES sites (id, project_id)
);
CREATE INDEX ix_issues_site_date ON material_issues (site_id, issue_date DESC);

CREATE TABLE material_issue_items (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    issue_id      uuid NOT NULL REFERENCES material_issues(id) ON DELETE CASCADE,
    material_id   uuid NOT NULL REFERENCES materials(id),
    unit_id       uuid NOT NULL REFERENCES units(id),
    quantity      numeric(18,4) NOT NULL,
    quantity_base numeric(18,4) NOT NULL,
    issued_rate   numeric(18,4),                 -- moving average at issue time, frozen
    value         numeric(18,2),
    boq_item_id   uuid,
    remarks       varchar(300),
    CONSTRAINT ck_mii_qty CHECK (quantity > 0 AND quantity_base > 0)
);
CREATE INDEX ix_mii_issue ON material_issue_items (issue_id);

-- ---------------------------------------------------------------- transfers
CREATE TABLE stock_transfers (
    id                uuid PRIMARY KEY,
    org_id            uuid NOT NULL REFERENCES organisations(id),
    transfer_number   varchar(50) NOT NULL,
    from_store_id     uuid NOT NULL REFERENCES stores(id),
    to_store_id       uuid NOT NULL REFERENCES stores(id),
    transfer_date     date NOT NULL,
    vehicle_number    varchar(25),
    status            varchar(20) NOT NULL DEFAULT 'CREATED',  -- CREATED|DISPATCHED|IN_TRANSIT|RECEIVED|CLOSED|CANCELLED
    dispatched_at     timestamptz,
    dispatched_by     uuid REFERENCES users(id),
    received_at       timestamptz,
    received_by       uuid REFERENCES users(id),
    remarks           text,
    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz NOT NULL DEFAULT now(),
    created_by        uuid,
    updated_by        uuid,
    version           bigint NOT NULL DEFAULT 0,
    CONSTRAINT uq_transfer_org_number UNIQUE (org_id, transfer_number),
    CONSTRAINT ck_transfer_stores CHECK (from_store_id <> to_store_id),
    CONSTRAINT ck_transfer_status CHECK (status IN ('CREATED','DISPATCHED','IN_TRANSIT','RECEIVED','CLOSED','CANCELLED'))
);

CREATE TABLE stock_transfer_items (
    id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    transfer_id      uuid NOT NULL REFERENCES stock_transfers(id) ON DELETE CASCADE,
    material_id      uuid NOT NULL REFERENCES materials(id),
    unit_id          uuid NOT NULL REFERENCES units(id),
    quantity         numeric(18,4) NOT NULL,
    quantity_base    numeric(18,4) NOT NULL,
    received_qty_base numeric(18,4),
    rate_base        numeric(18,4),
    shortage_qty_base numeric(18,4) NOT NULL DEFAULT 0,
    remarks          varchar(300),
    CONSTRAINT ck_sti_qty CHECK (quantity > 0)
);
CREATE INDEX ix_sti_transfer ON stock_transfer_items (transfer_id);

-- ---------------------------------------------------------------- physical counts
CREATE TABLE physical_stock_counts (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id       uuid NOT NULL REFERENCES organisations(id),
    store_id     uuid NOT NULL REFERENCES stores(id),
    count_number varchar(50) NOT NULL,
    count_date   date NOT NULL,
    status       varchar(20) NOT NULL DEFAULT 'DRAFT',   -- DRAFT|SUBMITTED|APPROVED|REJECTED
    counted_by   uuid REFERENCES users(id),
    approved_by  uuid REFERENCES users(id),
    approved_at  timestamptz,
    remarks      text,
    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now(),
    created_by   uuid,
    updated_by   uuid,
    version      bigint NOT NULL DEFAULT 0,
    CONSTRAINT uq_count_org_number UNIQUE (org_id, count_number)
);

CREATE TABLE physical_stock_count_items (
    id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    count_id       uuid NOT NULL REFERENCES physical_stock_counts(id) ON DELETE CASCADE,
    material_id    uuid NOT NULL REFERENCES materials(id),
    system_qty_base numeric(18,4) NOT NULL,
    counted_qty_base numeric(18,4) NOT NULL,
    variance_qty_base numeric(18,4) GENERATED ALWAYS AS (counted_qty_base - system_qty_base) STORED,
    variance_reason varchar(300),
    CONSTRAINT uq_psci UNIQUE (count_id, material_id)
);

-- ---------------------------------------------------------------- the ledger
-- Append-only. Never updated, never deleted. Every quantity is in the material base unit.
-- direction: +1 increases stock, -1 decreases it. Service layer sets it from txn_type.
CREATE TABLE stock_transactions (
    id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id         uuid NOT NULL REFERENCES organisations(id),
    project_id     uuid REFERENCES projects(id),
    site_id        uuid NOT NULL REFERENCES sites(id),
    store_id       uuid NOT NULL REFERENCES stores(id),
    material_id    uuid NOT NULL REFERENCES materials(id),
    txn_type       varchar(25) NOT NULL,
    direction      smallint    NOT NULL,
    txn_date       date        NOT NULL,
    quantity_base  numeric(18,4) NOT NULL,        -- always positive; direction carries the sign
    rate_base      numeric(18,4) NOT NULL DEFAULT 0,
    value          numeric(18,2) NOT NULL DEFAULT 0,
    balance_after  numeric(18,4),                 -- running balance snapshot for fast ledger reads
    avg_rate_after numeric(18,4),
    source_type    varchar(30) NOT NULL,          -- OPENING|GOODS_RECEIPT|ISSUE|TRANSFER|COUNT|WASTAGE|ADJUSTMENT
    source_id      uuid,
    source_line_id uuid,
    boq_item_id    uuid,                          -- FK added in V5
    reason         text,                          -- mandatory for wastage, damage and adjustment
    created_at     timestamptz NOT NULL DEFAULT now(),
    created_by     uuid,
    CONSTRAINT ck_stx_type CHECK (txn_type IN (
        'OPENING_STOCK','RECEIPT','ISSUE','TRANSFER_OUT','TRANSFER_IN',
        'RETURN','WASTAGE','DAMAGE','ADJUSTMENT')),
    CONSTRAINT ck_stx_direction CHECK (direction IN (1,-1)),
    CONSTRAINT ck_stx_qty CHECK (quantity_base > 0),
    CONSTRAINT ck_stx_reason CHECK (
        txn_type NOT IN ('WASTAGE','DAMAGE','ADJUSTMENT') OR reason IS NOT NULL),
    -- project_id is nullable here, and MATCH SIMPLE skips the check when either column is
    -- null, so this constrains only the rows that name both.
    CONSTRAINT fk_stx_site_project FOREIGN KEY (site_id, project_id) REFERENCES sites (id, project_id)
);
CREATE INDEX ix_stx_store_material_date ON stock_transactions (store_id, material_id, txn_date, created_at);
CREATE INDEX ix_stx_site_date  ON stock_transactions (site_id, txn_date);
CREATE INDEX ix_stx_source     ON stock_transactions (source_type, source_id);
CREATE INDEX ix_stx_boq        ON stock_transactions (boq_item_id) WHERE boq_item_id IS NOT NULL;

-- Derived cache maintained inside the same transaction as the ledger write, under SELECT FOR UPDATE.
-- The ledger is the source of truth; a mismatch is a data-quality alert, not a fixable balance.
CREATE TABLE stock_balances (
    id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id         uuid NOT NULL REFERENCES organisations(id),
    store_id       uuid NOT NULL REFERENCES stores(id),
    material_id    uuid NOT NULL REFERENCES materials(id),
    quantity_base  numeric(18,4) NOT NULL DEFAULT 0,
    moving_avg_rate numeric(18,4) NOT NULL DEFAULT 0,
    stock_value    numeric(18,2) NOT NULL DEFAULT 0,
    in_transit_qty_base numeric(18,4) NOT NULL DEFAULT 0,
    last_txn_at    timestamptz,
    updated_at     timestamptz NOT NULL DEFAULT now(),
    version        bigint NOT NULL DEFAULT 0,
    CONSTRAINT uq_stock_balance UNIQUE (store_id, material_id),
    CONSTRAINT ck_balance_non_negative CHECK (quantity_base >= 0)
);
CREATE INDEX ix_balances_material ON stock_balances (material_id);

-- The missing link between a tender and a shopping list. A BOQ line says "RCC M25 in
-- columns, 5.94 cum"; it does not say "30 bags of cement". The field sheet applies 5.0 bags
-- per cum for RCC and 0.63 per cum for brickwork by hand - these are those coefficients,
-- stored once and reused across every tender. work_category matches boq_items.category, so a
-- parsed NIT can be turned into material quantities without mapping every line by hand.
CREATE TABLE material_consumption_norms (
    id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id            uuid NOT NULL REFERENCES organisations(id),
    work_category     varchar(80) NOT NULL,
    work_sub_type     varchar(80),                        -- grade: M25, 1:4:8, Fe-500D
    material_id       uuid NOT NULL REFERENCES materials(id),
    work_unit_id      uuid NOT NULL REFERENCES units(id),
    qty_per_work_unit numeric(18,6) NOT NULL,
    source            varchar(20) NOT NULL DEFAULT 'INTERNAL',
    is_active         boolean NOT NULL DEFAULT true,
    notes             text,
    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz NOT NULL DEFAULT now(),
    created_by        uuid,
    updated_by        uuid,
    version           bigint NOT NULL DEFAULT 0,
    CONSTRAINT ck_norm_qty CHECK (qty_per_work_unit > 0),
    CONSTRAINT ck_norm_source CHECK (source IN ('CPWD_AOR','INTERNAL','PROJECT'))
);
-- A unique index rather than a table constraint: work_sub_type is nullable and NULLs compare
-- as distinct, so the same norm could otherwise be entered twice with no sub-type.
CREATE UNIQUE INDEX uq_norm_scope ON material_consumption_norms
    (org_id, work_category, COALESCE(work_sub_type, ''), material_id);

-- Rejected negative-stock attempts feed the data-quality dashboard.
CREATE TABLE stock_violation_log (
    id            bigserial PRIMARY KEY,
    org_id        uuid NOT NULL,
    store_id      uuid NOT NULL,
    material_id   uuid NOT NULL,
    attempted_qty_base numeric(18,4) NOT NULL,
    available_qty_base numeric(18,4) NOT NULL,
    source_type   varchar(30),
    attempted_by  uuid,
    attempted_at  timestamptz NOT NULL DEFAULT now()
);


-- ==============================================================================================
-- EXPENSES AND CASH — expenses, payments, site advances
-- ==============================================================================================

CREATE TABLE expense_categories (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id        uuid NOT NULL REFERENCES organisations(id),
    code          varchar(40)  NOT NULL,
    name          varchar(120) NOT NULL,
    parent_id     uuid REFERENCES expense_categories(id),
    is_material_purchase boolean NOT NULL DEFAULT false,  -- true = value is inventory, not cost incurred
    -- true = rows here are wage disbursement, not incremental cost. Money handed to a worker
    -- settles a wage already counted through verified attendance, so counting it again as an
    -- expense doubles the labour cost. The exact counterpart of is_material_purchase, which
    -- already keeps purchase out of consumption. Reconciled against worker_ledger_entries.
    is_labour_payment boolean NOT NULL DEFAULT false,
    requires_vendor boolean NOT NULL DEFAULT false,
    is_active     boolean NOT NULL DEFAULT true,
    sort_order    int NOT NULL DEFAULT 0,
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now(),
    version       bigint NOT NULL DEFAULT 0,
    CONSTRAINT uq_expcat_org_code UNIQUE (org_id, code)
);

CREATE TABLE expense_settings (
    id                       uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id                   uuid NOT NULL UNIQUE REFERENCES organisations(id),
    bill_required_above      numeric(18,2) NOT NULL DEFAULT 5000,
    admin_approval_above     numeric(18,2) NOT NULL DEFAULT 25000,
    duplicate_check_enabled  boolean NOT NULL DEFAULT true,
    updated_at               timestamptz NOT NULL DEFAULT now(),
    version                  bigint NOT NULL DEFAULT 0
);

CREATE TABLE expenses (
    id                uuid PRIMARY KEY,              -- client-generated, idempotent offline sync
    org_id            uuid NOT NULL REFERENCES organisations(id),
    project_id        uuid NOT NULL REFERENCES projects(id),
    site_id           uuid NOT NULL REFERENCES sites(id),
    expense_number    varchar(50) NOT NULL,
    expense_date      date NOT NULL,
    category_id       uuid NOT NULL REFERENCES expense_categories(id),
    subcategory_id    uuid REFERENCES expense_categories(id),
    vendor_id         uuid REFERENCES vendors(id),
    boq_item_id       uuid,                          -- FK added in V5
    cost_code         varchar(40),
    description       text NOT NULL,
    bill_number       varchar(60),
    bill_date         date,
    amount_before_tax numeric(18,2) NOT NULL,
    gst_percent       numeric(5,2)  NOT NULL DEFAULT 0,
    gst_amount        numeric(18,2) NOT NULL DEFAULT 0,
    total_amount      numeric(18,2) NOT NULL,
    payment_mode      varchar(20),                   -- CASH|BANK|UPI|CHEQUE|CARD|ADVANCE
    payment_status    varchar(20) NOT NULL DEFAULT 'UNPAID',  -- UNPAID|PARTIAL|PAID
    paid_amount       numeric(18,2) NOT NULL DEFAULT 0,
    no_bill_reason    text,
    goods_receipt_id  uuid REFERENCES goods_receipts(id),
    site_advance_id   uuid,                          -- FK added below
    workflow_status   varchar(25) NOT NULL DEFAULT 'DRAFT',
    submitted_at      timestamptz,
    submitted_by      uuid REFERENCES users(id),
    approved_at       timestamptz,
    approved_by       uuid REFERENCES users(id),
    rejection_reason  text,
    voided_at         timestamptz,
    voided_by         uuid REFERENCES users(id),
    void_reason       text,
    duplicate_of_id   uuid REFERENCES expenses(id),
    duplicate_override_reason text,
    remarks           text,
    source            varchar(15) NOT NULL DEFAULT 'ONLINE',
    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz NOT NULL DEFAULT now(),
    created_by        uuid,
    updated_by        uuid,
    version           bigint NOT NULL DEFAULT 0,
    CONSTRAINT uq_expense_org_number UNIQUE (org_id, expense_number),
    CONSTRAINT ck_expense_workflow CHECK (workflow_status IN
        ('DRAFT','SUBMITTED','L1_APPROVED','APPROVED','REJECTED','RETURNED','VOIDED')),
    CONSTRAINT ck_expense_amounts CHECK (amount_before_tax >= 0 AND gst_amount >= 0
        AND total_amount >= 0 AND paid_amount >= 0 AND paid_amount <= total_amount),
    CONSTRAINT ck_expense_payment_status CHECK (payment_status IN ('UNPAID','PARTIAL','PAID')),
    -- an approved expense must carry either a bill number or a written reason for its absence
    CONSTRAINT ck_expense_bill_reason CHECK (
        workflow_status NOT IN ('APPROVED','L1_APPROVED')
        OR bill_number IS NOT NULL OR no_bill_reason IS NOT NULL),
    CONSTRAINT fk_expense_site_project FOREIGN KEY (site_id, project_id) REFERENCES sites (id, project_id)
);
CREATE INDEX ix_expenses_site_date  ON expenses (site_id, expense_date DESC);
CREATE INDEX ix_expenses_status     ON expenses (org_id, workflow_status);
CREATE INDEX ix_expenses_vendor     ON expenses (vendor_id, payment_status);
CREATE INDEX ix_expenses_boq        ON expenses (boq_item_id) WHERE boq_item_id IS NOT NULL;
-- Duplicate-invoice detection: the same vendor and bill number cannot be booked twice.
-- vendor_id is nullable and NULLs compare as distinct in Postgres, so without COALESCE every
-- vendorless expense escaped the check entirely - and most site expenses have no vendor.
-- Placeholder bill numbers are excluded for the same reason as on goods_receipts.
CREATE UNIQUE INDEX uq_expense_vendor_bill ON expenses
    (org_id, COALESCE(vendor_id, '00000000-0000-0000-0000-000000000000'::uuid),
     upper(btrim(bill_number)))
    WHERE bill_number IS NOT NULL
      AND workflow_status NOT IN ('VOIDED','REJECTED')
      AND upper(btrim(bill_number)) NOT IN ('-','--','NIL','NA','N/A','LOCAL','CASH','');

CREATE TABLE expense_attachments (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    expense_id    uuid NOT NULL REFERENCES expenses(id) ON DELETE CASCADE,
    attachment_id uuid NOT NULL REFERENCES attachments(id),
    doc_type      varchar(30) NOT NULL DEFAULT 'BILL',
    CONSTRAINT uq_expense_attachment UNIQUE (expense_id, attachment_id)
);

CREATE TABLE payments (
    id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id           uuid NOT NULL REFERENCES organisations(id),
    project_id       uuid REFERENCES projects(id),
    site_id          uuid REFERENCES sites(id),
    expense_id       uuid REFERENCES expenses(id),
    vendor_id        uuid REFERENCES vendors(id),
    payment_number   varchar(50) NOT NULL,
    payment_date     date NOT NULL,
    amount           numeric(18,2) NOT NULL,
    payment_mode     varchar(20) NOT NULL,
    reference_number varchar(80),
    bank_account     varchar(60),
    remarks          text,
    reconciled_at    timestamptz,
    reconciled_by    uuid REFERENCES users(id),
    created_at       timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz NOT NULL DEFAULT now(),
    created_by       uuid,
    updated_by       uuid,
    version          bigint NOT NULL DEFAULT 0,
    CONSTRAINT uq_payment_org_number UNIQUE (org_id, payment_number),
    CONSTRAINT ck_payment_amount CHECK (amount > 0)
);
CREATE INDEX ix_payments_expense ON payments (expense_id);
CREATE INDEX ix_payments_vendor  ON payments (vendor_id, payment_date DESC);

-- ---------------------------------------------------------------- site advances
CREATE TABLE site_advances (
    id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id            uuid NOT NULL REFERENCES organisations(id),
    project_id        uuid NOT NULL REFERENCES projects(id),
    site_id           uuid NOT NULL REFERENCES sites(id),
    advance_number    varchar(50) NOT NULL,
    issued_to_user_id uuid NOT NULL REFERENCES users(id),
    advance_date      date NOT NULL,
    amount            numeric(18,2) NOT NULL,
    payment_mode      varchar(20) NOT NULL,
    reference_number  varchar(80),
    purpose           text NOT NULL,
    adjusted_amount   numeric(18,2) NOT NULL DEFAULT 0,
    returned_amount   numeric(18,2) NOT NULL DEFAULT 0,
    balance_amount    numeric(18,2) GENERATED ALWAYS AS (amount - adjusted_amount - returned_amount) STORED,
    settlement_status varchar(20) NOT NULL DEFAULT 'OPEN',  -- OPEN|PARTIALLY_SETTLED|SETTLED|CANCELLED
    issued_by         uuid REFERENCES users(id),
    closed_at         timestamptz,
    remarks           text,
    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz NOT NULL DEFAULT now(),
    created_by        uuid,
    updated_by        uuid,
    version           bigint NOT NULL DEFAULT 0,
    CONSTRAINT uq_advance_org_number UNIQUE (org_id, advance_number),
    CONSTRAINT ck_advance_amount CHECK (amount > 0),
    CONSTRAINT ck_advance_adjusted CHECK (adjusted_amount >= 0 AND returned_amount >= 0
        AND adjusted_amount + returned_amount <= amount),
    CONSTRAINT ck_advance_status CHECK (settlement_status IN ('OPEN','PARTIALLY_SETTLED','SETTLED','CANCELLED')),
    CONSTRAINT fk_site_advance_site_project FOREIGN KEY (site_id, project_id) REFERENCES sites (id, project_id)
);
CREATE INDEX ix_advances_user ON site_advances (issued_to_user_id, settlement_status);
CREATE INDEX ix_advances_site ON site_advances (site_id, settlement_status);

ALTER TABLE expenses ADD CONSTRAINT fk_expense_advance
    FOREIGN KEY (site_advance_id) REFERENCES site_advances(id);

CREATE TABLE advance_settlements (
    id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id            uuid NOT NULL REFERENCES organisations(id),
    advance_id        uuid NOT NULL REFERENCES site_advances(id),
    settlement_number varchar(50) NOT NULL,
    settlement_date   date NOT NULL,
    expenses_amount   numeric(18,2) NOT NULL DEFAULT 0,
    returned_amount   numeric(18,2) NOT NULL DEFAULT 0,
    status            varchar(20) NOT NULL DEFAULT 'SUBMITTED',  -- SUBMITTED|APPROVED|REJECTED
    submitted_by      uuid REFERENCES users(id),
    approved_by       uuid REFERENCES users(id),
    approved_at       timestamptz,
    rejection_reason  text,
    remarks           text,
    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz NOT NULL DEFAULT now(),
    created_by        uuid,
    updated_by        uuid,
    version           bigint NOT NULL DEFAULT 0,
    CONSTRAINT uq_settlement_org_number UNIQUE (org_id, settlement_number),
    CONSTRAINT ck_settlement_status CHECK (status IN ('SUBMITTED','APPROVED','REJECTED'))
);

CREATE TABLE advance_settlement_expenses (
    settlement_id uuid NOT NULL REFERENCES advance_settlements(id) ON DELETE CASCADE,
    expense_id    uuid NOT NULL REFERENCES expenses(id),
    amount        numeric(18,2) NOT NULL,
    PRIMARY KEY (settlement_id, expense_id)
);

ALTER TABLE goods_receipts ADD CONSTRAINT fk_grn_expense
    FOREIGN KEY (expense_id) REFERENCES expenses(id);

-- Deferred from V2: a worker advance paid out through the cash book links to its expense row.
ALTER TABLE worker_advances ADD CONSTRAINT fk_worker_advance_expense
    FOREIGN KEY (expense_id) REFERENCES expenses(id);


-- ==============================================================================================
-- BOQ AND DPR — work items, material estimates, daily progress reports
-- ==============================================================================================

CREATE TABLE boq_items (
    id                     uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id                 uuid NOT NULL REFERENCES organisations(id),
    project_id             uuid NOT NULL REFERENCES projects(id),
    site_id                uuid REFERENCES sites(id),          -- null = applies to whole project
    parent_id              uuid REFERENCES boq_items(id),      -- sub-items for future MB structure
    item_number            varchar(40)  NOT NULL,
    description            text NOT NULL,
    unit_id                uuid NOT NULL REFERENCES units(id),
    contract_quantity      numeric(18,4) NOT NULL DEFAULT 0,
    contract_rate          numeric(18,4) NOT NULL DEFAULT 0,
    contract_amount        numeric(18,2) NOT NULL DEFAULT 0,
    completed_quantity     numeric(18,4) NOT NULL DEFAULT 0,
    planned_start_date     date,
    planned_completion_date date,
    actual_start_date      date,
    actual_completion_date date,
    budget_labour_cost     numeric(18,2) NOT NULL DEFAULT 0,
    budget_material_cost   numeric(18,2) NOT NULL DEFAULT 0,
    budget_subcontract_cost numeric(18,2) NOT NULL DEFAULT 0,
    budget_other_cost      numeric(18,2) NOT NULL DEFAULT 0,
    status                 varchar(20) NOT NULL DEFAULT 'NOT_STARTED',
    -- Classification the NIT parser produces. Kept on the item so an imported tender keeps
    -- its shape instead of being flattened, and so consumption norms can be matched by
    -- category rather than by a human mapping every line.
    work_part              varchar(40),          -- Civil Works | E&M Works
    category               varchar(80),          -- e.g. "Concrete & RCC", "Plumbing & Sanitary"
    source                 varchar(20) NOT NULL DEFAULT 'MANUAL',
    -- The parser emits UNALLOCATED placeholder rows when the extracted lines do not sum to
    -- the stated BOQ total. Those are reconciliation artefacts, not work: labour, material
    -- and cash must never be bookable against them.
    is_synthetic           boolean NOT NULL DEFAULT false,
    sort_order             int NOT NULL DEFAULT 0,
    deleted_at             timestamptz,
    created_at             timestamptz NOT NULL DEFAULT now(),
    updated_at             timestamptz NOT NULL DEFAULT now(),
    created_by             uuid,
    updated_by             uuid,
    version                bigint NOT NULL DEFAULT 0,
    CONSTRAINT uq_boq_project_number UNIQUE (project_id, item_number),
    CONSTRAINT ck_boq_status CHECK (status IN ('NOT_STARTED','IN_PROGRESS','COMPLETED','ON_HOLD','CANCELLED')),
    CONSTRAINT ck_boq_qty CHECK (contract_quantity >= 0 AND completed_quantity >= 0),
    CONSTRAINT ck_boq_source CHECK (source IN ('MANUAL','NIT_IMPORT','EXCEL_IMPORT'))
);
CREATE INDEX ix_boq_project  ON boq_items (project_id) WHERE deleted_at IS NULL;
CREATE INDEX ix_boq_site     ON boq_items (site_id) WHERE deleted_at IS NULL;
CREATE INDEX ix_boq_category ON boq_items (project_id, category) WHERE deleted_at IS NULL;

-- Estimated quantities, against which the stock ledger supplies the actual. Three levels are
-- kept apart on purpose: the gap between what was tendered and what the drawings actually
-- demand is a different question from whether site consumed what was planned. The field
-- steel figure of 4,550 kg came from a bar bending schedule, not from the NIT.
CREATE TABLE material_estimates (
    id                 uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id             uuid NOT NULL REFERENCES organisations(id),
    project_id         uuid NOT NULL REFERENCES projects(id),
    site_id            uuid REFERENCES sites(id),          -- null = whole project
    boq_item_id        uuid REFERENCES boq_items(id),      -- null = project-wide estimate
    material_id        uuid NOT NULL REFERENCES materials(id),
    estimate_level     varchar(25) NOT NULL,
    estimated_qty_base numeric(18,4) NOT NULL,
    wastage_percent    numeric(5,2) NOT NULL DEFAULT 0,    -- the "add 3%" the field sheet applies
    qty_with_wastage   numeric(18,4) GENERATED ALWAYS AS
                       (round(estimated_qty_base * (1 + wastage_percent / 100), 4)) STORED,
    -- A revision supersedes rather than overwrites, so last month's variance cannot silently
    -- rewrite itself - the same rule that freezes a wage at verification.
    revision           int NOT NULL DEFAULT 1,
    effective_from     date NOT NULL DEFAULT CURRENT_DATE,
    superseded_at      timestamptz,
    source_ref         varchar(120),                       -- NIT number, drawing number, BBS sheet
    derived_from_norm  boolean NOT NULL DEFAULT false,
    notes              text,
    created_at         timestamptz NOT NULL DEFAULT now(),
    updated_at         timestamptz NOT NULL DEFAULT now(),
    created_by         uuid,
    updated_by         uuid,
    version            bigint NOT NULL DEFAULT 0,
    CONSTRAINT ck_mest_level CHECK (estimate_level IN ('TENDER_BOQ','EXECUTION_TAKEOFF','BBS','REVISED')),
    CONSTRAINT ck_mest_qty CHECK (estimated_qty_base >= 0),
    CONSTRAINT ck_mest_wastage CHECK (wastage_percent >= 0 AND wastage_percent <= 100)
);
-- One live estimate per material per scope per level; superseded revisions stay readable.
CREATE UNIQUE INDEX uq_material_estimate_live ON material_estimates
    (project_id, COALESCE(boq_item_id, '00000000-0000-0000-0000-000000000000'::uuid),
     material_id, estimate_level)
    WHERE superseded_at IS NULL;
CREATE INDEX ix_mest_project ON material_estimates (project_id, material_id);
CREATE INDEX ix_mest_boq     ON material_estimates (boq_item_id) WHERE boq_item_id IS NOT NULL;

-- Progress is recorded as dated entries, never as an overwritten total.
CREATE TABLE boq_progress_entries (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id        uuid NOT NULL REFERENCES organisations(id),
    boq_item_id   uuid NOT NULL REFERENCES boq_items(id),
    site_id       uuid NOT NULL REFERENCES sites(id),
    entry_date    date NOT NULL,
    quantity      numeric(18,4) NOT NULL,
    dpr_id        uuid,                              -- FK added after DPR table
    remarks       text,
    recorded_by   uuid REFERENCES users(id),
    created_at    timestamptz NOT NULL DEFAULT now(),
    version       bigint NOT NULL DEFAULT 0,
    CONSTRAINT ck_boq_entry_qty CHECK (quantity <> 0)
);
CREATE INDEX ix_boq_entries_item ON boq_progress_entries (boq_item_id, entry_date);

-- Deferred foreign keys from earlier migrations.
ALTER TABLE attendance_records   ADD CONSTRAINT fk_attendance_boq  FOREIGN KEY (boq_item_id) REFERENCES boq_items(id);
ALTER TABLE material_issues      ADD CONSTRAINT fk_issue_boq       FOREIGN KEY (boq_item_id) REFERENCES boq_items(id);
ALTER TABLE material_issue_items ADD CONSTRAINT fk_issue_item_boq  FOREIGN KEY (boq_item_id) REFERENCES boq_items(id);
ALTER TABLE stock_transactions   ADD CONSTRAINT fk_stx_boq         FOREIGN KEY (boq_item_id) REFERENCES boq_items(id);
ALTER TABLE expenses             ADD CONSTRAINT fk_expense_boq     FOREIGN KEY (boq_item_id) REFERENCES boq_items(id);
CREATE INDEX ix_attendance_boq ON attendance_records (boq_item_id) WHERE boq_item_id IS NOT NULL;

-- ---------------------------------------------------------------- DPR
CREATE TABLE daily_progress_reports (
    id                  uuid PRIMARY KEY,          -- client-generated for offline drafts
    org_id              uuid NOT NULL REFERENCES organisations(id),
    project_id          uuid NOT NULL REFERENCES projects(id),
    site_id             uuid NOT NULL REFERENCES sites(id),
    report_date         date NOT NULL,
    dpr_number          varchar(50) NOT NULL,
    weather             varchar(30),               -- CLEAR|CLOUDY|RAIN|HEAVY_RAIN|EXTREME_HEAT
    temperature_c       numeric(4,1),
    working_hours_lost  numeric(4,1) NOT NULL DEFAULT 0,
    -- rolled-up snapshots, frozen at submission so the PDF never changes retrospectively
    labour_present_count int,
    labour_regular_hours numeric(10,2),
    labour_overtime_hours numeric(10,2),
    labour_cost         numeric(18,2),
    material_received_value numeric(18,2),
    material_consumed_value numeric(18,2),
    expense_amount      numeric(18,2),
    work_summary        text,
    delays              text,
    safety_observations text,
    quality_observations text,
    instructions_received text,
    management_attention text,
    next_day_plan       text,
    workflow_status     varchar(20) NOT NULL DEFAULT 'DRAFT',
    prepared_by         uuid REFERENCES users(id),
    submitted_at        timestamptz,
    verified_by         uuid REFERENCES users(id),
    verified_at         timestamptz,
    rejection_reason    text,
    source              varchar(15) NOT NULL DEFAULT 'ONLINE',
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now(),
    created_by          uuid,
    updated_by          uuid,
    version             bigint NOT NULL DEFAULT 0,
    CONSTRAINT uq_dpr_site_date UNIQUE (site_id, report_date),
    CONSTRAINT uq_dpr_org_number UNIQUE (org_id, dpr_number),
    CONSTRAINT ck_dpr_workflow CHECK (workflow_status IN ('DRAFT','SUBMITTED','VERIFIED','REJECTED')),
    CONSTRAINT fk_dpr_site_project FOREIGN KEY (site_id, project_id) REFERENCES sites (id, project_id)
);
CREATE INDEX ix_dpr_site_date ON daily_progress_reports (site_id, report_date DESC);

ALTER TABLE boq_progress_entries ADD CONSTRAINT fk_boq_entry_dpr
    FOREIGN KEY (dpr_id) REFERENCES daily_progress_reports(id);

CREATE TABLE dpr_work_items (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    dpr_id        uuid NOT NULL REFERENCES daily_progress_reports(id) ON DELETE CASCADE,
    boq_item_id   uuid REFERENCES boq_items(id),
    activity      text NOT NULL,
    work_location varchar(150),
    quantity      numeric(18,4),
    unit_id       uuid REFERENCES units(id),
    remarks       text,
    sort_order    int NOT NULL DEFAULT 0
);
CREATE INDEX ix_dpr_work_items_dpr ON dpr_work_items (dpr_id);

CREATE TABLE dpr_labour (
    id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    dpr_id            uuid NOT NULL REFERENCES daily_progress_reports(id) ON DELETE CASCADE,
    skill_category_id uuid REFERENCES skill_categories(id),
    labour_contractor_id uuid REFERENCES labour_contractors(id),
    head_count        int NOT NULL DEFAULT 0,
    regular_hours     numeric(10,2) NOT NULL DEFAULT 0,
    overtime_hours    numeric(10,2) NOT NULL DEFAULT 0
);
CREATE INDEX ix_dpr_labour_dpr ON dpr_labour (dpr_id);

CREATE TABLE dpr_machinery (
    id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    dpr_id         uuid NOT NULL REFERENCES daily_progress_reports(id) ON DELETE CASCADE,
    machinery_name varchar(150) NOT NULL,
    count          int NOT NULL DEFAULT 1,
    hours_used     numeric(6,2),
    idle_hours     numeric(6,2),
    remarks        varchar(300)
);
CREATE INDEX ix_dpr_machinery_dpr ON dpr_machinery (dpr_id);

CREATE TABLE dpr_photos (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    dpr_id        uuid NOT NULL REFERENCES daily_progress_reports(id) ON DELETE CASCADE,
    attachment_id uuid NOT NULL REFERENCES attachments(id),
    caption       varchar(300),
    taken_at      timestamptz,
    sort_order    int NOT NULL DEFAULT 0,
    CONSTRAINT uq_dpr_photo UNIQUE (dpr_id, attachment_id)
);
