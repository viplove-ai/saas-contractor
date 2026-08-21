-- Nirman — the running account bill, and the measurements it is built from.
--
-- The contractor's engineer prepares an RA bill in a spreadsheet of forty-three sheets and
-- six and a half thousand formulas, carried forward from bill to bill by copy and paste. In
-- the copy this migration was designed against, two hundred and eighty-two of those formulas
-- had already decayed to #REF!, the rate table pointed at a workbook that was not there, the
-- bill number said three things in three places, and a hundred and fifteen "amount of
-- previous bill" figures were typed in by hand off the last bill's printout. None of that is
-- carelessness. It is what happens when a document that must be derived is instead
-- maintained.
--
-- Every one of those figures is derivable from four inputs: the agreement, the measurements,
-- the rate analyses for items the agreement did not price, and the previous bill's closing
-- position. This migration holds the second and the fourth, and the parameters that turn a
-- published schedule of rates into the rate this contract actually pays.
--
--
-- WHY MEASUREMENT IS ITS OWN RECORD, AND NOT THE DPR
--
-- `boq_progress_entries` already holds dated claims against a contract line, and a verified
-- daily progress report is the only thing that writes them. That is right for what it is:
-- the engineer signs, in the evening, for what the day built. It is the wrong instrument for
-- a bill. The tape comes out later — often days later, usually the week the Assistant
-- Engineer is due — and a claim recorded from memory at seven in the evening is not a
-- measurement. Forcing the two into one act means either the daily record is signed late or
-- the bill inherits a guess.
--
-- So this is a second ledger, deliberately. `boq_progress_entries` says what was REPORTED and
-- keeps driving the dashboards; `measurement_lines` say what was MEASURED and drive the bill.
-- The two will differ, and the difference is information rather than a fault — a site whose
-- reported figures run consistently above its measured ones is telling the office something.
-- Reconciling them is deferred on purpose: posting automatic adjustments between two ledgers
-- is the part that can quietly rewrite a signed figure, and it is not being built blind.
--
--
-- WHY THE SHEET IS THE UNIT AND NOT THE LINE
--
-- The engineer measures onto paper, because a phone is not a thing you hold in a half-built
-- stairwell with a tape in the other hand. The paper is a pre-printed sheet: one item, a
-- dozen ruled rows, a total he works out himself and writes at the bottom. `measurement_sheets`
-- is that piece of paper and `measurement_lines` are its rows, and the bill sweeps sheets
-- rather than rows because that is the object that exists, gets signed, and can be
-- photographed and produced two years later when somebody argues about 6.22 by 5.80.
--
-- `written_total` is the whole reason the paper is worth copying faithfully. He computed it
-- once by hand; the system computes it again from the rows. Two independent arrivals at one
-- number, and `ck_sheet_signed_agrees` will not let a sheet be signed while they disagree.
-- It catches a typing slip, a skipped row, a transposed 5.8 for 8.5, and his own arithmetic —
-- and it needs no cleverness at all, only subtraction.
--
--
-- WHY A BILL FREEZES, AND WHY A LINE CAN ONLY BE IN ONE
--
-- Two invariants carry the whole series, and both are structural rather than remembered.
--
-- `measurement_sheets.ra_bill_id` means a sheet belongs to exactly one bill. A quantity
-- cannot be paid twice because the row cannot hold two ids. That is the expensive error in a
-- billing system and it is closed by the schema, not by a check somebody has to keep writing.
--
-- `ra_bill_items` is a snapshot written when a bill is passed. Open the 2nd RA bill next year
-- and it shows what was actually paid, not what today's data would now produce — the same
-- discipline the daily report already follows when it freezes its figures at handover. Which
-- is also why "since previous bill" is a column nobody ever types again: it is this bill's
-- own sheets, and "up to date" is those plus every earlier bill's, both derived per call.
--
-- A correction after a bill has been passed is a fresh sheet with negative rows, dated when
-- it was found, swept into the next bill. Never an edit to a bill that has gone out. The
-- stock ledger, the wage ledger and the progress ledger all already say this.
--
--
-- WHY BILLING-ONLY PROJECTS GET A SITE THEY DID NOT ASK FOR
--
-- A contractor wants to bill a tender he is not otherwise running through the system: upload
-- the NIT, get the schedule, prepare bills, nothing else. `NitImportService` already produces
-- exactly that — a project with `boq_items` and no sites at all — so the mode flag is all
-- that was missing.
--
-- Except that `SiteAccessGuard` is the single choke point for authorisation and it takes a
-- site id. A project with no sites has nothing to scope on, and the alternative to giving it
-- one is a second access model beside the first, which is how a system ends up with a hole in
-- whichever of the two nobody remembers to update. So a BILLING_ONLY project is given one
-- site, named after the work, and the billing screens never show a picker. It is the same
-- argument `SiteService.create` already makes about stores: a store is not a decision
-- anybody was making, and an empty picker strands a lorry at the gate.
--
--
-- WHY THE RATE CHAIN IS STORED AND THE RATE IS NOT
--
-- A CPWD rate is not a number, it is an arithmetic: the published schedule rate, times a
-- coefficient factor, plus the station's cost index, less the percentage the contractor
-- tendered below. In the workbook it reads `=((D4*0.973))` then `=((E4+(E4*23%)))` then
-- `=((F4-(F4*11.11%)))`, forty times over, with the three constants retyped into every one.
-- `agreements` holds the three, once. A cost index revised by circular then reprices every
-- extra item instead of needing forty cells found and changed, and — the part that matters
-- when the bill is questioned — the derivation can be shown rather than asserted.

