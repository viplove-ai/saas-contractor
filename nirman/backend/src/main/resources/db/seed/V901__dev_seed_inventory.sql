-- Nirman — development seed, Phase 4: BOQ items, opening stock and material estimates.
--
-- A separate file rather than an edit to V900: that one has been applied, and Flyway
-- checksums an applied script. Same discipline as the real migrations.
--
-- The numbers are chosen to reproduce the situation docs/09 section B is about, because it
-- is the one that has to be got right and the one that is easiest to get wrong. The cement
-- take-off covers RCC and two brickwork lines and totals ~263 bags; plaster and flooring
-- are on the BOQ and in nobody's take-off. Issue cement against those two lines and the
-- variance report must report an overrun of nothing, with the uncovered scope listed
-- beside it. The steel BBS covers the whole structure and is the clean comparison.

-- ---------------------------------------------------------------- BOQ items
-- Categories match material_consumption_norms.work_category exactly, which is what lets an
-- estimate be derived from a coefficient instead of typed.
INSERT INTO boq_items (id, org_id, project_id, site_id, item_number, description, unit_id,
                       contract_quantity, contract_rate, contract_amount, work_part, category,
                       source, sort_order)
SELECT b.id::uuid, '10000000-0000-0000-0000-000000000001',
       '30000000-0000-0000-0000-000000000001', b.site_id::uuid, b.item_number, b.description,
       (SELECT id FROM units WHERE code = b.unit
         AND org_id = '10000000-0000-0000-0000-000000000001'),
       b.qty, b.rate, round(b.qty * b.rate, 2), 'Civil Works', b.category, 'MANUAL', b.sort_order
FROM (VALUES
    -- Covered by the cement take-off
    ('70000000-0000-0000-0000-000000000001', '31000000-0000-0000-0000-000000000001', 'B-001',
     'RCC M25 in columns',                          'CUM',  18.000,  8200.0000, 'Concrete & RCC', 1),
    ('70000000-0000-0000-0000-000000000002', '31000000-0000-0000-0000-000000000001', 'B-002',
     'RCC M25 in beams and slab',                   'CUM',  22.000,  7900.0000, 'Concrete & RCC', 2),
    ('70000000-0000-0000-0000-000000000003', '31000000-0000-0000-0000-000000000001', 'B-003',
     'Brickwork in cement mortar 1:4',              'CUM',  50.000,  6100.0000, 'Brickwork',      3),
    ('70000000-0000-0000-0000-000000000004', '31000000-0000-0000-0000-000000000001', 'B-004',
     'Brickwork in cement mortar 1:6',              'CUM',  50.000,  5800.0000, 'Brickwork',      4),
    -- Real work that the cement take-off never touched. This is the completeness trap:
    -- cement issued here is not an overrun against a take-off that never claimed it.
    ('70000000-0000-0000-0000-000000000005', '31000000-0000-0000-0000-000000000001', 'B-005',
     'Cement plaster 12mm on brick surfaces',       'SQM', 1180.000,  290.0000, 'Plastering',     5),
    ('70000000-0000-0000-0000-000000000006', '31000000-0000-0000-0000-000000000001', 'B-006',
     'Kota stone flooring 25mm',                    'SQM',  320.000, 1150.0000, 'Flooring',       6),
    -- Steel: the clean case, because a bar bending schedule covers the whole structure.
    ('70000000-0000-0000-0000-000000000007', '31000000-0000-0000-0000-000000000001', 'B-007',
     'TMT reinforcement Fe-500D, cut bent and placed', 'KG', 4550.000,  78.0000, 'Concrete & RCC', 7),
    -- Annexe work, so the second site has something to charge material to.
    ('70000000-0000-0000-0000-000000000008', '31000000-0000-0000-0000-000000000002', 'B-101',
     'Brickwork in cement mortar 1:6, annexe',      'CUM',  38.000,  5800.0000, 'Brickwork',      8)
) AS b(id, site_id, item_number, description, unit, qty, rate, category, sort_order);

-- A parser placeholder, kept so the "nothing may be charged to a rounding gap" rule has a
-- fixture. is_synthetic = true; BoqItemService.requireChargeable refuses it.
INSERT INTO boq_items (id, org_id, project_id, item_number, description, unit_id,
                       contract_quantity, contract_rate, contract_amount, category, source,
                       is_synthetic, sort_order)
SELECT '70000000-0000-0000-0000-000000000099', '10000000-0000-0000-0000-000000000001',
       '30000000-0000-0000-0000-000000000001', 'B-UNALLOC',
       'UNALLOCATED — reconciliation placeholder from tender import',
       (SELECT id FROM units WHERE code = 'CUM'
         AND org_id = '10000000-0000-0000-0000-000000000001'),
       0, 0, 0, 'Unallocated', 'NIT_IMPORT', true, 99;

-- ---------------------------------------------------------------- opening stock
-- Written as ledger rows plus the balance they produce, exactly as StockLedgerService would
-- write them: the ledger is the truth and stock_balances is its cache, so seeding one
-- without the other would seed the very inconsistency the module exists to prevent.
--
-- uq_stx_opening_once (V7) means this can only ever run once per store and material, which
-- is also what stops a second application of the seed from doubling a store.
INSERT INTO stock_transactions (org_id, project_id, site_id, store_id, material_id, txn_type,
                                direction, txn_date, quantity_base, rate_base, value,
                                balance_after, avg_rate_after, source_type, reason)
