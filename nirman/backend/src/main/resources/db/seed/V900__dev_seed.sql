-- Nirman — development seed data. Loaded only when the flyway locations include db/seed,
-- which application-dev.yml does and the prod profile never does.
--
-- Shaped after the Kausani field data reviewed in docs/09-design-decisions.md: a 7-hour
-- standard shift, quintal as a working unit, the two-level expense taxonomy, and the CPWD
-- cement coefficients. Fixed UUIDs so tests and API examples can reference rows by id.
--
-- Every seeded login uses the password  Nirman@123  (BCrypt cost 12). Dev only. Never reuse
-- these hashes anywhere near production.

-- ---------------------------------------------------------------- organisation
INSERT INTO organisations (id, name, code, gstin, pan, address, contact_email, contact_phone)
VALUES ('10000000-0000-0000-0000-000000000001', 'Nirman Constructions', 'NIRMAN',
        '05ABCDE1234F1Z5', 'ABCDE1234F', 'Bageshwar, Uttarakhand',
        'office@nirman.example', '+91-9800000000');

-- ---------------------------------------------------------------- users (password: Nirman@123)
-- One login per real person, not one per role. In a firm this size the same people wear
-- several hats, and user_roles is many-to-many precisely so that is expressible. A shared
-- "admin" account would also make audit_logs.username useless: every row has to trace to
-- one human being, or the trail answers "which role did this" instead of "who did this".
INSERT INTO users (id, org_id, username, email, mobile, full_name, password_hash, is_active) VALUES
    ('20000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000001',
     'viplove', 'viplove@nirman.example', '+91-9800000001', 'Viplove Chaudhary',
     '$2y$12$INhOshrwkBHNgxdcOaVvd.mQ6Svx6kAZ9FWKiQlmeRPpnXxzaccHG', true),
    ('20000000-0000-0000-0000-000000000002', '10000000-0000-0000-0000-000000000001',
     'uttam',   'uttam@nirman.example',   '+91-9800000002', 'Uttam Rana',
     '$2y$12$INhOshrwkBHNgxdcOaVvd.mQ6Svx6kAZ9FWKiQlmeRPpnXxzaccHG', true),
    ('20000000-0000-0000-0000-000000000003', '10000000-0000-0000-0000-000000000001',
     'vivek',   'vivek@nirman.example',   '+91-9800000003', 'Vivek Aggarwal',
     '$2y$12$INhOshrwkBHNgxdcOaVvd.mQ6Svx6kAZ9FWKiQlmeRPpnXxzaccHG', true);

-- Viplove and Uttam each hold three roles; Vivek is a pure supervisor, which is what makes
-- him the one seeded login whose token is genuinely site-scoped (see the note below).
INSERT INTO user_roles (user_id, role_id)
SELECT u.id, r.id FROM users u JOIN roles r ON r.is_system AND (
    (u.username = 'viplove' AND r.code IN ('ADMIN', 'ACCOUNTANT', 'SUPERVISOR')) OR
    (u.username = 'uttam'   AND r.code IN ('ADMIN', 'ENGINEER', 'ACCOUNTANT')) OR
    (u.username = 'vivek'   AND r.code = 'SUPERVISOR'))
WHERE u.org_id = '10000000-0000-0000-0000-000000000001';

-- ---------------------------------------------------------------- project, sites, stores
INSERT INTO projects (id, org_id, code, name, client_department, agreement_no, nit_number,
                      contract_value, budget_amount, start_date, expected_completion_date,
                      project_manager_id, status, description)
VALUES ('30000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000001',
        'KSN01', 'Kausani Guest House Extension', 'CPWD', 'AGR/2024/117', 'NIT/2024/0642',
        18500000.00, 16000000.00, DATE '2024-12-01', DATE '2026-03-31',
        '20000000-0000-0000-0000-000000000002', 'ACTIVE',   -- PM: Uttam Rana
        'Guest house extension block: RCC frame, masonry, finishing and E&M works.');

-- The 7.00 shift is what the Kausani wage sheet actually computes with (docs/09).
INSERT INTO sites (id, org_id, project_id, code, name, address,
                   status, start_date, standard_shift_hours, monthly_wage_days) VALUES
    ('31000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000001',
     '30000000-0000-0000-0000-000000000001', 'KSN-A', 'Kausani Main Block',
     'Kausani, Bageshwar', 'ACTIVE', DATE '2024-12-01', 7.00, 26),
    ('31000000-0000-0000-0000-000000000002', '10000000-0000-0000-0000-000000000001',
     '30000000-0000-0000-0000-000000000001', 'KSN-B', 'Kausani Annexe',
     'Kausani, Bageshwar', 'ACTIVE', DATE '2025-02-01', 7.00, 26);