-- ---------------------------------------------------------------- billing-only projects

ALTER TABLE projects
    ADD COLUMN mode varchar(20) NOT NULL DEFAULT 'FULL';

ALTER TABLE projects
    ADD CONSTRAINT ck_projects_mode CHECK (mode IN ('FULL', 'BILLING_ONLY'));

COMMENT ON COLUMN projects.mode IS
    'FULL runs the site through the whole system. BILLING_ONLY was imported from a NIT to '
    'prepare bills and nothing else; it still gets one site, because authorisation is '
    'site-scoped. See the V46 header.';

-- ---------------------------------------------------------------- the published schedules

-- DSR 2023, DAR 2023 Vol. II, NDSR 2021. CPWD publishes these free as PDF and there is no
-- official machine-readable form, so a schedule arrives by import-and-review rather than by
-- upload: a rate nobody has eyeballed must never reach a bill. `source_attachment_id` keeps
-- the PDF beside the rows, so a disputed rate can be opened at its own page.
CREATE TABLE dsr_schedules (
    id                   uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id               uuid NOT NULL REFERENCES organisations(id),
    code                 varchar(40) NOT NULL,
    name                 varchar(200) NOT NULL,
    -- The year the rates are priced at, which is not always the year of publication.
    rate_year            integer,
    effective_from       date,
    source_attachment_id uuid REFERENCES attachments(id),
    -- Nothing may price a bill off a schedule still being checked.
    status               varchar(20) NOT NULL DEFAULT 'DRAFT',
    created_at           timestamptz NOT NULL DEFAULT now(),
    updated_at           timestamptz NOT NULL DEFAULT now(),
    created_by           uuid,
    updated_by           uuid,
    version              bigint NOT NULL DEFAULT 0,
    deleted_at           timestamptz,
    CONSTRAINT ck_dsr_schedule_status CHECK (status IN ('DRAFT', 'PUBLISHED', 'SUPERSEDED')),
    CONSTRAINT uq_dsr_schedule_code UNIQUE (org_id, code)
);

CREATE TABLE dsr_items (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id      uuid NOT NULL REFERENCES organisations(id),
    schedule_id uuid NOT NULL REFERENCES dsr_schedules(id) ON DELETE CASCADE,
    -- As the schedule prints it: 15.7.4, 26.40. Matched against an analysis-of-rate citation.
    code        varchar(40) NOT NULL,
    description text NOT NULL,
    unit_id     uuid REFERENCES units(id),
    -- What the unit column said before it was resolved, kept because a schedule prices work
    -- in units an organisation's master data has never heard of.
    unit_text   varchar(40),
    rate        numeric(18, 4) NOT NULL,
    chapter     varchar(120),
    page_no     integer,
    -- False when the importer had to guess. The review screen shows these first.
    confirmed   boolean NOT NULL DEFAULT false,
    created_at  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_dsr_item_code UNIQUE (schedule_id, code)
);

CREATE INDEX ix_dsr_items_schedule ON dsr_items (schedule_id, code);
CREATE INDEX ix_dsr_items_unconfirmed ON dsr_items (schedule_id) WHERE NOT confirmed;

-- ---------------------------------------------------------------- the agreement

