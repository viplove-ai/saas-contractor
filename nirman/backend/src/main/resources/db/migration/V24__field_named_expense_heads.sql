-- Nirman — an expense head named at the site, for the spending nobody set up a head for.
--
-- The same problem V15 solved for materials, one screen along. An expense is booked against
-- a head — `expenses.category_id` is not nullable and never should be, because a cost with
-- no head is a cost no report can group — and the taxonomy an organisation starts with is
-- the CPWD one V14 gives it. The first time somebody pays for something that catalogue never
-- imagined, the supervisor's only honest answer is to book it under whichever head is least
-- wrong, and a month later the office is reading a "Miscellaneous" figure it cannot break
-- down.
--
-- So the picker gains an "Other" answer, and what he types there becomes a head. It is
-- marked provisional for exactly the reason a field-named material is: it is a phrase off a
-- site, not a decision the office made, and the office needs to be able to find those rows
-- and fold them into the real taxonomy. Unlike a material there is no screen yet that edits
-- a head, so nothing clears the flag today — it is the list of what the taxonomy is missing,
-- and it is written now because it cannot be reconstructed later.

ALTER TABLE expense_categories
    ADD COLUMN provisional boolean NOT NULL DEFAULT false;

COMMENT ON COLUMN expense_categories.provisional IS
    'Named from the site while booking an expense rather than set up by the office. Cleared '
    'when an administrator edits the row.';

-- The office's list of what to tidy up: small by construction, so a partial index.
CREATE INDEX ix_expense_categories_provisional ON expense_categories (org_id, code)
    WHERE provisional;

-- ---------------------------------------------------------------- permission
-- Its own permission rather than masterdata:write, on V15's reasoning. Naming what a hundred
-- rupees was spent on is not the same act as owning the chart of accounts: this one may not
-- set the two flags that decide whether a head's rows count as cost at all
-- (is_material_purchase, is_labour_payment), and the row it creates is marked unvetted.
--
-- Not folded into masterdata:provisional either. That one is held by whoever books a
-- delivery; this one belongs with whoever books an expense, and the two lists are the same
-- today only by coincidence.
INSERT INTO permissions (code, module, description) VALUES
    ('masterdata:provisional:head', 'masterdata',
     'Name an expense head from the site while booking an expense');

-- V2 gave ADMIN every permission by CROSS JOIN, but that ran against the catalogue as it
-- stood then. A permission added later has to be granted explicitly.
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
  FROM roles r
  JOIN permissions p ON p.code = 'masterdata:provisional:head'
 WHERE r.code IN ('ADMIN', 'ENGINEER', 'SUPERVISOR')
   AND r.is_system
   AND NOT EXISTS (SELECT 1 FROM role_permissions rp
                    WHERE rp.role_id = r.id AND rp.permission_id = p.id);