-- Who is named on each site, and as what. A post is a row rather than a column (V19), so a
-- site may carry two supervisors; these two do not, because the sample data is meant to be
-- the ordinary case and the second supervisor is not it.
INSERT INTO site_staff (id, org_id, site_id, user_id, post) VALUES
    -- KSN-A: engineer Uttam, supervisor Vivek
    ('33000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000001',
     '31000000-0000-0000-0000-000000000001', '20000000-0000-0000-0000-000000000002', 'ENGINEER'),
    ('33000000-0000-0000-0000-000000000002', '10000000-0000-0000-0000-000000000001',
     '31000000-0000-0000-0000-000000000001', '20000000-0000-0000-0000-000000000003', 'SUPERVISOR'),
    -- KSN-B: engineer Uttam, supervised by Viplove himself
    ('33000000-0000-0000-0000-000000000003', '10000000-0000-0000-0000-000000000001',
     '31000000-0000-0000-0000-000000000002', '20000000-0000-0000-0000-000000000002', 'ENGINEER'),
    ('33000000-0000-0000-0000-000000000004', '10000000-0000-0000-0000-000000000001',
     '31000000-0000-0000-0000-000000000002', '20000000-0000-0000-0000-000000000001', 'SUPERVISOR');

INSERT INTO stores (id, org_id, site_id, code, name, location, is_default) VALUES
    ('32000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000001',
     '31000000-0000-0000-0000-000000000001', 'KSN-A-ST1', 'Main Block Store', 'Ground floor lockup', true),
    ('32000000-0000-0000-0000-000000000002', '10000000-0000-0000-0000-000000000001',
     '31000000-0000-0000-0000-000000000002', 'KSN-B-ST1', 'Annexe Store', 'Site cabin', true);

-- Who is posted where. Note that this only *restricts* Vivek: Viplove and Uttam both hold
-- ADMIN, so their tokens carry the ALL-sites claim and these rows never narrow what they
-- see. They are recorded anyway because posting is an operational fact — it is who answers
-- for the site — not only an access-control one.
--
-- Vivek is therefore the one login that demonstrates site scoping by hand, and the fixture
-- the scope integration test asserts against: KSN-A yes, KSN-B 403.
INSERT INTO user_site_assignments (org_id, user_id, site_id, assigned_from, is_primary) VALUES
    -- Viplove: KSN-B
    ('10000000-0000-0000-0000-000000000001', '20000000-0000-0000-0000-000000000001',
     '31000000-0000-0000-0000-000000000002', DATE '2025-02-01', true),
    -- Uttam: both sites, as engineer
    ('10000000-0000-0000-0000-000000000001', '20000000-0000-0000-0000-000000000002',
     '31000000-0000-0000-0000-000000000001', DATE '2024-12-01', true),
    ('10000000-0000-0000-0000-000000000001', '20000000-0000-0000-0000-000000000002',
     '31000000-0000-0000-0000-000000000002', DATE '2025-02-01', false),
    -- Vivek: KSN-A only
    ('10000000-0000-0000-0000-000000000001', '20000000-0000-0000-0000-000000000003',
     '31000000-0000-0000-0000-000000000001', DATE '2024-12-01', true);

-- ---------------------------------------------------------------- units
-- QTL earns its place from the field sheets: aggregate and sand move in quintals (docs/09).
INSERT INTO units (org_id, code, name, decimal_places)
SELECT '10000000-0000-0000-0000-000000000001', v.code, v.name, v.dp
FROM (VALUES
    ('BAG', 'Bag',          0),
    ('KG',  'Kilogram',     3),
    ('QTL', 'Quintal',      3),
    ('MT',  'Metric Tonne', 3),
    ('CUM', 'Cubic Metre',  3),
    ('SQM', 'Square Metre', 2),
    ('NOS', 'Numbers',      0),
    ('MTR', 'Metre',        2),
    ('LTR', 'Litre',        2),
    ('BOX', 'Box',          0)
) AS v(code, name, dp);

-- ---------------------------------------------------------------- material master
INSERT INTO material_categories (org_id, code, name)
SELECT '10000000-0000-0000-0000-000000000001', v.code, v.name
FROM (VALUES
    ('CEMENT',    'Cement'),
    ('STEEL',     'Steel and Reinforcement'),
    ('AGGREGATE', 'Aggregate and Sand'),
    ('MASONRY',   'Bricks and Masonry'),
    ('MISC',      'Miscellaneous')
) AS v(code, name);