-- One row per project, holding what the contract is called and the arithmetic that turns a
-- published rate into the rate this contract pays.
CREATE TABLE agreements (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id              uuid NOT NULL REFERENCES organisations(id),
    project_id          uuid NOT NULL REFERENCES projects(id),
    agreement_no        varchar(120),
    division            varchar(200),
    sub_division        varchar(200),
    -- The schedule extra items are priced off. Null until one is chosen.
    dsr_schedule_id     uuid REFERENCES dsr_schedules(id),
    -- The three that make a rate, in the order they apply. Defaults are the identity: a
    -- contract with no adjustment pays the schedule rate, which is a real case.
    dsr_coefficient     numeric(9, 6) NOT NULL DEFAULT 1,
    cost_index_pct      numeric(9, 4) NOT NULL DEFAULT 0,
    -- Signed. Negative is a tender below the schedule, which is the usual direction.
    tender_pct          numeric(9, 4) NOT NULL DEFAULT 0,
    -- The band inside which a quantity may move before it becomes a deviation.
    deviation_limit_pct numeric(9, 4) NOT NULL DEFAULT 100,
    -- The names and figures that print on every page of every bill for this tender and
    -- change only between tenders. They were retyped into forty-three sheets by hand, which
    -- is why the workbook says "3rd RA Bill" on the measurement pages, "4th RA Bill" on the
    -- abstract, and has a sheet named MB1st RA. Asked once, when the NIT is imported.
    contractor_name     varchar(200),
    -- "SH. Sarvesh Kumar, JE(C)" — the officer whose measurements the bill is based on, and
    -- who is named in the certificate on the CPWA-26 form.
    measured_by_name    varchar(200),
    measured_by_designation varchar(120),
    prepared_by_name    varchar(200),
    prepared_by_designation varchar(120),
    checked_by_name     varchar(200),
    checked_by_designation varchar(120),
    executive_engineer  varchar(200),
    -- The computerised measurement book number the front page certifies.
    cmb_no              varchar(60),
    -- Both print on the deviation statement, where the power limits are percentages of them.
    estimated_cost      numeric(18, 2),
    tendered_cost       numeric(18, 2),
    date_of_start       date,
    stipulated_completion date,
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now(),
    created_by          uuid,
    updated_by          uuid,
    version             bigint NOT NULL DEFAULT 0,
    CONSTRAINT uq_agreement_project UNIQUE (project_id)
);

COMMENT ON TABLE agreements IS
    'The contract''s rate arithmetic, stored as its three steps rather than as the answer, so '
    'a revised cost index reprices every derived rate. See the V46 header.';

-- ---------------------------------------------------------------- the running account bills

CREATE TABLE ra_bills (
    id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id           uuid NOT NULL REFERENCES organisations(id),
    project_id       uuid NOT NULL REFERENCES projects(id),
    serial_no        integer NOT NULL,
    -- "3rd RA Bill", "Final Bill". What the department will call it on paper.
    title            varchar(120) NOT NULL,
    -- Sheets measured on or before this date are swept in.
    cutoff_date      date NOT NULL,
    previous_bill_id uuid REFERENCES ra_bills(id),
    status           varchar(20) NOT NULL DEFAULT 'DRAFT',
    -- Written when the bill is passed, and never again.
    frozen_at        timestamptz,
    frozen_by        uuid,
    gross_work_done  numeric(18, 2),
    -- How many times it went back through the chain, in the shape expenses already use.
    revision         integer NOT NULL DEFAULT 0,
    remarks          text,
    created_at       timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz NOT NULL DEFAULT now(),
    created_by       uuid,
    updated_by       uuid,
    version          bigint NOT NULL DEFAULT 0,
    deleted_at       timestamptz,
    CONSTRAINT ck_ra_bill_status CHECK (status IN ('DRAFT', 'SUBMITTED', 'CHECKED', 'PASSED')),
    -- A passed bill is frozen by somebody at a time, or it is not passed.
    CONSTRAINT ck_ra_bill_frozen CHECK (
        (status = 'PASSED') = (frozen_at IS NOT NULL AND frozen_by IS NOT NULL)),
    CONSTRAINT uq_ra_bill_serial UNIQUE (project_id, serial_no)
);

CREATE INDEX ix_ra_bills_project ON ra_bills (project_id, serial_no DESC)
    WHERE deleted_at IS NULL;

