-- Nirman — Phase 5: the amount an approval chain was raised for, and the two guards that
-- make the cash side safe.
--
-- The guards follow V7's shape for stock: the service layer checks first and answers in a
-- sentence, and these are the backstop for the race the check cannot see and for the code
-- path that one day forgets to check at all.

-- 0. The amount the chain was raised against.
--
-- approval_rules routes by amount — an engineer clears anything, the administrator has to
-- see it above ₹25,000 — so deciding level 1 has to know what the record was worth in order
-- to work out whether there is a level 2 at all. Without this the engine would have to ask
-- the business module, which is exactly the coupling the generic engine exists to avoid
-- (docs/09 open question 2), or re-read a rule set that may have changed since.
--
-- Frozen at submission on purpose. A threshold raised next week must not retroactively
-- excuse a record already sitting in the administrator's queue.
ALTER TABLE approvals ADD COLUMN entity_amount numeric(18,2);

COMMENT ON COLUMN approvals.entity_amount IS
    'What the record was worth when the chain was raised. Null for records with no money on them.';

-- 1. Money does not leave against an expense nobody approved.
--
-- This is the control the whole two-level approval flow exists to enforce, and without it
-- the flow is advisory: a payment row could be written against a draft, and the expense
-- would show as paid while still sitting in somebody's queue. VOIDED is allowed because an
-- expense can be voided *after* it was approved and paid — the payment happened, and
-- pretending otherwise would mean the void could never be recorded.
ALTER TABLE expenses ADD CONSTRAINT ck_expense_paid_only_when_approved
    CHECK (paid_amount = 0 OR workflow_status IN ('APPROVED', 'VOIDED'));

-- 2. An expense is settled against one advance, not two.
--
-- The primary key on advance_settlement_expenses is (settlement_id, expense_id), which stops
-- the same expense appearing twice on one settlement and does nothing at all about it
-- appearing on two. That is the version that matters: the same ₹8,000 bill claimed against
-- two different site advances clears ₹16,000 of somebody's float, and the arithmetic in
-- site_advances.balance_amount is generated, so nothing downstream would notice.
CREATE UNIQUE INDEX uq_ase_expense_settled_once
    ON advance_settlement_expenses (expense_id);

COMMENT ON CONSTRAINT ck_expense_paid_only_when_approved ON expenses IS
    'No cash against an unapproved expense. See V8 header.';
COMMENT ON INDEX uq_ase_expense_settled_once IS
    'An expense settles one advance. Stops the same bill clearing two floats.';
