-- =============================================================================================
-- V49 — a bill settled out of the float in somebody's pocket
--
-- The float register has existed since V1 and could only be cleared one way: the holder
-- submits a settlement listing his bills, and the office approves it. That is the right shape
-- for a fortnight of pocket receipts and the wrong shape for the ordinary case, which is one
-- bill the supervisor already paid at the counter. Under the old flow that bill sat in the
-- "approved and still owed" queue looking like money the company owed the shopkeeper, when the
-- shopkeeper had been paid an hour after the lorry arrived and the person actually owed was the
-- supervisor.
--
-- So a float charge is recorded as what it is: a payment. The vendor was paid, `paid_amount`
-- moves, and the bill leaves the payable queue — the identity PaymentService exists to keep
-- (approved cost, cash paid, payable) stays true. `payments.site_advance_id` says which pocket
-- the cash came out of, which is the only thing that distinguishes it from a bank transfer.
--
-- Two things follow, and both are why this needs a migration rather than only code.
-- =============================================================================================

-- 1. Which float funded the payment. Null on every payment the office makes itself, which is
--    almost all of them.
ALTER TABLE payments ADD COLUMN site_advance_id uuid REFERENCES site_advances(id);

CREATE INDEX ix_payments_advance ON payments (site_advance_id)
    WHERE site_advance_id IS NOT NULL;

-- 2. A float may be overdrawn, and saying so is the point.
--
--    The old check capped adjusted + returned at the amount issued, which made the one thing a
--    holder does constantly — spending past his float and being owed the difference — the one
--    thing the register could not record. A supervisor holding 5,000 who buys 7,000 of steel is
--    owed 2,000 by the company, and refusing that row does not stop it happening; it sends it
--    into the notebook where nobody can read it back.
--
--    So the ceiling goes and the components keep their floors. `balance_amount` is generated
--    from the three and now carries a sign: positive is cash still in his pocket, negative is
--    money the company owes him. Nothing is netted across floats here — a holder's position is
--    summed per call by the register, because a stored per-person balance is a second version of
--    a truth these rows already tell.
--
--    The batch settlement path keeps its own ceiling in SiteAdvanceService: a holder claiming
--    more than he was given is a claim to check, not a fact to record. This relaxation is for
--    the office charging a bill it has just approved, which is the office asserting it.
ALTER TABLE site_advances DROP CONSTRAINT ck_advance_adjusted;
ALTER TABLE site_advances ADD CONSTRAINT ck_advance_adjusted
    CHECK (adjusted_amount >= 0 AND returned_amount >= 0);

-- 3. Overdrawn is its own state, not a settled one.
--
--    Without it a float charged past its amount would read as SETTLED, which would be both
--    wrong and invisible: the balances report lists open floats, so the one position the office
--    most needs to see — a man it owes money to — would be the one it dropped.
ALTER TABLE site_advances DROP CONSTRAINT ck_advance_status;
ALTER TABLE site_advances ADD CONSTRAINT ck_advance_status
    CHECK (settlement_status IN ('OPEN','PARTIALLY_SETTLED','SETTLED','OVERSPENT','CANCELLED'));

COMMENT ON COLUMN site_advances.balance_amount IS
    'Issued less spent less returned. Positive: cash still with the holder. Negative: he spent '
    'past his float and the company owes him the difference.';

COMMENT ON COLUMN payments.site_advance_id IS
    'The float this payment came out of, where the holder paid the vendor himself. Null when '
    'the office paid.';
