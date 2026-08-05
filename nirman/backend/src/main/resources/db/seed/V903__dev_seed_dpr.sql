-- Nirman — development seed, Phase 6: two days of a real site diary.
--
-- A separate file rather than an edit to V900-V902: those have been applied, and Flyway
-- checksums an applied script. Same discipline as the real migrations.
--
-- The two days are deliberately dated 2025-06-09 and 2025-06-10, well clear of every window
-- the existing integration tests assert over (March 2026 for the labour reports, the last
-- ninety days for the cash ones). Seed data that shifted a figure another test pins is seed
-- data that breaks the build for reasons nobody can find.
--
-- What the pair demonstrates:
--   * 09 June — a report the engineer verified, whose measured brickwork became a dated entry
--     in the measurement book. This is the whole path: describe, sign, claim.
--   * 10 June — a draft, with attendance still unverified behind it, so the labour cost on it
--     is provisional and the screen says so.

-- ---------------------------------------------------------------- the muster behind the reports
-- Left SUBMITTED rather than verified on purpose. Verification is what freezes the wage and
-- posts it to the worker's ledger, and a seed that posted wages would put an opening balance on
-- four men that no test asked for. It also gives the DPR screens the more interesting case:
-- a labour cost that is real arithmetic over today's rates and is not yet anybody's signature.
--
-- Hours are the seven-hour Kausani shift, with an hour of overtime on the mason and the bar
-- bender — which is what the field sheet actually looks like on a pour day.
INSERT INTO attendance_records (id, org_id, project_id, site_id, worker_id, attendance_date,
                                status, entered_hours, worked_hours, regular_hours, overtime_hours,
                                boq_item_id, work_location, computed_wage_amount, computed_ot_amount,
                                workflow_status, submitted_at, source)
SELECT a.id::uuid, '10000000-0000-0000-0000-000000000001',
       '30000000-0000-0000-0000-000000000001', '31000000-0000-0000-0000-000000000001',
       a.worker_id::uuid, a.on_date, 'PRESENT', a.hours, a.hours,
       least(a.hours, 7.00), greatest(a.hours - 7.00, 0),
       a.boq_item_id::uuid, a.location, a.wage, a.ot, 'SUBMITTED',
       a.on_date + TIME '18:30', 'ONLINE'
FROM (VALUES
    -- 09 June: brickwork 1:4 on the ground floor. Two masons and a helper.
    ('80000000-0000-0000-0000-000000000001', '60000000-0000-0000-0000-000000000101',
     DATE '2025-06-09', 8.00, '70000000-0000-0000-0000-000000000003', 'Ground floor, north wall',
     625.0000,  89.2857),
    ('80000000-0000-0000-0000-000000000002', '60000000-0000-0000-0000-000000000102',
     DATE '2025-06-09', 7.00, '70000000-0000-0000-0000-000000000003', 'Ground floor, north wall',
     450.0000,   0.0000),
    ('80000000-0000-0000-0000-000000000003', '60000000-0000-0000-0000-000000000107',
     DATE '2025-06-09', 7.00, '70000000-0000-0000-0000-000000000003', 'Ground floor, north wall',
     700.0000,   0.0000),
    -- 10 June: reinforcement for the first-floor beams. Bar bender and a helper.
    ('80000000-0000-0000-0000-000000000004', '60000000-0000-0000-0000-000000000104',
     DATE '2025-06-10', 8.00, '70000000-0000-0000-0000-000000000007', 'First floor, beam B3-B7',
     700.0000, 100.0000),
    ('80000000-0000-0000-0000-000000000005', '60000000-0000-0000-0000-000000000102',
     DATE '2025-06-10', 7.00, '70000000-0000-0000-0000-000000000007', 'First floor, beam B3-B7',
     450.0000,   0.0000),
    ('80000000-0000-0000-0000-000000000006', '60000000-0000-0000-0000-000000000101',
     DATE '2025-06-10', 7.00, '70000000-0000-0000-0000-000000000003', 'Ground floor, north wall',
     625.0000,   0.0000)
) AS a(id, worker_id, on_date, hours, boq_item_id, location, wage, ot);

