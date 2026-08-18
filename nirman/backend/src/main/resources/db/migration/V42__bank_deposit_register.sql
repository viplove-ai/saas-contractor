-- Nirman — the fixed deposits themselves, as a register of their own.
--
-- The treasury has known since V38 what each contract has lodged with a department, and each
-- of those rows types its own FDR number, bank, branch and maturity date. What nobody could
-- ask the system was the question the office actually asks the bank: how many fixed deposits
-- do we hold, for how much, and which of them matures next. The deposits were only ever
-- visible one contract at a time, so the answer lived in a folder and a memory.
--
-- Three things follow from making the instrument its own row rather than a set of columns on
-- whatever contract it happens to be pledged against.
--
-- **It exists before it is pledged, and after.** A contractor buys an FDR to bid; the bid is
-- refused; the FDR is still his money and still matures in October. There was no row for it
-- at all — V38's register starts at a contract — so it was invisible until it was committed to
-- something. Now it is entered when it is bought and closed when the bank pays it out, and
-- what happens in between is a series of pledges.
--
-- **The same one goes to the next tender.** V38 already saw this and answered it with
-- `redeployed_to_project_id`, a pointer from the released deposit to the contract that reused
-- it. That records the fact but cannot carry it: the second contract's row is a new row typing
-- the same FDR number again, and the two are only connected by the pointer nobody joins on. A
-- pledge that names the instrument makes the FDR's whole history one thread.
--
-- **A photograph belongs to the FDR, not to a pledge of it.** The paper is the bank's
-- certificate and it does not change when the deposit moves to another contract, so the
-- photographs hang off this table and follow it about.
--
-- ---------------------------------------------------------------- what is not stored
-- Whether a deposit is *pledged* is deliberately not a column. It is true exactly when some
-- live security row points at this deposit, which is a fact the link already carries; storing
-- it as well would be the second version of the truth docs/09 keeps chasing out — and the one
-- that goes stale is always the copy, so the register would show an FDR as committed months
-- after the department returned it. The status column holds only what cannot be derived: the
-- company holds it, or the bank has paid it out and it is gone.

CREATE TABLE bank_deposits (
    id              uuid PRIMARY KEY,               -- client-generated; see BaseEntity
    org_id          uuid        NOT NULL REFERENCES organisations(id),

    -- The number on the certificate. Unique per organisation because it is what somebody says
    -- out loud on the telephone to the bank, and two rows for one certificate would split the
    -- company's own holdings into two figures that never add up.
    deposit_number  varchar(80) NOT NULL,
    bank_name       varchar(160) NOT NULL,
    branch          varchar(160),

    amount          numeric(18,2) NOT NULL,
    issued_on       date        NOT NULL,

    -- Nullable: a deposit whose maturity nobody has read off the certificate yet is a real
    -- deposit, and a guessed date would put it in or out of the renewal list wrongly.
    maturity_on     date,
    interest_rate   numeric(6,3),

    status          varchar(20) NOT NULL DEFAULT 'HELD',
    closed_on       date,
    closed_reason   varchar(500),
    notes           text,

    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    created_by      uuid,
    updated_by      uuid,
    version         bigint      NOT NULL DEFAULT 0,

    CONSTRAINT uq_bank_deposit_number UNIQUE (org_id, deposit_number),
    CONSTRAINT ck_bank_deposit_status CHECK (status IN ('HELD', 'CLOSED')),
    CONSTRAINT ck_bank_deposit_amount CHECK (amount > 0),
    CONSTRAINT ck_bank_deposit_rate CHECK (interest_rate IS NULL OR interest_rate >= 0),
    -- A closed deposit is closed on a day, and a day it closed means it is closed. One without
    -- the other is a row nobody can read.
    CONSTRAINT ck_bank_deposit_closed
        CHECK ((status = 'CLOSED') = (closed_on IS NOT NULL)),
    CONSTRAINT ck_bank_deposit_maturity
        CHECK (maturity_on IS NULL OR maturity_on >= issued_on)
);

-- The register's own order: what is still held, soonest to mature first. That is the question
-- the screen opens on, and the one a renewal is missed for want of.
CREATE INDEX ix_bank_deposits_open ON bank_deposits (org_id, maturity_on)
    WHERE status = 'HELD';

COMMENT ON TABLE bank_deposits IS
    'Fixed deposits the company holds, whatever they are pledged against. Whether one is '
    'pledged is derived from project_securities.bank_deposit_id, never stored here.';

-- ---------------------------------------------------------------- the certificate itself
-- Modelled on dpr_photos: several pictures, each an ordinary attachment, joined here so the
-- files can be reordered and captioned without touching the attachment rows.
CREATE TABLE bank_deposit_photos (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    deposit_id    uuid NOT NULL REFERENCES bank_deposits(id) ON DELETE CASCADE,
    attachment_id uuid NOT NULL REFERENCES attachments(id),
    caption       varchar(300),
    sort_order    int  NOT NULL DEFAULT 0,
    CONSTRAINT uq_bank_deposit_photo UNIQUE (deposit_id, attachment_id)
);

-- ---------------------------------------------------------------- the pledge
-- Nullable, and it stays nullable. A retention was never an instrument at all — it is money
-- deducted from bills that never reached the contractor — and a deposit lodged in cash or by
-- bank guarantee has no FDR behind it either. Every row written before this migration also has
-- none, and they keep the bank details they were typed with rather than being guessed into
-- certificates nobody can produce.
ALTER TABLE project_securities
    ADD COLUMN bank_deposit_id uuid REFERENCES bank_deposits(id);

CREATE INDEX ix_project_securities_deposit ON project_securities (bank_deposit_id)
    WHERE bank_deposit_id IS NOT NULL;

COMMENT ON COLUMN project_securities.bank_deposit_id IS
    'The fixed deposit pledged against this security, where one is. Its number, bank and '
    'maturity are the deposit''s to state; the columns here are what earlier rows were typed '
    'with and what a non-FDR instrument still carries.';

-- ---------------------------------------------------------------- and no new permission
-- Buying a fixed deposit, photographing the certificate and closing it when the bank pays out
-- are the same act by the same person as lodging and releasing a deposit against a contract:
-- the office moving the company's money. V38 minted `security:read` and `security:write` for
-- exactly that, and an organisation able to grant one and withhold the other would have a
-- register somebody can pledge from and not enter into.
