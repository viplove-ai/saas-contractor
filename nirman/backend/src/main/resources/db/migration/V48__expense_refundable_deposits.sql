-- ==============================================================================================
-- V48 — the part of a bill that is coming back
--
-- An electricity connection costs ₹18,000 and ₹12,000 of it is a security deposit against the
-- meter. A hired mixer wants ₹25,000 down before it leaves the yard. A cylinder, a barricade, a
-- transformer, a temporary water connection: the money goes out on one bill, and part of it is
-- not spending at all — it is the company's money placed with somebody, and it comes back when
-- the meter is surrendered.
--
-- The expense screen had one box for the amount, so the whole ₹18,000 was booked as the site's
-- cost. Two things went wrong with that, and they are the same two things docs/09 chased out of
-- material and wages:
--
--   * The job is overstated. ₹12,000 that is still ours is reported as spent on the work, and
--     nothing ever reports it back when it returns — the refund arrives months later, usually
--     after the site is closed, and lands nowhere.
--   * Nobody is counting. A contractor running six sites has a few lakh sitting in deposits
--     with the electricity board and three plant hirers, and the only record of it was the
--     memory of whoever paid. V38 solved exactly this problem for the money lodged with the
--     *department*; this is the same problem with the money lodged with everybody else.
--
-- So: `refundable_amount` carves that part out of the total. The bill is unchanged — the vendor
-- is paid the whole ₹18,000 and `payments` still reconciles against `total_amount` — but the
-- deposit is out of cost incurred from the day it is booked, and it stands in the register
-- until somebody says what became of it.
--
-- ---------------------------------------------------------- one column, not a second expense
-- The alternative was to book the deposit as its own expense under a deposit head. It was
-- rejected because the office holds one bill: splitting it into two rows means two bill
-- numbers for one piece of paper, the duplicate check firing on the pair, and a supervisor
-- being asked at the gate to do accounting arithmetic. The bill is one row and one of its
-- columns says how much of it is not spending.
--
-- ---------------------------------------------------------- settling is a register, not a flag
-- `expense_refunds` is what became of it: money received back, or a write-off when it will not
-- come. Rows rather than a status column for the reason `payments` is rows — a deposit comes
-- back in parts often enough (the board adjusts half against a final bill and refunds the rest)
-- that a single date-and-amount would be a lie about a normal case. `refunded_amount` and
-- `written_off_amount` on the expense are running totals this register writes and nothing else
-- does, exactly as `paid_amount` is.
--
-- ---------------------------------------------------------- a write-off does not become cost here
-- A deposit that will never come back *is* a loss, and the loss belongs to the day somebody
-- decided it rather than to the day the connection was taken — history does not move. So a
-- write-off closes the row in this register and says so out loud, and booking the loss is a
-- fresh expense under a loss head at the date of the decision. The same shape as a correction
-- after an RA bill is passed: a new document, not a rewritten one. Writing it silently back
-- into the original expense's period would change a figure the office has already reported.
-- ==============================================================================================

ALTER TABLE expenses
    -- How much of total_amount is a deposit rather than spending. Zero on the overwhelming
    -- majority of rows, which is why it defaults to zero and no screen shows the box until
    -- somebody ticks it.
    ADD COLUMN refundable_amount  numeric(18,2) NOT NULL DEFAULT 0,

    -- When it is expected back, if anybody knows. Usually nobody does — "when we return the
    -- gadgets" has no date — and a guessed date is worse than a blank, because the register
    -- then sorts a deposit nobody is chasing above one that has been due for a year.
    ADD COLUMN refund_expected_on date,

    -- Running totals, written by the refund register and by nothing else. Cached on the
    -- expense for the same reason paid_amount is: "what is still outstanding" is asked of
    -- every row of the deposits register on every read.
    ADD COLUMN refunded_amount    numeric(18,2) NOT NULL DEFAULT 0,
    ADD COLUMN written_off_amount numeric(18,2) NOT NULL DEFAULT 0;