-- ---------------------------------------------------------------- a cartage bill on the 10th
-- Left as a draft: it is a bill somebody photographed at site and has not sent yet, which is
-- the ordinary state of an expense at six in the evening. It still counts towards the day's
-- cost incurred on the DPR prefill — the report describes what the day cost, not what has been
-- approved — and the prefill reports the unapproved count beside it so nobody mistakes one for
-- the other.
--
-- Site Expenses rather than Material Purchase deliberately: a material purchase would be
-- excluded from cost incurred (it becomes inventory and is costed at issue), so it would
-- demonstrate the split by contributing nothing to the figure the DPR carries.
INSERT INTO expenses (id, org_id, project_id, site_id, expense_number, expense_date, category_id,
                      subcategory_id, vendor_id, boq_item_id, description, bill_number, bill_date,
                      amount_before_tax, gst_percent, gst_amount, total_amount, payment_mode,
                      workflow_status, source)
SELECT '81000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000001',
       '30000000-0000-0000-0000-000000000001', '31000000-0000-0000-0000-000000000001',
       'EXP-2025-9001', DATE '2025-06-10', '50000000-0000-0000-0000-000000000003',
       (SELECT id FROM expense_categories WHERE code = 'MISC-SITE'
         AND org_id = '10000000-0000-0000-0000-000000000001'),
       (SELECT id FROM vendors WHERE code = 'VND-003'
         AND org_id = '10000000-0000-0000-0000-000000000001'),
       '70000000-0000-0000-0000-000000000007',
       'Tractor hire, shifting reinforcement to first floor', 'KT/2025/318', DATE '2025-06-10',
       1800.00, 0.00, 0.00, 1800.00, 'CASH', 'DRAFT', 'ONLINE';

-- ---------------------------------------------------------------- 09 June: the verified report
INSERT INTO daily_progress_reports (
    id, org_id, project_id, site_id, report_date, dpr_number, weather, temperature_c,
    working_hours_lost, labour_present_count, labour_regular_hours, labour_overtime_hours,
    labour_cost, material_received_value, material_consumed_value, expense_amount,
    work_summary, delays, safety_observations, quality_observations, next_day_plan,
    workflow_status, prepared_by, submitted_at, verified_by, verified_at, source)
VALUES (
    '82000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000001',
    '30000000-0000-0000-0000-000000000001', '31000000-0000-0000-0000-000000000001',
    DATE '2025-06-09', 'DPR-2025-9001', 'CLOUDY', 24.5, 0.0,
    -- The frozen snapshot. It matches the three attendance rows above at the rates in force,
    -- and it is stored rather than derived because this report has been signed: a correction to
    -- an underlying row must show up as a difference, not by rewriting what the engineer saw.
    3, 21.00, 1.00, 1864.29, 0.00, 0.00, 0.00,
    'Brickwork in cement mortar 1:4 continued on the ground floor north wall. 18 cum laid and '
        || 'measured jointly with the site engineer.',
    NULL,
    'All three men in helmets. Scaffold planks checked before the lift.',
    'Line and level checked against the grid; joints raked for plaster.',
    'Start reinforcement for the first-floor beams.',
    'VERIFIED', '20000000-0000-0000-0000-000000000003',   -- prepared by Vivek, the supervisor
    TIMESTAMPTZ '2025-06-09 18:40+05:30',
    '20000000-0000-0000-0000-000000000002',               -- verified by Uttam, the engineer
    TIMESTAMPTZ '2025-06-09 20:05+05:30', 'ONLINE');

INSERT INTO dpr_work_items (dpr_id, boq_item_id, activity, work_location, quantity, unit_id,
                            remarks, sort_order)
SELECT '82000000-0000-0000-0000-000000000001', w.boq_item_id::uuid, w.activity, w.location,
       w.qty,
       CASE WHEN w.unit IS NULL THEN NULL
            ELSE (SELECT id FROM units WHERE code = w.unit
                   AND org_id = '10000000-0000-0000-0000-000000000001') END,
       w.remarks, w.sort_order
FROM (VALUES
    -- Measured: this is the row that became a claim against the contract.
    ('70000000-0000-0000-0000-000000000003', 'Brickwork in cement mortar 1:4',
     'Ground floor, north wall', 18.0000, 'CUM', 'Measured jointly with the site engineer', 0),
    -- Described, not measured. Real work against no contract line — which is exactly why
    -- boq_item_id and quantity are both nullable.
    (NULL, 'Curing of yesterday''s brickwork', 'Ground floor', NULL, NULL, NULL, 1)
) AS w(boq_item_id, activity, location, qty, unit, remarks, sort_order);