INSERT INTO materials (id, org_id, code, name, category_id, base_unit_id, hsn_code,
                       gst_percent, min_stock_level, standard_rate)
SELECT m.id::uuid, '10000000-0000-0000-0000-000000000001', m.code, m.name,
       (SELECT id FROM material_categories WHERE code = m.cat
         AND org_id = '10000000-0000-0000-0000-000000000001'),
       (SELECT id FROM units WHERE code = m.unit
         AND org_id = '10000000-0000-0000-0000-000000000001'),
       m.hsn, m.gst, m.min_stock, m.rate
FROM (VALUES
    ('40000000-0000-0000-0000-000000000001', 'CEM-OPC43',  'Cement OPC 43 Grade',           'CEMENT',    'BAG', '2523', 28.00, 50,   415.0000),
    ('40000000-0000-0000-0000-000000000002', 'STL-FE500D', 'Steel TMT Fe-500D',             'STEEL',     'KG',  '7214', 18.00, 500,  62.0000),
    ('40000000-0000-0000-0000-000000000003', 'AGG-20MM',   'Coarse Aggregate 20mm',         'AGGREGATE', 'QTL', '2517', 5.00,  100,  95.0000),
    ('40000000-0000-0000-0000-000000000004', 'SAND-COARSE','Coarse Sand',                   'AGGREGATE', 'QTL', '2505', 5.00,  100,  110.0000),
    ('40000000-0000-0000-0000-000000000005', 'BRICK-1C',   'First Class Brick',             'MASONRY',   'NOS', '6904', 12.00, 2000, 11.0000)
) AS m(id, code, name, cat, unit, hsn, gst, min_stock, rate);

-- Alternative units: factor_to_base converts one alt unit into base units.
INSERT INTO material_unit_conversions (org_id, material_id, alt_unit_id, factor_to_base)
SELECT '10000000-0000-0000-0000-000000000001', c.material_id::uuid,
       (SELECT id FROM units WHERE code = c.alt
         AND org_id = '10000000-0000-0000-0000-000000000001'),
       c.factor
FROM (VALUES
    -- 1 kg of cement = 0.02 bag (50 kg bags)
    ('40000000-0000-0000-0000-000000000001', 'KG',  0.02000000),
    -- 1 quintal of steel = 100 kg; 1 tonne = 1000 kg
    ('40000000-0000-0000-0000-000000000002', 'QTL', 100.00000000),
    ('40000000-0000-0000-0000-000000000002', 'MT',  1000.00000000)
) AS c(material_id, alt, factor);

-- CPWD-style consumption coefficients observed in the Kausani take-off (docs/09):
-- 5.0 bags of cement per cum of RCC, 0.63 per cum of brickwork.
INSERT INTO material_consumption_norms (org_id, work_category, work_sub_type, material_id,
                                        work_unit_id, qty_per_work_unit, source, notes)
SELECT '10000000-0000-0000-0000-000000000001', n.cat, n.sub, n.material_id::uuid,
       (SELECT id FROM units WHERE code = 'CUM'
         AND org_id = '10000000-0000-0000-0000-000000000001'),
       n.qty, 'INTERNAL', n.notes
FROM (VALUES
    ('Concrete & RCC', 'M25',   '40000000-0000-0000-0000-000000000001', 5.000000, 'Kausani take-off coefficient'),
    ('Brickwork',      '1:4',   '40000000-0000-0000-0000-000000000001', 0.630000, 'Kausani take-off coefficient'),
    ('Brickwork',      '1:6',   '40000000-0000-0000-0000-000000000001', 0.625000, 'Kausani take-off coefficient')
) AS n(cat, sub, material_id, qty, notes);

-- ---------------------------------------------------------------- vendors and contractors
INSERT INTO vendors (org_id, code, name, vendor_type, contact_person, mobile, gstin, credit_days)
VALUES
    ('10000000-0000-0000-0000-000000000001', 'VND-001', 'Shri Ji Traders', 'MATERIAL',
     'Rakesh Agarwal', '+91-9810000001', '05FGHIJ5678K1Z3', 15),
    ('10000000-0000-0000-0000-000000000001', 'VND-002', 'Haldwani Steel Suppliers', 'MATERIAL',
     'Naveen Pant', '+91-9810000002', '05KLMNO9012P1Z1', 30),
    ('10000000-0000-0000-0000-000000000001', 'VND-003', 'Kumaon Transport Co', 'TRANSPORT',
     'Bhupal Mehta', '+91-9810000003', NULL, 0);

