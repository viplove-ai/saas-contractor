-- Nirman — development seed, Phase 5: approval routing, and a float to settle.
--
-- A separate file rather than an edit to V900 or V901: those have been applied, and Flyway
-- checksums an applied script.

-- ---------------------------------------------------------------- approval rules
-- The routing docs/09 open question 2 made authoritative. Two levels for an expense:
--
--   Level 1  ENGINEER  everything.        The site engineer sees every bill from his site.
--   Level 2  ADMIN     ₹25,000 and above. The office sees the large ones.
--
-- A ₹4,000 cartage bill therefore passes one person and is done; a ₹60,000 steel invoice
-- passes two. The threshold matches expense_settings.admin_approval_above, which is now a
-- historical field and not read by the flow — one home for a rule, not two.
--
-- The bounds are inclusive at the bottom and exclusive at the top, so 25,000 exactly lands
-- in the administrator's queue rather than falling between the two rows.
INSERT INTO approval_rules (org_id, entity_type, level, role_code, min_amount, max_amount)
VALUES
    ('10000000-0000-0000-0000-000000000001', 'EXPENSE', 1, 'ENGINEER', NULL, NULL),
    ('10000000-0000-0000-0000-000000000001', 'EXPENSE', 2, 'ADMIN',    25000, NULL),
    -- A settlement is the office agreeing that a float was properly spent, so it goes
    -- straight to whoever issued it. One level, no threshold: a small settlement that
    -- nobody checks is exactly how a float quietly stops being reconciled.
    ('10000000-0000-0000-0000-000000000001', 'ADVANCE_SETTLEMENT', 1, 'ACCOUNTANT', NULL, NULL);

-- ---------------------------------------------------------------- a float in a pocket
-- Vivek supervises KSN-A and is the one seeded login that is genuinely site-scoped, so a
-- float in his hands is the fixture that exercises the settle flow end to end: he submits,
-- the accountant approves, and only then does the balance move.
INSERT INTO site_advances (id, org_id, project_id, site_id, advance_number, issued_to_user_id,
                           advance_date, amount, payment_mode, reference_number, purpose,
                           issued_by)
VALUES ('80000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000001',
        '30000000-0000-0000-0000-000000000001', '31000000-0000-0000-0000-000000000001',
        'SAD-2025-0001', '20000000-0000-0000-0000-000000000003',
        DATE '2025-06-01', 20000.00, 'CASH', NULL,
        'Site petty cash for June: cartage, small tools and consumables',
        '20000000-0000-0000-0000-000000000001');