-- The frozen labour table: two masons on one line, the helper on another. Written from the
-- muster, which is why it carries head counts and hours and no money.
INSERT INTO dpr_labour (dpr_id, skill_category_id, labour_contractor_id, head_count,
                        regular_hours, overtime_hours)
SELECT '82000000-0000-0000-0000-000000000001',
       (SELECT id FROM skill_categories WHERE code = l.skill
         AND org_id = '10000000-0000-0000-0000-000000000001'),
       (SELECT id FROM labour_contractors WHERE code = 'LC-001'
         AND org_id = '10000000-0000-0000-0000-000000000001'),
       l.head_count, l.regular, l.overtime
FROM (VALUES
    ('MASON',  1, 7.00, 1.00),
    ('HELPER', 1, 7.00, 0.00),
    ('HELPER', 1, 7.00, 0.00)
) AS l(skill, head_count, regular, overtime);

-- What verification did. 18 cum of the 50 cum line, dated, traceable to the report that claimed
-- it — and boq_items.completed_quantity below is the cache of exactly this.
INSERT INTO boq_progress_entries (org_id, boq_item_id, site_id, entry_date, quantity, dpr_id,
                                  remarks, recorded_by)
VALUES ('10000000-0000-0000-0000-000000000001', '70000000-0000-0000-0000-000000000003',
        '31000000-0000-0000-0000-000000000001', DATE '2025-06-09', 18.0000,
        '82000000-0000-0000-0000-000000000001',
        'DPR Brickwork in cement mortar 1:4', '20000000-0000-0000-0000-000000000002');

UPDATE boq_items
   SET completed_quantity = 18.0000,
       status             = 'IN_PROGRESS',
       actual_start_date  = DATE '2025-06-09'
 WHERE id = '70000000-0000-0000-0000-000000000003';

-- ---------------------------------------------------------------- 10 June: the draft
-- No frozen snapshot columns. A draft's figures are refreshed from the records on every save
-- and frozen only at submission, so leaving them null here is what a real draft looks like —
-- and the prefill will fill them the moment somebody opens it.
INSERT INTO daily_progress_reports (
    id, org_id, project_id, site_id, report_date, dpr_number, weather, temperature_c,
    working_hours_lost, work_summary, delays, management_attention, next_day_plan,
    workflow_status, prepared_by, source)
VALUES (
    '82000000-0000-0000-0000-000000000002', '10000000-0000-0000-0000-000000000001',
    '30000000-0000-0000-0000-000000000001', '31000000-0000-0000-0000-000000000001',
    DATE '2025-06-10', 'DPR-2025-9002', 'RAIN', 21.0, 1.5,
    'Cutting and binding reinforcement for the first-floor beams. Brickwork paused after the '
        || 'afternoon shower.',
    'Rain from about half past two. Roughly an hour and a half lost on the brickwork.',
    'Steel for the slab has not been indented yet. It is needed on site by the 18th.',
    'Continue beam reinforcement; resume brickwork if it stays dry.',
    'DRAFT', '20000000-0000-0000-0000-000000000003', 'ONLINE');

INSERT INTO dpr_work_items (dpr_id, boq_item_id, activity, work_location, quantity, unit_id,
                            remarks, sort_order)
SELECT '82000000-0000-0000-0000-000000000002', w.boq_item_id::uuid, w.activity, w.location,
       w.qty,
       CASE WHEN w.unit IS NULL THEN NULL
            ELSE (SELECT id FROM units WHERE code = w.unit
                   AND org_id = '10000000-0000-0000-0000-000000000001') END,
       w.remarks, w.sort_order
FROM (VALUES
    ('70000000-0000-0000-0000-000000000007', 'TMT reinforcement cut, bent and placed',
     'First floor, beam B3-B7', 620.0000, 'KG', 'Bar bending schedule sheet 4', 0),
    (NULL, 'Shuttering cleaned and oiled for the beam soffits', 'First floor', NULL, NULL,
     NULL, 1)
) AS w(boq_item_id, activity, location, qty, unit, remarks, sort_order);

INSERT INTO dpr_machinery (dpr_id, machinery_name, count, hours_used, idle_hours, remarks)
VALUES ('82000000-0000-0000-0000-000000000002', 'Bar bending machine', 1, 6.00, 0.00, NULL),
       -- Idle hours are the operationally useful half: a mixer that stood for four hours is a
       -- story about the rain, not about the mixer.
       ('82000000-0000-0000-0000-000000000002', 'Concrete mixer 10/7', 1, 0.00, 4.00,
        'Stood after the shower');