-- The snapshot. Written at freeze from what the sheets then said, and read for ever after
-- instead of recomputing — because what was paid is a fact about the past.
CREATE TABLE ra_bill_items (
    id                 uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    ra_bill_id         uuid NOT NULL REFERENCES ra_bills(id) ON DELETE CASCADE,
    boq_item_id        uuid NOT NULL REFERENCES boq_items(id),
    item_number        varchar(40) NOT NULL,
    description        text NOT NULL,
    unit_id            uuid REFERENCES units(id),
    contract_quantity  numeric(18, 4) NOT NULL DEFAULT 0,
    qty_since_previous numeric(18, 4) NOT NULL DEFAULT 0,
    qty_to_date        numeric(18, 4) NOT NULL DEFAULT 0,
    rate               numeric(18, 4) NOT NULL DEFAULT 0,
    amount_to_date     numeric(18, 2) NOT NULL DEFAULT 0,
    amount_previous    numeric(18, 2) NOT NULL DEFAULT 0,
    amount_since       numeric(18, 2) NOT NULL DEFAULT 0,
    sort_order         integer NOT NULL DEFAULT 0,
    CONSTRAINT uq_ra_bill_item UNIQUE (ra_bill_id, boq_item_id)
);

CREATE INDEX ix_ra_bill_items_bill ON ra_bill_items (ra_bill_id, sort_order);

-- ---------------------------------------------------------------- the measurement sheets

CREATE TABLE measurement_sheets (
    id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id         uuid NOT NULL REFERENCES organisations(id),
    project_id     uuid NOT NULL REFERENCES projects(id),
    site_id        uuid NOT NULL REFERENCES sites(id),
    boq_item_id    uuid NOT NULL REFERENCES boq_items(id),
    -- Pre-printed on the paper and unique across the book, which is what stops the same
    -- sheet being entered twice by two people who each thought the other had not.
    sheet_serial   varchar(40),
    -- MEASUREMENT is nos x mult x L x B x H. BAR_BENDING is dia/nos/length and totals by
    -- diameter against tested unit weights; it is a different table on the paper too.
    sheet_type     varchar(20) NOT NULL DEFAULT 'MEASUREMENT',
    measured_on    date NOT NULL,
    measured_by    uuid,
    location_note  text,
    -- What he worked out by hand at the foot of the sheet. Nullable, because a sheet typed
    -- straight into the app never had a paper total to copy.
    written_total  numeric(18, 4),
    -- What the rows come to. Maintained by the service; the rows remain the truth.
    computed_total numeric(18, 4) NOT NULL DEFAULT 0,
    -- For the sections: total length, then times kg/m. Null for everything else.
    unit_weight    numeric(18, 4),
    status         varchar(20) NOT NULL DEFAULT 'DRAFT',
    signed_at      timestamptz,
    signed_by      uuid,
    -- Null while the sheet is measured but not yet billed. This is the double-payment guard.
    ra_bill_id     uuid REFERENCES ra_bills(id),
    -- The photograph of the paper. Evidence, not input — nothing reads it.
    attachment_id  uuid REFERENCES attachments(id),
    remarks        text,
    created_at     timestamptz NOT NULL DEFAULT now(),
    updated_at     timestamptz NOT NULL DEFAULT now(),
    created_by     uuid,
    updated_by     uuid,
    version        bigint NOT NULL DEFAULT 0,
    deleted_at     timestamptz,
    CONSTRAINT ck_sheet_type CHECK (sheet_type IN ('MEASUREMENT', 'BAR_BENDING')),
    CONSTRAINT ck_sheet_status CHECK (status IN ('DRAFT', 'SIGNED')),
    -- Signed by somebody at a time, or not signed.
    CONSTRAINT ck_sheet_signed CHECK (
        (status = 'SIGNED') = (signed_at IS NOT NULL AND signed_by IS NOT NULL)),
    -- The check that makes the paper worth copying: a signed sheet's two totals agree to the
    -- rounding the rows are kept at. A draft may disagree — that is what drafts are for.
    CONSTRAINT ck_sheet_signed_agrees CHECK (
        status <> 'SIGNED' OR written_total IS NULL
        OR abs(written_total - computed_total) <= 0.01),
    -- Only a signed sheet reaches a bill.
    CONSTRAINT ck_sheet_billed_is_signed CHECK (ra_bill_id IS NULL OR status = 'SIGNED')
);

-- One serial, one sheet. Partial, because a sheet typed without paper has no serial and any
-- number of those may exist.
CREATE UNIQUE INDEX uq_measurement_sheet_serial
    ON measurement_sheets (org_id, sheet_serial)
    WHERE sheet_serial IS NOT NULL AND deleted_at IS NULL;

-- The unbilled queue, which is the screen the engineer lives in.
CREATE INDEX ix_measurement_sheets_unbilled
    ON measurement_sheets (org_id, project_id, measured_on)
    WHERE ra_bill_id IS NULL AND deleted_at IS NULL;

