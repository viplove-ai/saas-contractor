-- Nirman — the field asking for a stock figure to be put right, without being able to put it
-- right itself.
--
-- ---------------------------------------------------------------- why this is not an edit
-- `stock_transactions` is append-only and nobody types a balance. That is not a preference:
-- the whole reason anybody believes a stock figure is that the movements behind it can be
-- read back, and a row that can be quietly rewritten in August is a row that proves nothing
-- about March. So a wrong figure is corrected the way it was made — by another movement —
-- and V7 already gave that movement a name and a lock: an ADJUSTMENT, `inventory:adjust`,
-- administrator only, because a role that can adjust a balance can hide a loss.
--
-- Which left the storekeeper holding the one thing the office has not got: he can see the
-- shed. Twelve bags were booked and eleven arrived; the issue that went out on Tuesday was
-- typed against the wrong material. Today he telephones somebody, and mostly he does not,
-- and the balance stays wrong until a physical count finds it months later.
--
-- This table is the sentence he cannot otherwise say. It holds a *request* for an adjustment,
-- not an adjustment: nothing in it reaches the ledger until an administrator accepts it, and
-- accepting it posts the ordinary ADJUSTMENT row through the ordinary service, with the
-- ordinary period lock and the ordinary refusal to drive a balance below zero. The request is
-- the paperwork; the ledger stays exactly as strict as it was.
--
-- A rejected request stays, and carries the reason. Somebody counted a shed and was told he
-- was wrong; a row that disappears is a man who counts the shed and says nothing next time.

CREATE TABLE stock_correction_requests (
    id             uuid PRIMARY KEY,
    org_id         uuid NOT NULL REFERENCES organisations(id),
    site_id        uuid NOT NULL REFERENCES sites(id),
    store_id       uuid NOT NULL REFERENCES stores(id),
    material_id    uuid NOT NULL REFERENCES materials(id),
    -- The unit he counted in, kept beside the figure he typed. The ledger holds base units
    -- and the conversion happens on the way in; storing what he actually said is what lets
    -- the office read "two bags short" rather than "-100 kg" and recognise its own store.
    unit_id        uuid NOT NULL REFERENCES units(id),

    -- Signed, exactly like AdjustmentRequest.quantityDelta: negative writes stock off,
    -- positive writes it on. A correction of nothing is refused in the service rather than
    -- here, so the message can say why.
    quantity_delta numeric(14,4) NOT NULL,

    -- The day the correction belongs to, not the day it was typed. A count done on the 31st
    -- and sent on the 2nd is March's, and the period lock is checked against this.
    correction_date date NOT NULL,

    -- Never nullable. A stock movement with no reason behind it is indistinguishable from
    -- stock walking out of the gate, and this one is being asked for by the person who would
    -- benefit from that being unreadable.
    reason         text NOT NULL,

    status         varchar(20) NOT NULL DEFAULT 'PENDING',
    -- The ADJUSTMENT this became, once somebody accepted it. The join that answers "what did
    -- the office actually do about my count", and the reason accepting twice is impossible.
    posted_txn_id  uuid REFERENCES stock_transactions(id),
    decided_at     timestamptz,
    decided_by     uuid REFERENCES users(id),
    decision_remarks varchar(500),

    created_at     timestamptz NOT NULL DEFAULT now(),
    updated_at     timestamptz NOT NULL DEFAULT now(),
    created_by     uuid,
    updated_by     uuid,
    version        bigint NOT NULL DEFAULT 0,

    CONSTRAINT ck_stock_correction_status
        CHECK (status IN ('PENDING', 'ACCEPTED', 'REJECTED')),
    -- A decision is a decision by somebody at a time, or it is not one.
    CONSTRAINT ck_stock_correction_decision_is_whole
        CHECK (status = 'PENDING' OR decided_at IS NOT NULL),
    -- An accepted request that posted nothing would be a correction everybody believes
    -- happened and the ledger has never heard of. This is the write that never goes through
    -- the service, refused at the table.
    CONSTRAINT ck_stock_correction_accepted_posted
        CHECK (status <> 'ACCEPTED' OR posted_txn_id IS NOT NULL)
);

COMMENT ON TABLE stock_correction_requests IS
    'A request from the field for a stock figure to be corrected. Holds no stock itself: '
    'accepting one posts an ordinary ADJUSTMENT through StockAdjustmentService, so the '
    'append-only ledger and its period lock stay exactly as strict as they were.';

COMMENT ON COLUMN stock_correction_requests.quantity_delta IS
    'Signed, in unit_id: negative writes stock off, positive writes it on.';

-- The storekeeper's screen: what this store has asked for, newest first.
CREATE INDEX ix_stock_correction_store
    ON stock_correction_requests (store_id, status, created_at DESC);

-- The administrator's queue, which is one query across every site he runs.
CREATE INDEX ix_stock_correction_pending
    ON stock_correction_requests (org_id, created_at DESC) WHERE status = 'PENDING';

-- ---------------------------------------------------------------- permissions
-- One new permission, not two. Asking is a new act and needs its own; deciding is not — an
-- accepted request posts an ADJUSTMENT, and `inventory:adjust` is already exactly the
-- question "may this person move a balance without a document behind it". Minting a second
-- permission for it would let an organisation grant the approval and not the posting, which
-- is a state the service could not honour.
INSERT INTO permissions (code, module, description) VALUES
    ('inventory:correct', 'inventory',
     'Ask for a stock figure to be corrected. Posts nothing; an administrator decides.');

-- V2 granted ADMIN everything by CROSS JOIN over the catalogue as it stood then; anything
-- added later has to be granted explicitly.
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
  FROM roles r
  JOIN permissions p ON p.code = 'inventory:correct'
 WHERE r.code IN ('ADMIN', 'ENGINEER', 'SUPERVISOR') AND r.is_system
   AND NOT EXISTS (SELECT 1 FROM role_permissions rp
                    WHERE rp.role_id = r.id AND rp.permission_id = p.id);

-- The accountant reads the stores and is asked to explain their value at month end, so he
-- sees the queue through inventory:read like everybody else — but he does not raise these.
-- The man who can see the shed is the man who should be typing the count.
