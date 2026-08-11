-- Nirman — the master data an organisation cannot open the app without.
--
-- V2 says org-specific data never appears in db/migration, and that rule still holds for
-- users, projects and BOQs. Units, materials and the expense taxonomy turned out not to be
-- org-specific in the same way, and leaving them out produced a deployment nobody could use:
-- the dev seed carries them, `db/seed` is loaded only by the dev and test profiles, and
-- there is no screen anywhere in the app that creates a unit or a material. So a production
-- organisation started with an empty catalogue and no way to fill it — the material picker on
-- "material that came in", on "material used today" and the "what for" picker on the day's
-- spending were all empty dropdowns, on every screen, permanently.
--
-- What goes in here is the CPWD starting catalogue and nothing beyond it: the units a challan
-- is written in, five materials every site orders, and the two-level expense taxonomy the
-- costing rules already depend on. It is a floor, not a policy — an organisation adds its own
-- rows through the API and this migration never touches them again.
--
-- Idempotent by code, per organisation, per table. An organisation that already has a unit
-- called BAG keeps its own; nothing here overwrites, renames or deactivates anything. That
-- matters because dev and test databases replay this file before the sample data in
-- `db/seed` inserts the same codes, and because re-running against a live organisation must
-- be a no-op rather than a second catalogue.
--
-- In dev and test the organisation itself arrives in V900, which runs *after* this, so every
-- statement below inserts nothing there and the sample data stands alone. A dev database that
-- has already run the seed is past version 900 and Flyway will refuse to slot a 14 in behind
-- it: `./scripts/dev.sh reset-db` replays from the baseline, which is the documented way to
-- take a migration that has not been applied anywhere yet.

-- ---------------------------------------------------------------- units
-- QTL earns its place from the field sheets: aggregate and sand move in quintals (docs/09).
INSERT INTO units (org_id, code, name, decimal_places)
SELECT o.id, v.code, v.name, v.dp
FROM organisations o
CROSS JOIN (VALUES
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
) AS v(code, name, dp)
WHERE NOT EXISTS (
    SELECT 1 FROM units u WHERE u.org_id = o.id AND u.code = v.code
);

-- ---------------------------------------------------------------- material categories
INSERT INTO material_categories (org_id, code, name)
SELECT o.id, v.code, v.name
FROM organisations o
CROSS JOIN (VALUES
    ('CEMENT',    'Cement'),
    ('STEEL',     'Steel and Reinforcement'),
    ('AGGREGATE', 'Aggregate and Sand'),
    ('MASONRY',   'Bricks and Masonry'),
    ('MISC',      'Miscellaneous')
) AS v(code, name)
WHERE NOT EXISTS (
    SELECT 1 FROM material_categories c WHERE c.org_id = o.id AND c.code = v.code
);

-- ---------------------------------------------------------------- materials
-- Standard rates are a starting figure for the store's moving average, not a price list. The
-- ledger overrules them the moment a real delivery is booked.
INSERT INTO materials (org_id, code, name, category_id, base_unit_id, hsn_code,
                       gst_percent, min_stock_level, standard_rate)
SELECT o.id, m.code, m.name,
       (SELECT c.id FROM material_categories c WHERE c.org_id = o.id AND c.code = m.cat),
       (SELECT u.id FROM units u WHERE u.org_id = o.id AND u.code = m.unit),
       m.hsn, m.gst, m.min_stock, m.rate
FROM organisations o
CROSS JOIN (VALUES
    ('CEM-OPC43',   'Cement OPC 43 Grade',    'CEMENT',    'BAG', '2523', 28.00, 50,   415.0000),
    ('CEM-PPC',     'Cement PPC',             'CEMENT',    'BAG', '2523', 28.00, 50,   395.0000),
    ('STL-FE500D',  'Steel TMT Fe-500D',      'STEEL',     'KG',  '7214', 18.00, 500,  62.0000),
    ('STL-BINDWIRE','Binding Wire',           'STEEL',     'KG',  '7217', 18.00, 25,   75.0000),
    ('AGG-20MM',    'Coarse Aggregate 20mm',  'AGGREGATE', 'QTL', '2517', 5.00,  100,  95.0000),
    ('AGG-10MM',    'Coarse Aggregate 10mm',  'AGGREGATE', 'QTL', '2517', 5.00,  100,  98.0000),
    ('SAND-COARSE', 'Coarse Sand',            'AGGREGATE', 'QTL', '2505', 5.00,  100,  110.0000),
    ('SAND-FINE',   'Fine Sand',              'AGGREGATE', 'QTL', '2505', 5.00,  100,  105.0000),
    ('BRICK-1C',    'First Class Brick',      'MASONRY',   'NOS', '6904', 12.00, 2000, 11.0000),
    ('MISC-PLY',    'Shuttering Plywood 12mm','MISC',      'SQM', '4412', 18.00, 0,    620.0000)
) AS m(code, name, cat, unit, hsn, gst, min_stock, rate)
WHERE NOT EXISTS (
    SELECT 1 FROM materials x WHERE x.org_id = o.id AND x.code = m.code
)
-- An organisation whose units were renamed before this ran keeps its own; a material with no
-- base unit is not insertable, and half a catalogue is worse than the row being skipped.
AND EXISTS (SELECT 1 FROM units u WHERE u.org_id = o.id AND u.code = m.unit);