CREATE INDEX ix_measurement_sheets_bill ON measurement_sheets (ra_bill_id)
    WHERE ra_bill_id IS NOT NULL;

CREATE INDEX ix_measurement_sheets_item ON measurement_sheets (boq_item_id, measured_on);

CREATE TABLE measurement_lines (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    sheet_id     uuid NOT NULL REFERENCES measurement_sheets(id) ON DELETE CASCADE,
    line_no      integer NOT NULL,
    -- "GD Room", "Wall at road bend". Free text, read by people and by nothing else.
    location     varchar(300),
    -- Nos is how many groups, mult how many in each: nine columns of one size is 1 x 9.
    nos          numeric(12, 3) NOT NULL DEFAULT 1,
    mult         numeric(12, 3) NOT NULL DEFAULT 1,
    -- Null is not zero. A linear item has no breadth, and multiplying by zero would say the
    -- work had none rather than that the question does not arise.
    length       numeric(12, 3),
    breadth      numeric(12, 3),
    height       numeric(12, 3),
    -- The product, rounded as the measurement book rounds it. Derived, stored, and re-derived
    -- on every write — kept because the bill reads thousands of these and re-multiplying them
    -- at read time buys nothing.
    contents     numeric(18, 4) NOT NULL DEFAULT 0,
    -- An opening deducted from plaster or brickwork. Prints indented under the additions and
    -- subtracts from the total.
    is_deduction boolean NOT NULL DEFAULT false,
    -- Bar bending only: the diameter this row's steel is, in mm.
    bar_dia      integer,
    created_at   timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_measurement_line_no UNIQUE (sheet_id, line_no)
);

CREATE INDEX ix_measurement_lines_sheet ON measurement_lines (sheet_id, line_no);

COMMENT ON TABLE measurement_lines IS
    'The ruled rows of one measurement sheet. Six shapes cover every line in a real bill: '
    'nos x mult x L x B x H and the four subsets of it, plus a length total taken against a '
    'tested unit weight. See the V46 header.';

-- ---------------------------------------------------------------- permissions
-- Five, and the split is between four different people.
--
-- Measuring is the engineer's, and is deliberately not `dpr:verify`: signing for what the day
-- built and measuring it with a tape a week later are different acts, done on different days,
-- sometimes by different people on the same site.
--
-- Preparing a bill is the office's arithmetic. Signing one is the act that sends a figure to
-- the department, and is separate from preparing it for the same reason `dpr:approve` is
-- separate from `dpr:verify`: an organisation that got the second by granting the first would
-- have a two-signature document carrying one signature.
--
-- Managing a schedule of rates is nobody's but an administrator's. A rate is the multiplier
-- on every quantity in the bill, so whoever may change one may change the total without
-- touching a measurement.
INSERT INTO permissions (code, module, description) VALUES
    ('billing:read',    'billing', 'View measurements and running account bills'),
    ('billing:measure', 'billing', 'Record and sign measurement sheets'),
    ('billing:prepare', 'billing', 'Prepare a running account bill from measured work'),
    ('billing:sign',    'billing', 'Pass a running account bill, freezing its figures'),
    ('dsr:manage',      'billing', 'Import and publish a schedule of rates');

-- V2 granted ADMIN everything by CROSS JOIN over the catalogue as it stood then; anything
-- added later has to be granted explicitly.
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
  FROM roles r
  JOIN permissions p ON p.code IN ('billing:read', 'billing:measure', 'billing:prepare',
                                   'billing:sign', 'dsr:manage')
 WHERE r.code = 'ADMIN' AND r.is_system
   AND NOT EXISTS (SELECT 1 FROM role_permissions rp
                    WHERE rp.role_id = r.id AND rp.permission_id = p.id);

-- The engineer measures and reads. He does not prepare the bill and does not pass it.
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
  FROM roles r
  JOIN permissions p ON p.code IN ('billing:read', 'billing:measure')
 WHERE r.code = 'ENGINEER' AND r.is_system
   AND NOT EXISTS (SELECT 1 FROM role_permissions rp
                    WHERE rp.role_id = r.id AND rp.permission_id = p.id);

-- The accountant prepares and reads, and is the one who will notice that a rate is wrong.
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
  FROM roles r
  JOIN permissions p ON p.code IN ('billing:read', 'billing:prepare')
 WHERE r.code = 'ACCOUNTANT' AND r.is_system
   AND NOT EXISTS (SELECT 1 FROM role_permissions rp
                    WHERE rp.role_id = r.id AND rp.permission_id = p.id);
