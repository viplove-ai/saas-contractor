-- Nirman — the proof that cash actually went out.
--
-- A payment has carried a reference number since V1 — a cheque number, a UPI reference, an
-- NEFT UTR — and that column says what it always said: whatever proves it left the account.
-- What it could not carry was the thing the accountant actually has in his hand. A UPI
-- payment on a site phone is a screenshot; a cash payment against a supplier's bill is a
-- receipt with his signature on it; an NEFT is a bank slip. None of those are a string, and
-- until now the answer was to type twelve digits off the screenshot and keep the screenshot
-- somewhere else — which is to say, nowhere the system could ever show it again.
--
-- The consequence was not abstract. A supplier disputes a payment nine months later, the
-- register says ₹47,000 against UTR N123456789012, and the only way to answer him is to find
-- the man who made the payment and hope his phone has not been wiped. The bill has had a
-- photograph on it since the beginning (`expense_attachments`); the payment settling that
-- bill had none, so the half of the transaction somebody argues about was the half with no
-- evidence behind it.
--
-- The shape is `expense_attachments` exactly, and deliberately so: the file itself lives in
-- `attachments` and this table is only the link, which is what lets one payment carry a
-- screenshot and a receipt without either of them knowing about the other. `doc_type` names
-- what the picture is of, because "RECEIPT" and "SCREENSHOT" are different kinds of evidence
-- and a reader deciding whether to trust one wants to be told which he is looking at.
--
-- No new permission. Attaching proof to a payment is part of recording the payment, and
-- `payment:record` is already exactly the question of who may do that — a second permission
-- would let an organisation grant the recording to somebody who cannot evidence it, which is
-- the wrong half to be able to hand out on its own.

CREATE TABLE payment_attachments (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    payment_id    uuid NOT NULL REFERENCES payments(id) ON DELETE CASCADE,
    attachment_id uuid NOT NULL REFERENCES attachments(id),
    -- RECEIPT | SCREENSHOT | BANK_SLIP | OTHER. What the picture is of, not what it is.
    doc_type      varchar(30) NOT NULL DEFAULT 'RECEIPT',
    CONSTRAINT uq_payment_attachment UNIQUE (payment_id, attachment_id)
);

CREATE INDEX ix_payment_attachments_payment ON payment_attachments (payment_id);

COMMENT ON TABLE payment_attachments IS
    'Proof that a payment went out — the UPI screenshot, the signed receipt, the bank slip. '
    'The link only; the file is in attachments. See the V45 header.';
