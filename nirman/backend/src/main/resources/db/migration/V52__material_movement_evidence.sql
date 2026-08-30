-- Nirman — the photograph of what actually arrived, and of the paper that came with it.
--
-- A goods receipt is the one document in the system where the thing and the paper are both
-- standing in front of one man for five minutes and never again. The lorry tips its load and
-- leaves; the challan goes into a pocket; the invoice reaches the office days later, if it
-- reaches it at all. Everything downstream — the moving average, the month's consumption, a
-- supplier's account, a dispute nine months later about whether forty bags or thirty-two came
-- off that vehicle — rests on what he typed in those five minutes, and until now nothing behind
-- it could be looked at.
--
-- The same argument the expense had (V40) and the payment had (V45), on the two rows that move
-- stock. Two pictures on a delivery because they answer two different questions and one cannot
-- stand for the other: the material says what came off the lorry, and the invoice or challan
-- says what the supplier claims he sent. When those two disagree, the disagreement is the whole
-- point, and a single photograph would have quietly settled it in whichever direction the
-- photographer pointed the camera. An issue takes one: there is no third party and no paper —
-- what left the store for the work face is a fact about the store, and the picture is what
-- stops "6 bags of cement" being a number somebody rounded on the way to the office.
--
-- ---------------------------------------------------------------- the shape
-- `expense_attachments` and `payment_attachments` exactly: the file itself lives in
-- `attachments` and this is only the link, which is what lets one delivery carry a picture of
-- the load, a picture of the invoice and a third of the damaged corner without any of them
-- knowing about the others. `doc_type` names what the picture is of, because a reader deciding
-- whether the delivery matches the bill needs to be told which of the two he is looking at.
--
-- ---------------------------------------------------------------- and not a check constraint
-- The requirement is enforced in `MaterialEvidencePolicy`, for the reason V40 moved the
-- expense's rule out of the database: what has to be true spans two tables, and a row-level
-- check can see one. It refuses at creation with a sentence a person can act on, which is the
-- only moment it can be satisfied — the man is still at the gate.
--
-- ---------------------------------------------------------------- no new permission
-- Photographing the delivery is part of booking it. `inventory:receive` and `inventory:issue`
-- are already exactly the question of who may do that, and a second permission would let an
-- organisation grant the booking to somebody who cannot evidence it — the wrong half to be
-- able to hand out on its own.

CREATE TABLE goods_receipt_attachments (
    id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    goods_receipt_id uuid NOT NULL REFERENCES goods_receipts(id) ON DELETE CASCADE,
    attachment_id    uuid NOT NULL REFERENCES attachments(id),
    -- MATERIAL | INVOICE | OTHER. What the picture is of, not what it is.
    doc_type         varchar(30) NOT NULL DEFAULT 'MATERIAL',
    CONSTRAINT uq_goods_receipt_attachment UNIQUE (goods_receipt_id, attachment_id),
    CONSTRAINT ck_goods_receipt_attachment_type
        CHECK (doc_type IN ('MATERIAL', 'INVOICE', 'OTHER'))
);

CREATE INDEX ix_goods_receipt_attachments_receipt
    ON goods_receipt_attachments (goods_receipt_id);

COMMENT ON TABLE goods_receipt_attachments IS
    'What came off the lorry and the paper that came with it. The link only; the file is in '
    'attachments. Both are required at creation by MaterialEvidencePolicy. See the V52 header.';

CREATE TABLE material_issue_attachments (
    id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    material_issue_id uuid NOT NULL REFERENCES material_issues(id) ON DELETE CASCADE,
    attachment_id     uuid NOT NULL REFERENCES attachments(id),
    -- MATERIAL | OTHER. There is no third party on an issue, and so no bill.
    doc_type          varchar(30) NOT NULL DEFAULT 'MATERIAL',
    CONSTRAINT uq_material_issue_attachment UNIQUE (material_issue_id, attachment_id),
    CONSTRAINT ck_material_issue_attachment_type CHECK (doc_type IN ('MATERIAL', 'OTHER'))
);

CREATE INDEX ix_material_issue_attachments_issue
    ON material_issue_attachments (material_issue_id);

COMMENT ON TABLE material_issue_attachments IS
    'What left the store for the work face. The link only; the file is in attachments. One is '
    'required at creation by MaterialEvidencePolicy. See the V52 header.';
