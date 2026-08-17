-- ---------------------------------------------------------------------------
-- Whose cost is it.
--
-- Every expense in this system has been the site's since V1, because every expense is
-- entered at a site and the entry screen has nowhere else to put it. That is true of the
-- lorry of sand and false of the accountant's salary: both are typed by somebody standing at
-- a site, and only one of them is a cost that site's project should carry. Booking the second
-- against the site overstates the job — the same double-count docs/09 chased out of material
-- and wages, arriving through a different door.
--
-- So the cost carries a decision beside it, and the decision belongs to whoever approves the
-- money, not to whoever typed it: the man at the gate knows what was bought, the office knows
-- whose books it lands in. Three answers, because two were not enough — a bill for diesel
-- half of which ran the site mixer and half the office car is one bill and two costs, and an
-- organisation forced to choose one answer for it books the whole thing wrong or splits the
-- bill into two rows that no longer match the paper.
--
-- **One number is stored, not two.** SPLIT keeps `site_share`; the company's share is
-- `total_amount - site_share` and is computed on the way out. Storing both would be two
-- versions of one truth, and the day an amount is corrected they disagree.
-- ---------------------------------------------------------------------------

ALTER TABLE expenses
    ADD COLUMN cost_allocation  varchar(10)   NOT NULL DEFAULT 'SITE',
    ADD COLUMN site_share       numeric(18,2),
    ADD COLUMN allocation_note  varchar(500),
    ADD COLUMN allocated_at     timestamptz,
    ADD COLUMN allocated_by     uuid REFERENCES users(id);

ALTER TABLE expenses ADD CONSTRAINT ck_expense_cost_allocation
    CHECK (cost_allocation IN ('SITE', 'COMPANY', 'SPLIT'));

-- The share is meaningful for exactly one of the three answers, and it is strictly inside the
-- total on that one: a "split" that gives the site everything is SITE, and one that gives it
-- nothing is COMPANY. Letting either be written as a SPLIT would put two spellings of the
-- same fact in the column the register groups by.
ALTER TABLE expenses ADD CONSTRAINT ck_expense_site_share
    CHECK ((cost_allocation = 'SPLIT'
            AND site_share IS NOT NULL AND site_share > 0 AND site_share < total_amount)
        OR (cost_allocation <> 'SPLIT' AND site_share IS NULL));

-- The register: every payment record in the organisation, newest first, narrowed by kind.
CREATE INDEX ix_expenses_allocation ON expenses (org_id, cost_allocation, expense_date DESC);

COMMENT ON COLUMN expenses.cost_allocation IS
    'SITE = the site''s project carries it, COMPANY = organisation overhead, SPLIT = both. '
    'Set by the approver; re-set by an administrator until the site closes.';
COMMENT ON COLUMN expenses.site_share IS
    'SPLIT only: the part the site carries. The company''s part is total_amount minus this '
    'and is never stored — one number, so a corrected total cannot leave the two disagreeing.';

-- ---------------------------------------------------------------------------
-- An approved expense the field needs to correct.
--
-- V1 gave an approved expense one way back: void it and book a replacement, which is right
-- when the expense should not have existed and wrong when a figure was typed badly. The
-- replacement carries a new number, so the vendor's bill and the system disagree about what
-- the record is called, and the supervisor who mistyped 4,500 as 45,000 learns to phone the
-- office instead of using the screen.
--
-- A revision is neither a silent edit nor a void: the row keeps its number, the approval it
-- had is cancelled, and it goes back through the same chain as a first submission. What was
-- signed is in the audit log, and what stands is what somebody signed again. `revision` is
-- what the screen reads to say so.
-- ---------------------------------------------------------------------------

ALTER TABLE expenses
    ADD COLUMN revision        integer NOT NULL DEFAULT 0,
    ADD COLUMN revised_at      timestamptz,
    ADD COLUMN revised_by      uuid REFERENCES users(id),
    ADD COLUMN revision_reason text;

