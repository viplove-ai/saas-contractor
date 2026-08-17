-- Dev and test only. The office heads' default allocation, and the staff head V36 gives every
-- real organisation.
--
-- V36 sets both for the organisations that exist when it runs. The dev organisation does not:
-- it arrives in V900, which Flyway orders *after* every migration, so the same statements have
-- to be repeated here or the demo shows an office bill defaulting to the site — the one case
-- the feature exists for. Exactly the split V14 already lives with, on the same catalogue.

UPDATE expense_categories SET default_allocation = 'COMPANY'
 WHERE org_id = '10000000-0000-0000-0000-000000000001'
   AND code IN ('OFFICE', 'OFFICE-GEN');

INSERT INTO expense_categories (org_id, code, name, parent_id,
                                is_material_purchase, is_labour_payment, requires_vendor,
                                default_allocation, sort_order)
VALUES ('10000000-0000-0000-0000-000000000001', 'OFFICE-STAFF', 'Staff Salary and Allowances',
        '50000000-0000-0000-0000-000000000004', false, false, false, 'COMPANY', 2);