INSERT INTO labour_contractors (org_id, code, name, contact_person, mobile)
VALUES ('10000000-0000-0000-0000-000000000001', 'LC-001', 'Kausani Labour Co-operative',
        'Karam Singh', '+91-9820000001');

INSERT INTO skill_categories (org_id, code, name, is_skilled)
SELECT '10000000-0000-0000-0000-000000000001', v.code, v.name, v.skilled
FROM (VALUES
    ('MASON',       'Mason',        true),
    ('HELPER',      'Helper',       false),
    ('CARPENTER',   'Carpenter',    true),
    ('BAR_BENDER',  'Bar Bender',   true),
    ('ELECTRICIAN', 'Electrician',  true),
    ('PLUMBER',     'Plumber',      true)
) AS v(code, name, skilled);

-- ---------------------------------------------------------------- expense taxonomy
-- Two levels, following the field sheets. The flags are the double-counting guards:
-- is_labour_payment rows settle wages already costed through attendance, and
-- is_material_purchase rows become inventory value, not direct cost (docs/09).
INSERT INTO expense_categories (id, org_id, code, name, parent_id,
                                is_material_purchase, is_labour_payment, requires_vendor, sort_order)
VALUES
    ('50000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000001',
     'LABOUR',   'Labour',        NULL, false, false, false, 1),
    ('50000000-0000-0000-0000-000000000002', '10000000-0000-0000-0000-000000000001',
     'MATERIAL', 'Material',      NULL, false, false, false, 2),
    ('50000000-0000-0000-0000-000000000003', '10000000-0000-0000-0000-000000000001',
     'MISC',     'Miscellaneous', NULL, false, false, false, 3),
    ('50000000-0000-0000-0000-000000000004', '10000000-0000-0000-0000-000000000001',
     'OFFICE',   'Office',        NULL, false, false, false, 4);

INSERT INTO expense_categories (org_id, code, name, parent_id,
                                is_material_purchase, is_labour_payment, requires_vendor, sort_order)
VALUES
    ('10000000-0000-0000-0000-000000000001', 'LABOUR-WAGE',    'Worker Wage Payment',
     '50000000-0000-0000-0000-000000000001', false, true,  false, 1),
    ('10000000-0000-0000-0000-000000000001', 'LABOUR-CONTRACT','Labour Contractor Payment',
     '50000000-0000-0000-0000-000000000001', false, true,  false, 2),
    ('10000000-0000-0000-0000-000000000001', 'MAT-PURCHASE',   'Material Purchase',
     '50000000-0000-0000-0000-000000000002', true,  false, true,  1),
    ('10000000-0000-0000-0000-000000000001', 'MAT-FREIGHT',    'Material Freight and Cartage',
     '50000000-0000-0000-0000-000000000002', false, false, false, 2),
    ('10000000-0000-0000-0000-000000000001', 'MISC-TRAVEL',    'Travel',
     '50000000-0000-0000-0000-000000000003', false, false, false, 1),
    ('10000000-0000-0000-0000-000000000001', 'MISC-PETROL',    'Petrol and Diesel',
     '50000000-0000-0000-0000-000000000003', false, false, false, 2),
    ('10000000-0000-0000-0000-000000000001', 'MISC-TOOLS',     'Tools and Consumables',
     '50000000-0000-0000-0000-000000000003', false, false, false, 3),
    ('10000000-0000-0000-0000-000000000001', 'MISC-SITE',      'Site Expenses',
     '50000000-0000-0000-0000-000000000003', false, false, false, 4),
    ('10000000-0000-0000-0000-000000000001', 'OFFICE-GEN',     'Office Expenses',
     '50000000-0000-0000-0000-000000000004', false, false, false, 1);

-- ---------------------------------------------------------------- workers
-- Codes follow the field sheet's 100-118 range. Overtime rates are the daily rate divided
-- by the seven-hour shift, which is what the Kausani sheet actually pays: an overtime hour
-- is worth exactly a regular hour there, with no premium (docs/09).
INSERT INTO workers (id, org_id, worker_code, full_name, mobile, skill_category_id,
                     employment_type, labour_contractor_id, wage_type, joining_date, is_active)
