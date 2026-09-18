-- Nirman — the payday.
--
-- The worker ledger has carried WAGE_EARNED and ADVANCE since V1 and has a PAYMENT entry type
-- with nothing that ever wrote one: the office paid the men off the wage summary and the
-- system never heard. Two things followed. The ledger's net payable only ever grew, so the
-- settlement screen answered "what is he owed" with everything he had ever earned less
-- everything he had ever drawn. And worker_advances.recovered_amount, with its balance and
-- its status, stayed at zero for ever — an advance was netted out of the man's wages by the
-- ledger the day it was approved and never marked recovered, because nothing recorded the
-- day the wages it stood against were handed over.
--
-- worker_payments is that day. One row per man per hand-over, with the ledger PAYMENT entry
-- pointing at it (source_type PAYMENT, source_id this row). Recording one does two things in
-- one transaction: posts the payment to the ledger, and marks every open recoverable advance
-- the man's wages have by then covered as recovered, oldest first — the same arithmetic the
-- field sheet does at the foot of the column, Total Amount − Advance = Balance Payment.
-- advances_recovered on the row is what that payday closed, kept for the record and not for
-- any balance: the balance is summed off the ledger as it always was.
--
-- Not an expense. Money handed to a worker is settlement of wages already counted as cost
-- at verification (docs/09, the double-counting rule); a bill that appeared in the approval
-- queue because a man was paid is one nobody typed. The office books the cash under an
-- is_labour_payment head as it does today, if it books it at all.
--
-- No new permission. Recording that money left the firm to settle what it owes is
-- payment:record, the accountant's, and a wage is the oldest thing the firm owes.

CREATE TABLE worker_payments (
    id                  uuid PRIMARY KEY,            -- client-generated, offline-safe
    org_id              uuid NOT NULL REFERENCES organisations(id),
    project_id          uuid NOT NULL REFERENCES projects(id),
    site_id             uuid NOT NULL REFERENCES sites(id),
    worker_id           uuid NOT NULL REFERENCES workers(id),
    payment_number      varchar(50) NOT NULL,
    payment_date        date NOT NULL,
    amount              numeric(18,2) NOT NULL,
    payment_mode        varchar(20) NOT NULL DEFAULT 'CASH',
    reference_number    varchar(100),
    -- What this payday closed of the man's open advances. A record of the act, never a balance.
    advances_recovered  numeric(18,2) NOT NULL DEFAULT 0,
    remarks             text,
    source              varchar(15) NOT NULL DEFAULT 'ONLINE',
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now(),
    created_by          uuid,
    updated_by          uuid,
    version             bigint NOT NULL DEFAULT 0,
    CONSTRAINT uq_worker_payment_number UNIQUE (org_id, payment_number),
    CONSTRAINT ck_worker_payment_amount CHECK (amount > 0),
    CONSTRAINT ck_worker_payment_recovered CHECK (advances_recovered >= 0),
    CONSTRAINT ck_worker_payment_mode CHECK (payment_mode IN ('CASH','BANK','UPI','CHEQUE')),
    CONSTRAINT fk_worker_payment_site_project FOREIGN KEY (site_id, project_id) REFERENCES sites (id, project_id)
);
CREATE INDEX ix_worker_payments_worker ON worker_payments (worker_id, payment_date DESC);
CREATE INDEX ix_worker_payments_site   ON worker_payments (site_id, payment_date DESC);

COMMENT ON TABLE worker_payments IS
    'Wages handed to a worker against his ledger balance. Posts PAYMENT to worker_ledger_entries and recovers his open advances. See V62 header.';
