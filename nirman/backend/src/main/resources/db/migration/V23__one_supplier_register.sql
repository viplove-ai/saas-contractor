-- Nirman — a supplier is a supplier, whether he sends cement or men.
--
-- There were two registers for the same thing. `vendors` held dealers, with a vendor_type
-- that already said SUBCONTRACTOR; `labour_contractors` held the men who bring gangs, in a
-- table with the same columns, the same shape and none of the account behind it. Onboarding
-- Karam Singh as a supplier and then finding you could not name him on a day's labour — or
-- naming him on the labour and then finding he has no account, no advance and no purchase
-- history — is the confusion two registers guarantees.
--
-- So the labour contractors move into `vendors` and the table goes. They keep their ids,
-- which is what makes this cheap: every row that pointed at a contractor already points at
-- the right vendor, and only the foreign key has to be told where to look.
--
-- The columns are renamed with it. `labour_contractor_id` pointing at `vendors` would be a
-- name that lies about what it references, and the next person to read it would go looking
-- for a table that is not there.

-- ---------------------------------------------------------------- the move
-- Same id, so nothing that references them has to be rewritten. The code is suffixed only
-- if a dealer already holds it — the two registers were never checked against each other,
-- so a collision is possible and must not fail the migration.
INSERT INTO vendors (id, org_id, code, name, vendor_type, contact_person, mobile, email,
                     address, gstin, pan, bank_account_no, bank_ifsc, credit_days,
                     opening_balance, is_active, deleted_at, created_at, updated_at,
                     created_by, updated_by)
SELECT lc.id, lc.org_id,
       CASE WHEN EXISTS (SELECT 1 FROM vendors v
                          WHERE v.org_id = lc.org_id AND v.code = lc.code)
            THEN left(lc.code, 36) || '-LAB'
            ELSE lc.code END,
       lc.name, 'SUBCONTRACTOR', lc.contact_person, lc.mobile, lc.email, lc.address,
       lc.gstin, lc.pan, lc.bank_account_no, lc.bank_ifsc, 0, 0,
       lc.is_active, lc.deleted_at, lc.created_at, lc.updated_at, lc.created_by, lc.updated_by
  FROM labour_contractors lc;

-- ---------------------------------------------------------------- point everything at it
-- Postgres carries a rename through the indexes and constraints that mention the column, so
-- uq_site_labour_counts_day and ix_workers_contractor follow along untouched.
ALTER TABLE workers DROP CONSTRAINT workers_labour_contractor_id_fkey;
ALTER TABLE workers RENAME COLUMN labour_contractor_id TO labour_supplier_id;
ALTER TABLE workers ADD CONSTRAINT workers_labour_supplier_id_fkey
    FOREIGN KEY (labour_supplier_id) REFERENCES vendors(id);

ALTER TABLE site_labour_counts DROP CONSTRAINT site_labour_counts_labour_contractor_id_fkey;
ALTER TABLE site_labour_counts RENAME COLUMN labour_contractor_id TO labour_supplier_id;
ALTER TABLE site_labour_counts ADD CONSTRAINT site_labour_counts_labour_supplier_id_fkey
    FOREIGN KEY (labour_supplier_id) REFERENCES vendors(id);

ALTER TABLE dpr_labour DROP CONSTRAINT dpr_labour_labour_contractor_id_fkey;
ALTER TABLE dpr_labour RENAME COLUMN labour_contractor_id TO labour_supplier_id;
ALTER TABLE dpr_labour ADD CONSTRAINT dpr_labour_labour_supplier_id_fkey
    FOREIGN KEY (labour_supplier_id) REFERENCES vendors(id);

ALTER TABLE material_issues DROP CONSTRAINT material_issues_issued_to_contractor_id_fkey;
ALTER TABLE material_issues RENAME COLUMN issued_to_contractor_id TO issued_to_supplier_id;
ALTER TABLE material_issues ADD CONSTRAINT material_issues_issued_to_supplier_id_fkey
    FOREIGN KEY (issued_to_supplier_id) REFERENCES vendors(id);

DROP TABLE labour_contractors;

COMMENT ON COLUMN site_labour_counts.labour_supplier_id IS
    'The registered supplier who brought this gang. Null means nobody said which — a real '
    'state on a site that uses one supplier and never bothers to name him.';

-- The supplier's own page asks "where has he worked", which is this column read the other
-- way round.
CREATE INDEX ix_site_labour_counts_supplier
    ON site_labour_counts (labour_supplier_id, count_date DESC)
    WHERE labour_supplier_id IS NOT NULL;

-- ---------------------------------------------------------------- was he there himself
-- One row per supplier per site per day, and not a column on the counts: a supplier who
-- sent masons, helpers and bar benders has three count rows, and "was he here" answered
-- three times is a question that can contradict itself.
--
-- Worth recording because it is the thing a site argues about later. A gang left to itself
-- for a week is how work goes wrong, and "his man was never here" is a claim that needs a
-- day-by-day answer rather than a memory.
CREATE TABLE site_labour_supplier_days (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id              uuid NOT NULL REFERENCES organisations(id),
    site_id             uuid NOT NULL REFERENCES sites(id),
    count_date          date NOT NULL,
    labour_supplier_id  uuid NOT NULL REFERENCES vendors(id),
    -- Whether the supplier himself, or the man he sends to run the gang, was on site.
    supplier_present    boolean NOT NULL DEFAULT false,
    -- Who that was, when it was not the supplier in person. A name here is what turns
    -- "somebody was here" into something anybody can check.
    representative_name varchar(150),
    remarks             varchar(300),
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now(),
    created_by          uuid,
    updated_by          uuid,
    version             bigint NOT NULL DEFAULT 0,

    CONSTRAINT uq_supplier_day UNIQUE (site_id, count_date, labour_supplier_id)
);

COMMENT ON TABLE site_labour_supplier_days IS
    'Which registered suppliers had men on a site on a day, and whether the supplier or his '
    'representative was there with them. No money follows from it — the supplier bills for '
    'the work; this is the site''s note of who turned up.';

CREATE INDEX ix_supplier_days_site ON site_labour_supplier_days (site_id, count_date);
CREATE INDEX ix_supplier_days_supplier
    ON site_labour_supplier_days (labour_supplier_id, count_date DESC);
