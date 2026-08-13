-- ==============================================================================================
-- V29 — Schedule F: the terms a plan is built on
--
-- V11 kept what the notice said about the bid. This keeps what it says about the *contract*:
-- when the work must reach which stage, when the clock starts, and how much work has to exist
-- before the department will pay for any of it.
--
-- The distinction is the point. Nothing here is needed to record a day's labour or a lorry of
-- cement, which is why none of it was extracted until now. All of it is needed before anybody
-- can answer "how much money do we need to start, and when does the department give it back" —
-- see docs/10-planning-and-execution-strategy.md.
--
-- Only what the reader actually produces is added. The standard form also offers a mobilisation
-- advance, a secured advance on material, and a price escalation clause; not one of the ten
-- notices in the corpus grants any of them, so those columns arrive with the extractors that
-- can fill them rather than sitting null in every row and being reported as a failed reading
-- forever.
-- ==============================================================================================

ALTER TABLE nit_documents
    -- completion_period keeps the printed words ("12 (Twelve) Months"). These two are the same
    -- span as a number the planner can work with. The unit is kept rather than folded into days
    -- because a CPWD month is a calendar month: twelve months from a 15th is the following
    -- year's 15th, where 360 days is five days short, and five days is the difference between
    -- meeting a milestone and having 1.25% of the contract withheld.
    ADD COLUMN completion_value      int,
    ADD COLUMN completion_unit       varchar(10),

    -- Days between the letter of acceptance and the date work is reckoned to start. Ten in all
    -- ten notices read so far, and the whole plan calendar hangs off it, so it is stored rather
    -- than assumed.
    ADD COLUMN start_reckoning_days  int,

    -- Clause 7A: no running account bill is paid until the labour licences and the EPFO, ESIC
    -- and BOCW registrations are filed. Not a deduction — a gate on being paid at all, which
    -- makes it the difference between a cash trough and a cash cliff. Null where the notice
    -- defers the answer elsewhere; that is a reading, not a gap.
    ADD COLUMN clause_7a_applicable  boolean,

    ADD CONSTRAINT ck_nit_completion_unit
        CHECK (completion_unit IS NULL OR completion_unit IN ('DAYS', 'MONTHS')),
    ADD CONSTRAINT ck_nit_completion_value
        CHECK (completion_value IS NULL OR completion_value > 0),
    ADD CONSTRAINT ck_nit_start_reckoning
        CHECK (start_reckoning_days IS NULL OR (start_reckoning_days >= 0
                                                AND start_reckoning_days <= 365));

-- ----------------------------------------------------------------------------------------------
-- The table of milestones.
--
-- A child table because the count varies from two to six across the corpus, and because the
-- descriptions are the valuable half: a physical milestone names the activities the department
-- expects finished, in the vocabulary boq_items.category already uses. That is the department's
-- own phasing of the work, and a planner that adopts it is planning the contract rather than
-- inventing a schedule and hoping it agrees.
-- ----------------------------------------------------------------------------------------------
CREATE TABLE nit_milestones (
    id                 uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    nit_document_id    uuid NOT NULL REFERENCES nit_documents(id),
    sequence_no        int  NOT NULL,
    description        text NOT NULL,

    -- As printed, unit preserved, for the same reason as completion_value above. One table
    -- mixes "15 Days" and "02 Month" in adjacent rows.
    time_allowed_value int,
    time_allowed_unit  varchar(10),

    -- Cumulative share of the tendered value this milestone represents. Nullable, and that is a
    -- real reading rather than a miss: one notice defines its final milestone entirely by
    -- handing over, as-built drawings and defect rectification, and states no percentage.
    financial_percent  numeric(6,3),

    -- Withheld on non-achievement, as a share of the accepted tendered value. Recoverable — it
    -- is released when a later milestone is met — so a plan models it as a timing event and
    -- never as a cost.
    withheld_percent   numeric(6,3),

    -- Whether the description names work rather than only a figure. The two kinds are phased
    -- completely differently and flattening them loses which is which.
    physical           boolean NOT NULL DEFAULT false,

    created_at         timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT uq_nit_milestone_seq UNIQUE (nit_document_id, sequence_no),
    CONSTRAINT ck_nit_milestone_seq CHECK (sequence_no > 0),
    CONSTRAINT ck_nit_milestone_unit
        CHECK (time_allowed_unit IS NULL OR time_allowed_unit IN ('DAYS', 'MONTHS')),
    CONSTRAINT ck_nit_milestone_time
        CHECK (time_allowed_value IS NULL OR time_allowed_value > 0),
    CONSTRAINT ck_nit_milestone_percents CHECK (
        (financial_percent IS NULL OR (financial_percent >= 0 AND financial_percent <= 100))
        AND (withheld_percent IS NULL OR (withheld_percent >= 0 AND withheld_percent <= 100)))
);
CREATE INDEX ix_nit_milestones_doc ON nit_milestones (nit_document_id, sequence_no);

-- ----------------------------------------------------------------------------------------------
-- Clause 7: gross work to be done before a running account bill may be raised.
--
-- A child table because every composite notice states it per work part — "Civil Works Rs. 21
-- Lakhs, Electrical Works Rs 05 Lakhs" — so civil and E&M bill on their own rhythms and one
-- column could not hold both. A non-composite notice states one figure and work_part is null,
-- meaning the whole contract.
--
-- This is the number that sets the depth of the cash trough: the contractor funds roughly a bill
-- and a half at all times, before the payment lag is counted at all.
-- ----------------------------------------------------------------------------------------------
CREATE TABLE nit_interim_minimums (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    nit_document_id uuid NOT NULL REFERENCES nit_documents(id),
    work_part       varchar(40),
    amount          numeric(18,2) NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT ck_nit_interim_amount CHECK (amount >= 0)
);
-- work_part is nullable and NULLs compare as distinct, so a table constraint would let the same
-- part be stored twice. Same reason uq_norm_scope in V1 is an index rather than a constraint.
CREATE UNIQUE INDEX uq_nit_interim_part
    ON nit_interim_minimums (nit_document_id, COALESCE(work_part, ''));
