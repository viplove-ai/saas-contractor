-- ==============================================================================================
-- V11 — NIT documents
--
-- The tender a project was born from, kept whole.
--
-- boq_items already carries the priced lines, because those are what labour, material and cash
-- charge against. This table keeps everything else the notice said: the earnest money, the
-- submission and opening deadlines, the eligibility clause, the DSR year and the cost index the
-- rates were built on. None of it is needed to run the project day to day, which is exactly why
-- it would otherwise be retyped into a spreadsheet and lost. Six months on, "what did we
-- actually bid against?" has an answer only if the answer was stored.
--
-- V1's comment on projects.nit_number named the tender-intelligence merge as the home for this.
-- This is that table.
-- ==============================================================================================

CREATE TABLE nit_documents (
    id                              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id                          uuid NOT NULL REFERENCES organisations(id),
    project_id                      uuid NOT NULL REFERENCES projects(id),
    -- The source PDF in object storage. Nullable so the extraction survives the file being
    -- removed: what the tender said outlives our copy of the paper it said it on.
    attachment_id                   uuid REFERENCES attachments(id),

    file_name                       varchar(200) NOT NULL,
    page_count                      int NOT NULL,
    checksum_sha256                 varchar(64),
    -- Which build of the reader produced this. A later version reading the same file
    -- differently is a question worth being able to ask.
    parser_version                  varchar(20) NOT NULL,

    -- Wider than projects.nit_number (80) on purpose. Truncation belongs at the project
    -- column, where a shortened reference is still useful; here the notice is quoted whole.
    nit_no                          varchar(120),
    work_name                       text,
    estimated_cost                  numeric(18,2),
    civil_estimated_cost            numeric(18,2),
    electrical_estimated_cost       numeric(18,2),
    emd_amount                      numeric(18,2),
    completion_period               varchar(80),
    -- Wall-clock IST as printed on the notice, resolved at Asia/Kolkata. Stored as an instant
    -- like every other timestamp here, so a deadline sorts against everything else.
    submission_closing              timestamptz,
    bid_opening                     timestamptz,
    division                        varchar(120),
    location                        varchar(300),
    bid_type                        varchar(40),
    contractor_eligibility          text,
    similar_work_criteria           text,
    performance_guarantee_percent   numeric(6,3),
    security_deposit_percent        numeric(6,3),
    civil_dsr_year                  int,
    civil_cost_index_percent        numeric(7,3),
    electrical_dsr_year             int,
    electrical_cost_index_percent   numeric(7,3),

    boq_total                       numeric(18,2),
    extracted_item_count            int NOT NULL DEFAULT 0,
    -- One warning per line. A child table would be three joins for a list nobody queries by,
    -- and these are read as a block or not at all.
    warnings                        text,

    deleted_at                      timestamptz,
    created_at                      timestamptz NOT NULL DEFAULT now(),
    updated_at                      timestamptz NOT NULL DEFAULT now(),
    created_by                      uuid,
    updated_by                      uuid,
    version                         bigint NOT NULL DEFAULT 0,

    CONSTRAINT ck_nit_pages CHECK (page_count > 0),
    CONSTRAINT ck_nit_amounts CHECK (
        (estimated_cost IS NULL OR estimated_cost >= 0)
        AND (emd_amount  IS NULL OR emd_amount  >= 0)
        AND (boq_total   IS NULL OR boq_total   >= 0))
);

-- One live NIT per project. A corrigendum replaces the extraction rather than stacking on it,
-- so "the tender this project runs under" always has exactly one answer.
CREATE UNIQUE INDEX uq_nit_project ON nit_documents (project_id) WHERE deleted_at IS NULL;
CREATE INDEX ix_nit_org_nitno ON nit_documents (org_id, nit_no) WHERE deleted_at IS NULL;
