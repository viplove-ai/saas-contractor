-- Nirman — Phase 4, the two guards that make the stock ledger safe to write from a phone.
--
-- V1 gave stock_transactions its shape and stock_balances its non-negative check. What it
-- did not give is the constraint that makes a re-sent document harmless. The labour ledger
-- has that already — uq_wle_attendance_posting is why verifying attendance twice cannot pay
-- a worker twice — and stock needs the same guarantee for the same reason: the documents
-- are created on a phone with no signal and synced later, possibly more than once.
--
-- Both are unique *indexes* rather than table constraints because both are partial. The
-- service layer checks first and answers a repeat cleanly; these are the backstop for the
-- race the check cannot see, and for the code path that one day forgets to check at all.

-- 1. Opening stock is a one-time declaration per store and material.
--
-- It is the only ledger row nobody can derive: it says "this much was already here when we
-- started counting". Posted twice, the second one silently doubles a balance that no
-- receipt, issue or count can explain, and the moving average moves with it. docs/05 states
-- the rule on the endpoint; this is the rule with teeth.
CREATE UNIQUE INDEX uq_stx_opening_once
    ON stock_transactions (store_id, material_id)
    WHERE txn_type = 'OPENING_STOCK';

-- 2. A document line moves stock at most once in each direction.
--
-- source_line_id is the goods_receipt_items / material_issue_items / stock_transfer_items
-- row that caused the movement. Verifying a GRN twice, or a retried request arriving after
-- the first one committed, must not post its lines again.
--
-- txn_type is part of the key because a transfer line legitimately produces two rows: a
-- TRANSFER_OUT when it is dispatched and a TRANSFER_IN when it is received. Those are the
-- same line and different movements, and collapsing them would make a transfer impossible
-- to complete.
CREATE UNIQUE INDEX uq_stx_source_line
    ON stock_transactions (source_type, source_line_id, txn_type)
    WHERE source_line_id IS NOT NULL;

COMMENT ON INDEX uq_stx_opening_once IS
    'Opening stock is declared once per store and material. See V7 header.';
COMMENT ON INDEX uq_stx_source_line IS
    'A document line posts once per movement direction; makes an offline re-send harmless.';