-- Alternative units. factor_to_base converts one alt unit into base units: a challan written
-- in tonnes is typed in tonnes, and the server converts rather than the storekeeper.
INSERT INTO material_unit_conversions (org_id, material_id, alt_unit_id, factor_to_base)
SELECT o.id,
       (SELECT x.id FROM materials x WHERE x.org_id = o.id AND x.code = c.material_code),
       (SELECT u.id FROM units u WHERE u.org_id = o.id AND u.code = c.alt),
       c.factor
FROM organisations o
CROSS JOIN (VALUES
    -- 1 kg of cement = 0.02 bag (50 kg bags)
    ('CEM-OPC43',  'KG',  0.02000000),
    ('CEM-PPC',    'KG',  0.02000000),
    -- 1 quintal of steel = 100 kg; 1 tonne = 1000 kg
    ('STL-FE500D', 'QTL', 100.00000000),
    ('STL-FE500D', 'MT',  1000.00000000),
    ('AGG-20MM',   'MT',  10.00000000),
    ('AGG-10MM',   'MT',  10.00000000),
    ('SAND-COARSE','MT',  10.00000000),
    ('SAND-FINE',  'MT',  10.00000000)
) AS c(material_code, alt, factor)
WHERE EXISTS (SELECT 1 FROM materials x WHERE x.org_id = o.id AND x.code = c.material_code)
  AND EXISTS (SELECT 1 FROM units u WHERE u.org_id = o.id AND u.code = c.alt)
  AND NOT EXISTS (
      SELECT 1 FROM material_unit_conversions mc
      WHERE mc.org_id = o.id
        AND mc.material_id = (SELECT x.id FROM materials x
                               WHERE x.org_id = o.id AND x.code = c.material_code)
        AND mc.alt_unit_id = (SELECT u.id FROM units u WHERE u.org_id = o.id AND u.code = c.alt)
  );

-- ---------------------------------------------------------------- skill categories
-- The trades the muster roll and the contractor's head counts are both written in.
INSERT INTO skill_categories (org_id, code, name, is_skilled)
SELECT o.id, v.code, v.name, v.skilled
FROM organisations o
CROSS JOIN (VALUES
    ('MASON',       'Mason',        true),
    ('HELPER',      'Helper',       false),
    ('CARPENTER',   'Carpenter',    true),
    ('BAR_BENDER',  'Bar Bender',   true),
    ('ELECTRICIAN', 'Electrician',  true),
    ('PLUMBER',     'Plumber',      true),
    ('PAINTER',     'Painter',      true),
    ('OPERATOR',    'Machine Operator', true)
) AS v(code, name, skilled)
WHERE NOT EXISTS (
    SELECT 1 FROM skill_categories s WHERE s.org_id = o.id AND s.code = v.code
);

-- ---------------------------------------------------------------- expense taxonomy
-- Two levels, following the field sheets. The flags are the double-counting guards:
-- is_labour_payment rows settle wages already costed through attendance, and
-- is_material_purchase rows become inventory value, not direct cost (docs/09). Getting these
-- wrong is not a cosmetic error — it is a site cost that counts the same rupee twice.
INSERT INTO expense_categories (org_id, code, name, parent_id,
                                is_material_purchase, is_labour_payment, requires_vendor, sort_order)
SELECT o.id, v.code, v.name, NULL, false, false, false, v.sort_order
FROM organisations o
CROSS JOIN (VALUES
    ('LABOUR',   'Labour',        1),
    ('MATERIAL', 'Material',      2),
    ('MISC',     'Miscellaneous', 3),
    ('OFFICE',   'Office',        4)
) AS v(code, name, sort_order)
WHERE NOT EXISTS (
    SELECT 1 FROM expense_categories e WHERE e.org_id = o.id AND e.code = v.code
);

INSERT INTO expense_categories (org_id, code, name, parent_id,
                                is_material_purchase, is_labour_payment, requires_vendor, sort_order)
SELECT o.id, v.code, v.name,
       (SELECT p.id FROM expense_categories p WHERE p.org_id = o.id AND p.code = v.parent_code),
       v.material_purchase, v.labour_payment, v.requires_vendor, v.sort_order
FROM organisations o
CROSS JOIN (VALUES
    ('LABOUR-WAGE',     'Worker Wage Payment',          'LABOUR',   false, true,  false, 1),
    ('LABOUR-CONTRACT', 'Labour Contractor Payment',    'LABOUR',   false, true,  false, 2),
    ('MAT-PURCHASE',    'Material Purchase',            'MATERIAL', true,  false, true,  1),
    ('MAT-FREIGHT',     'Material Freight and Cartage', 'MATERIAL', false, false, false, 2),
    ('MISC-TRAVEL',     'Travel',                       'MISC',     false, false, false, 1),
    ('MISC-PETROL',     'Petrol and Diesel',            'MISC',     false, false, false, 2),
    ('MISC-TOOLS',      'Tools and Consumables',        'MISC',     false, false, false, 3),
    ('MISC-SITE',       'Site Expenses',                'MISC',     false, false, false, 4),
    ('MISC-MACHINE',    'Machinery Hire and Repair',    'MISC',     false, false, false, 5),
    ('OFFICE-GEN',      'Office Expenses',              'OFFICE',   false, false, false, 1)
) AS v(code, name, parent_code, material_purchase, labour_payment, requires_vendor, sort_order)
WHERE NOT EXISTS (
    SELECT 1 FROM expense_categories e WHERE e.org_id = o.id AND e.code = v.code
)
AND EXISTS (
    SELECT 1 FROM expense_categories p WHERE p.org_id = o.id AND p.code = v.parent_code
);