SELECT w.id::uuid, '10000000-0000-0000-0000-000000000001', w.code, w.name, w.mobile,
       (SELECT id FROM skill_categories WHERE code = w.skill
         AND org_id = '10000000-0000-0000-0000-000000000001'),
       w.employment,
       (SELECT id FROM labour_contractors WHERE code = 'LC-001'
         AND org_id = '10000000-0000-0000-0000-000000000001'),
       'DAILY', DATE '2024-12-01', true
FROM (VALUES
    ('60000000-0000-0000-0000-000000000101', 'W-101', 'Karam Singh',    '+91-9830000101', 'MASON',       'CONTRACT'),
    ('60000000-0000-0000-0000-000000000102', 'W-102', 'Ramesh Kumar',   '+91-9830000102', 'HELPER',      'CONTRACT'),
    ('60000000-0000-0000-0000-000000000103', 'W-103', 'Suresh Lal',     '+91-9830000103', 'CARPENTER',   'CONTRACT'),
    ('60000000-0000-0000-0000-000000000104', 'W-104', 'Dinesh Ram',     '+91-9830000104', 'BAR_BENDER',  'CONTRACT'),
    ('60000000-0000-0000-0000-000000000105', 'W-105', 'Mahesh Chand',   '+91-9830000105', 'HELPER',      'CONTRACT'),
    ('60000000-0000-0000-0000-000000000106', 'W-106', 'Prakash Joshi',  '+91-9830000106', 'MASON',       'CONTRACT'),
    -- Naresh exists so the workflow tests have a man whose ledger nobody else asserts a
    -- total for. The other four on this site are each pinned to an exact earned figure by
    -- some test, which makes any new test that verifies one of them break another.
    ('60000000-0000-0000-0000-000000000107', 'W-107', 'Naresh Kumar',   '+91-9830000107', 'HELPER',      'CONTRACT')
) AS w(id, code, name, mobile, skill, employment);

INSERT INTO wage_rates (org_id, worker_id, normal_rate, overtime_rate, effective_from, remarks)
SELECT '10000000-0000-0000-0000-000000000001', r.worker_id::uuid, r.normal, r.overtime,
       DATE '2024-12-01', 'Opening rate at project start'
FROM (VALUES
    ('60000000-0000-0000-0000-000000000101', 625.0000,  89.2857),
    ('60000000-0000-0000-0000-000000000102', 450.0000,  64.2857),
    ('60000000-0000-0000-0000-000000000103', 700.0000, 100.0000),
    ('60000000-0000-0000-0000-000000000104', 700.0000, 100.0000),
    ('60000000-0000-0000-0000-000000000105', 450.0000,  64.2857),
    ('60000000-0000-0000-0000-000000000106', 625.0000,  89.2857),
    ('60000000-0000-0000-0000-000000000107', 700.0000, 100.0000)
) AS r(worker_id, normal, overtime);

-- Five men on the main block, two on the annexe. The allocation open on a date is what
-- decides whose roster a worker appears on that morning.
INSERT INTO worker_site_allocations (org_id, worker_id, site_id, effective_from)
SELECT '10000000-0000-0000-0000-000000000001', a.worker_id::uuid, a.site_id::uuid, a.from_date
FROM (VALUES
    ('60000000-0000-0000-0000-000000000101', '31000000-0000-0000-0000-000000000001', DATE '2024-12-01'),
    ('60000000-0000-0000-0000-000000000102', '31000000-0000-0000-0000-000000000001', DATE '2024-12-01'),
    ('60000000-0000-0000-0000-000000000103', '31000000-0000-0000-0000-000000000001', DATE '2024-12-01'),
    ('60000000-0000-0000-0000-000000000104', '31000000-0000-0000-0000-000000000001', DATE '2024-12-01'),
    ('60000000-0000-0000-0000-000000000105', '31000000-0000-0000-0000-000000000002', DATE '2025-02-01'),
    ('60000000-0000-0000-0000-000000000106', '31000000-0000-0000-0000-000000000002', DATE '2025-02-01'),
    ('60000000-0000-0000-0000-000000000107', '31000000-0000-0000-0000-000000000001', DATE '2024-12-01')
) AS a(worker_id, site_id, from_date);

-- ---------------------------------------------------------------- per-org settings
INSERT INTO labour_settings (org_id, overtime_reason_required_above_hours, settlement_period)
VALUES ('10000000-0000-0000-0000-000000000001', 4.00, 'MONTHLY');

INSERT INTO expense_settings (org_id, bill_required_above, admin_approval_above)
VALUES ('10000000-0000-0000-0000-000000000001', 5000, 25000);
