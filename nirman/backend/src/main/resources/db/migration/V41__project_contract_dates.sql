-- Nirman — the contract's calendar, cut back to the two dates anybody fills in.
--
-- V38 gave the project three dates off the tender file — the bid opening, the allotment
-- letter and the department's completion certificate — because the treasury register works
-- every deposit's release date out of them. The reasoning was sound and the dates were
-- correct; they were simply never entered. Three date boxes on a form, each asking for a
-- letter that lives in a different file, and the man creating the project has the notice in
-- front of him and nothing else. A release schedule computed from blanks proposes blanks, so
-- the register has been showing "—" beside deposits that do have a due date.
--
-- The two dates the office does fill in are the start and the expected completion, because
-- every other screen in the system asks for them and they are what the site actually works
-- to. So the schedule now hangs off those:
--
--   * The earnest money comes back when the bid stops being live, which the allotment letter
--     used to date. The work's start date says the same thing from the other side — the
--     contract had been awarded by the day work began — and it is a date somebody types.
--   * The guarantee and the deposit run from completion: the day work actually finished
--     where that is recorded, and the expected completion until then. Projecting off the
--     expectation is the change of substance here. It is a due date that will move, and a due
--     date that moves is worth more to the man chasing an FDR than a blank that never does.
--
-- What does not change: nature of work, defect liability, and the arithmetic of all four
-- deposits. Only the dates they are counted from.

ALTER TABLE projects
    DROP COLUMN bid_opening_date,
    DROP COLUMN allotment_letter_date,
    DROP COLUMN completion_certificate_date;

-- ---------------------------------------------------------------- and one column renamed,
-- because two of them were called the estimate and only one of them was.
--
-- The NIT states the estimated cost put to tender, and `NitImportService` has always written
-- that figure into `contract_value` — the project form says so on the box in as many words.
-- V38 then added `estimated_cost` for the same thing under the CPWD name, and the treasury
-- read the pair the other way round: contract_value as the accepted bid, estimated_cost as
-- the department's estimate, backing one out of the other by dividing by the quote. Both
-- cannot be true, and the import decides what is actually in the columns.
--
-- So the second one is renamed to what it is. `quoted_cost` is the contract value moved by
-- the contractor's quote — what the work pays at the rate he bid — and nobody reading the
-- four columns together has to remember which of two estimates is which:
--
--     contract_value   what the notice put to tender
--     quoted_percent   what he bid against it, above (+) or below (-)
--     quoted_cost      the first moved by the second
--     budget_amount    what the office allows itself, a quarter below the quoted cost
--
-- The last two are worked out on the project form and a person may type over either.
--
-- No data is moved, only the column's name. Every row reached these columns through the
-- import or the form, both of which already meant them this way; swapping two columns on the
-- strength of a comment nobody had followed would corrupt the rows that were right.

ALTER TABLE projects RENAME COLUMN estimated_cost TO quoted_cost;
ALTER TABLE projects RENAME CONSTRAINT ck_projects_estimated_cost TO ck_projects_quoted_cost;

COMMENT ON COLUMN projects.contract_value IS
    'The estimated cost put to tender, as stated in the notice. Written by the NIT import. '
    'Every deposit the treasury proposes is reckoned against this or against the quoted cost.';
COMMENT ON COLUMN projects.quoted_cost IS
    'What the work pays at the rate bid: contract_value moved by quoted_percent. Worked out '
    'on the project form and editable there. The performance guarantee stands on this or on '
    'the contract value, whichever is higher, so a low bid does not shrink it.';
COMMENT ON COLUMN projects.budget_amount IS
    'What the office allows itself to spend against the work. Proposed at a quarter below the '
    'quoted cost and overridable, because a budget answers to things arithmetic cannot see.';