ALTER TABLE expenses ADD CONSTRAINT ck_expense_revision CHECK (revision >= 0);

COMMENT ON COLUMN expenses.revision IS
    'How many times the author has re-opened this expense after approval. Each one cancels '
    'the approval and re-enters the chain; nothing is edited in place behind a signature.';

-- ---------------------------------------------------------------------------
-- The head's own answer.
--
-- Staff salary and office spending are the organisation's, always, at every site, and asking
-- the approver the same question about every one of them is how the question stops being read
-- — he clicks the first option two hundred times and the two hundred and first is the diesel
-- bill. So the head carries the answer it almost always has, the approver is shown it already
-- chosen, and he changes it for the bill where it is wrong.
--
-- Two values, not three: a head cannot default to SPLIT, because a split is an amount and an
-- amount is a fact about one bill, never about a category.
-- ---------------------------------------------------------------------------

ALTER TABLE expense_categories
    ADD COLUMN default_allocation varchar(10) NOT NULL DEFAULT 'SITE';

ALTER TABLE expense_categories ADD CONSTRAINT ck_expense_category_default_allocation
    CHECK (default_allocation IN ('SITE', 'COMPANY'));

-- V14's starting catalogue, for the organisations that took it. Guarded by code, exactly as
-- V14 is: an organisation that renamed or re-purposed these heads keeps what it decided.
UPDATE expense_categories SET default_allocation = 'COMPANY'
 WHERE code IN ('OFFICE', 'OFFICE-GEN');

-- The head the catalogue was missing. Staff are paid by the company and posted to whichever
-- site needs them that month; booking a month's salary against the site the man happened to
-- be standing at is how one project pays for another's people.
INSERT INTO expense_categories (org_id, code, name, parent_id,
                                is_material_purchase, is_labour_payment, requires_vendor,
                                default_allocation, sort_order)
SELECT o.id, 'OFFICE-STAFF', 'Staff Salary and Allowances',
       (SELECT p.id FROM expense_categories p WHERE p.org_id = o.id AND p.code = 'OFFICE'),
       false, false, false, 'COMPANY', 2
  FROM organisations o
 WHERE NOT EXISTS (SELECT 1 FROM expense_categories e
                    WHERE e.org_id = o.id AND e.code = 'OFFICE-STAFF')
   AND EXISTS (SELECT 1 FROM expense_categories p
                WHERE p.org_id = o.id AND p.code = 'OFFICE');

COMMENT ON COLUMN expense_categories.default_allocation IS
    'What the approver is shown already chosen for this head. Never SPLIT: a split is an '
    'amount, and an amount is a fact about one bill rather than about a category.';

-- ---------------------------------------------------------------------------
-- One new permission.
--
-- Deciding whose cost it is *at approval time* needs none: it is part of approving the money,
-- and `expense:approve:l1`/`l2` is already the question "may this person say yes to this
-- spending". Minting a second permission for the same act would let an organisation grant the
-- approval and withhold the classification, leaving an approver who must answer a question he
-- is not allowed to answer.
--
-- Re-deciding it afterwards is a different act by a different person — the accountant reading
-- the month, not the engineer reading the bill — and he holds no approval permission at all.
-- Hence exactly one: `expense:allocate`.
-- ---------------------------------------------------------------------------

INSERT INTO permissions (code, module, description) VALUES
    ('expense:allocate', 'expense',
     'Re-decide whether an approved cost is the site''s or the company''s, until the site closes');

-- V2 granted ADMIN everything by CROSS JOIN over the catalogue as it stood then; anything
-- added later has to be granted explicitly.
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
  FROM roles r
  JOIN permissions p ON p.code = 'expense:allocate'
 WHERE r.code IN ('ADMIN', 'ACCOUNTANT') AND r.is_system
   AND NOT EXISTS (SELECT 1 FROM role_permissions rp
                    WHERE rp.role_id = r.id AND rp.permission_id = p.id);
