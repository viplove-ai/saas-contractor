-- Nirman — what counts as evidence that an expense happened, and where that is decided.
--
-- V1 wrote the rule as a row-level check: an approved expense must carry either a bill
-- number or a written reason for its absence. The sentence is right. The place was wrong, and
-- it had been quietly refusing legitimate work.
--
-- A check constraint sees one row of one table and nothing else. This rule needs two things
-- that live elsewhere, both of which the organisation had already said out loud:
--
--   * `expense_settings.bill_required_above` — the threshold, which exists because a great
--     many small site purchases genuinely have no bill, and demanding a written reason on
--     every ₹200 of cartage produces a column of the word "cash" that nobody reads. The check
--     could not read that table, so it enforced on every row a rule the organisation had
--     switched off below its own limit. A ₹813 bus fare from Pithoragarh to Bageshwar is the
--     shape of row that hit it.
--   * `expense_attachments` — the photograph. A challan with no serial number on it is still
--     a challan, and the man at the gate photographs it. `ExpenseService` has accepted that as
--     evidence since it was written; the check never knew about it.
--
-- What made it worse than a wrong rule is where it fired. The predicate is on
-- workflow_status, so the row saves, submits and sits in the queue without complaint, and the
-- refusal arrives when the approver presses Approve — as 23514, which Spring maps to a 409 and
-- GlobalExceptionHandler spells "This record conflicts with one that already exists." Nothing
-- conflicted with anything. A bill number was missing, and neither the approver nor the man
-- who typed the expense was told so.
--
-- So the rule moves to `ExpenseEvidencePolicy`, where the threshold and the attachments are
-- both visible, and it is asked at submission, at revision, and once more in
-- `ExpenseApprovalListener` when a decision turns the row into an approved one — the backstop
-- this check was, refusing with 422 and the sentence a person can act on.
--
-- Dropping a constraint is not a thing to do lightly, and the argument for it is not that the
-- rule stopped mattering: it is that a check which refuses correct rows and cannot express the
-- correct rule is worse than the same rule enforced where it can be stated properly. Nothing
-- in this file relaxes what an approved expense has to carry.

ALTER TABLE expenses DROP CONSTRAINT ck_expense_bill_reason;

COMMENT ON COLUMN expenses.no_bill_reason IS
    'Why there is no bill. Required by ExpenseEvidencePolicy above '
    'expense_settings.bill_required_above when no bill number and no photograph of the bill '
    'is on the record — not by a check constraint, which can see neither the threshold nor '
    'the attachment. See the V40 header.';