SELECT '10000000-0000-0000-0000-000000000001', '30000000-0000-0000-0000-000000000001',
       s.site_id::uuid, s.store_id::uuid, s.material_id::uuid, 'OPENING_STOCK', 1,
       DATE '2024-12-01', s.qty, s.rate, round(s.qty * s.rate, 2), s.qty, s.rate,
       'OPENING', 'Opening stock at project start'
FROM (VALUES
    -- Main block store
    ('32000000-0000-0000-0000-000000000001', '31000000-0000-0000-0000-000000000001',
     '40000000-0000-0000-0000-000000000001',  120.0000,  415.0000),   -- cement, bags
    ('32000000-0000-0000-0000-000000000001', '31000000-0000-0000-0000-000000000001',
     '40000000-0000-0000-0000-000000000002', 2500.0000,   62.0000),   -- steel, kg
    ('32000000-0000-0000-0000-000000000001', '31000000-0000-0000-0000-000000000001',
     '40000000-0000-0000-0000-000000000003',  180.0000,   95.0000),   -- aggregate, quintals
    ('32000000-0000-0000-0000-000000000001', '31000000-0000-0000-0000-000000000001',
     '40000000-0000-0000-0000-000000000004',  150.0000,  110.0000),   -- coarse sand, quintals
    ('32000000-0000-0000-0000-000000000001', '31000000-0000-0000-0000-000000000001',
     '40000000-0000-0000-0000-000000000005', 8000.0000,   11.0000),   -- bricks, numbers
    -- Annexe store
    ('32000000-0000-0000-0000-000000000002', '31000000-0000-0000-0000-000000000002',
     '40000000-0000-0000-0000-000000000001',   40.0000,  420.0000),
    ('32000000-0000-0000-0000-000000000002', '31000000-0000-0000-0000-000000000002',
     '40000000-0000-0000-0000-000000000005', 3000.0000,   11.0000)
) AS s(store_id, site_id, material_id, qty, rate);

INSERT INTO stock_balances (org_id, store_id, material_id, quantity_base, moving_avg_rate,
                            stock_value, last_txn_at)
SELECT org_id, store_id, material_id, quantity_base, rate_base, value, now()
FROM stock_transactions
WHERE txn_type = 'OPENING_STOCK'
  AND org_id = '10000000-0000-0000-0000-000000000001';

-- ---------------------------------------------------------------- material estimates
-- Cement, EXECUTION_TAKEOFF, scoped line by line. 200 + 31.5 + 31.25 = 262.75 bags, which
-- is the field take-off's 263 to within a rounding. Note what is NOT here: plaster (B-005)
-- and flooring (B-006) are real work with real cement in them and no estimate at all.
INSERT INTO material_estimates (org_id, project_id, site_id, boq_item_id, material_id,
                                estimate_level, estimated_qty_base, wastage_percent,
                                effective_from, source_ref, derived_from_norm, notes)
SELECT '10000000-0000-0000-0000-000000000001', '30000000-0000-0000-0000-000000000001',
       '31000000-0000-0000-0000-000000000001', e.boq_item_id::uuid,
       '40000000-0000-0000-0000-000000000001', 'EXECUTION_TAKEOFF', e.qty, 3.00,
       DATE '2024-12-01', 'Kausani take-off sheet', true, e.notes
FROM (VALUES
    ('70000000-0000-0000-0000-000000000001',  90.0000, '5.0 bags/cum of RCC M25'),
    ('70000000-0000-0000-0000-000000000002', 110.0000, '5.0 bags/cum of RCC M25'),
    ('70000000-0000-0000-0000-000000000003',  31.5000, '0.63 bags/cum of 1:4 brickwork'),
    ('70000000-0000-0000-0000-000000000004',  31.2500, '0.625 bags/cum of 1:6 brickwork')
) AS e(boq_item_id, qty, notes);

-- Steel, from the bar bending schedule: 4,550 kg, and the BBS covers the whole structure.
-- This is the comparison that can be read as the whole story, and the report says so.
INSERT INTO material_estimates (org_id, project_id, site_id, boq_item_id, material_id,
                                estimate_level, estimated_qty_base, wastage_percent,
                                effective_from, source_ref, derived_from_norm, notes)
VALUES ('10000000-0000-0000-0000-000000000001', '30000000-0000-0000-0000-000000000001',
        '31000000-0000-0000-0000-000000000001', '70000000-0000-0000-0000-000000000007',
        '40000000-0000-0000-0000-000000000002', 'BBS', 4550.0000, 2.00,
        DATE '2024-12-01', 'BBS/KSN/R2', false,
        'Bar bending schedule for the whole structure — complete scope');

-- What the tender priced, for the same steel. Kept apart from the BBS on purpose: the gap
-- between what was quoted and what the drawings demand is a different question from whether
-- site consumed what was planned, and collapsing the two hides the commercially useful one.
INSERT INTO material_estimates (org_id, project_id, site_id, boq_item_id, material_id,
                                estimate_level, estimated_qty_base, wastage_percent,
                                effective_from, source_ref, derived_from_norm, notes)
VALUES ('10000000-0000-0000-0000-000000000001', '30000000-0000-0000-0000-000000000001',
        '31000000-0000-0000-0000-000000000001', '70000000-0000-0000-0000-000000000007',
        '40000000-0000-0000-0000-000000000002', 'TENDER_BOQ', 4200.0000, 0.00,
        DATE '2024-12-01', 'NIT/2024/0642', false,
        'Quoted quantity. The BBS came out 8% higher — that gap is the tender under-quantifying.');