ALTER TABLE expenses
    -- A deposit is part of the bill, never more than it. The upper bound is what stops
    -- "₹12,000 refundable" on a ₹4,500 bill reporting negative cost for the day.
    ADD CONSTRAINT ck_expense_refundable_within_total
        CHECK (refundable_amount >= 0 AND refundable_amount <= total_amount),

    -- Nothing can be settled that was never placed, and no deposit is settled twice.
    ADD CONSTRAINT ck_expense_refund_settled_within_deposit
        CHECK (refunded_amount >= 0
               AND written_off_amount >= 0
               AND refunded_amount + written_off_amount <= refundable_amount),

    -- The same guard V8 put on paid_amount, for the same reason: the flow is advisory, and a
    -- refund row written against a draft would show a deposit coming back on an expense
    -- nobody has approved. VOIDED is allowed because an expense can be voided after a
    -- deposit was already settled, and refusing that would make the void unrecordable.
    ADD CONSTRAINT ck_expense_refund_only_when_approved
        CHECK ((refunded_amount = 0 AND written_off_amount = 0)
               OR workflow_status IN ('APPROVED', 'VOIDED'));

COMMENT ON COLUMN expenses.refundable_amount IS
    'The part of total_amount that is a deposit and not spending — a meter security, plant '
    'hire deposit, cylinder deposit. Out of cost incurred from the day it is booked; see the '
    'V48 header.';
COMMENT ON COLUMN expenses.refunded_amount IS
    'Running total of deposit money actually received back. Written by ExpenseRefundService '
    'and by nothing else, exactly as paid_amount is written by PaymentService.';

-- ---------------------------------------------------------------- what became of the deposit
CREATE TABLE expense_refunds (
    id                 uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id             uuid NOT NULL REFERENCES organisations(id),
    expense_id         uuid NOT NULL REFERENCES expenses(id),

    -- RECEIVED: the money came back. WRITTEN_OFF: it is not coming, and the row says why.
    -- Two outcomes and not three: "still waiting" is the absence of a row, and giving it a
    -- spelling of its own would let one deposit be both waiting and settled.
    outcome            varchar(20) NOT NULL,

    settled_on         date NOT NULL,
    amount             numeric(18,2) NOT NULL,

    -- How it came back, and the proof: a cheque number, a NEFT UTR, or the credit note the
    -- board adjusted it against. Free on a write-off, where there is nothing to reference.
    payment_mode       varchar(20),
    reference_number   varchar(80),

    -- Required on a write-off. A deposit that quietly disappears from the register is a
    -- deposit nobody can be asked about six months later.
    reason             varchar(500),
    remarks            text,

    created_at         timestamptz NOT NULL DEFAULT now(),
    updated_at         timestamptz NOT NULL DEFAULT now(),
    created_by         uuid,
    updated_by         uuid,
    version            bigint NOT NULL DEFAULT 0,

    CONSTRAINT ck_expense_refund_outcome CHECK (outcome IN ('RECEIVED', 'WRITTEN_OFF')),
    CONSTRAINT ck_expense_refund_amount CHECK (amount > 0),
    CONSTRAINT ck_expense_refund_writeoff_has_reason
        CHECK (outcome <> 'WRITTEN_OFF' OR (reason IS NOT NULL AND length(trim(reason)) > 0))
);

CREATE INDEX ix_expense_refunds_expense ON expense_refunds (expense_id, settled_on);
CREATE INDEX ix_expense_refunds_org_date ON expense_refunds (org_id, settled_on DESC);

-- "What is still out there." The one query the deposits register is built on, and the reason
-- the columns above are cached rather than summed per read: it runs over every expense the
-- organisation has ever booked, and all but a handful of them carry no deposit at all.
CREATE INDEX ix_expenses_deposit_outstanding
    ON expenses (org_id, site_id, refund_expected_on)
    WHERE refundable_amount > 0
      AND refunded_amount + written_off_amount < refundable_amount;

-- ---------------------------------------------------------------- permissions
-- None. Saying that part of a bill is a deposit is part of typing the bill, which is
-- `expense:create`; recording that the money came back is the mirror of recording that money
-- went out, which is `payment:record` and is already the accountant's and the administrator's
-- alone. A permission minted here would let an organisation grant somebody the right to say
-- a deposit had returned without the right to say a payment had left, and those are one job.
