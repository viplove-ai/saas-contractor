-- Nirman — development seed: the planning norms, for the seeded organisation.
--
-- V30 gives its starter catalogue to "every organisation that lacks one", which in a real
-- deployment is every organisation there is. In dev and test it is none of them: the
-- organisation itself only arrives in V900, which runs afterwards, so V30's inserts match
-- nothing and the dev database comes up with no norms at all.
--
-- Exactly the situation V14 left for the master data, and this is the same answer one migration
-- along. The statements below are V30's, unchanged, run once the organisation exists. They keep
-- the WHERE NOT EXISTS guard so replaying them changes nothing, and the joins to units,
-- skill_categories and materials mean a row whose vocabulary the seed does not have is skipped
-- rather than failing the build.
--
-- A separate file rather than an edit to V900-V903: those have been applied, and Flyway
-- checksums an applied script.

INSERT INTO work_type_profiles (org_id, code, name, description, phase_basis,
                                monsoon_sensitive, default_overhead_percent)
SELECT o.id, v.code, v.name, v.description, v.phase_basis, v.monsoon, v.overhead
FROM organisations o
CROSS JOIN (VALUES
    ('BUILDING_NEW',   'Building - new construction',
     'Substructure, then superstructure by floor, then finishes. The default.',
     'FLOOR',    true,  8.00),
    ('BUILDING_MAINT', 'Building - repair and maintenance',
     'Many small scattered packages, largely parallel. CPWD''s commonest tender.',
     'PARALLEL', false, 10.00),
    ('ROAD',           'Road and pavement',
     'Phased by chainage. Earthwork and bituminous work stop in rain.',
     'CHAINAGE', true,  7.00),
    ('WATER_SANITARY', 'Water supply and sanitary',
     'Phased by line or block. Trenching is weather and permission bound.',
     'BLOCK',    true,  8.00),
    ('ELECTRICAL_EM',  'Electrical and E&M',
     'Follows the civil work and cannot lead it; phases depend on the civil plan.',
     'FLOOR',    false, 9.00),
    ('HORTICULTURE',   'Horticulture and landscape',
     'Season bound. Planting windows are not negotiable.',
     'SEASON',   true,  10.00),
    ('COMPOSITE',      'Composite civil and E&M',
     'Two interlocked curves. The case boq_items.work_part already exists for.',
     'FLOOR',    true,  8.00)
) AS v(code, name, description, phase_basis, monsoon, overhead)
WHERE NOT EXISTS (
    SELECT 1 FROM work_type_profiles w WHERE w.org_id = o.id AND w.code = v.code
);

INSERT INTO labour_productivity_norms (org_id, work_category, work_sub_type, skill_category_id,
                                       work_unit_id, man_days_per_work_unit, source, notes)
SELECT o.id, v.category, v.sub_type, s.id, u.id, v.man_days, 'INTERNAL',
       'Starter value. Replace with your own site figures once a project has finished.'
FROM organisations o
CROSS JOIN (VALUES
    ('Earthwork',                        NULL,         'HELPER',     'CUM',  0.330000),
    ('Earthwork',                        NULL,         'OPERATOR',   'CUM',  0.020000),

    ('Concrete & RCC',                   NULL,         'MASON',      'CUM',  0.200000),
    ('Concrete & RCC',                   NULL,         'HELPER',     'CUM',  1.300000),
    ('Concrete & RCC',                   'Formwork',   'CARPENTER',  'SQM',  0.100000),
    ('Concrete & RCC',                   'Formwork',   'HELPER',     'SQM',  0.100000),

    ('Reinforcement & Structural Steel', NULL,         'BAR_BENDER', 'KG',   0.010000),
    ('Reinforcement & Structural Steel', NULL,         'HELPER',     'KG',   0.010000),

    ('Masonry',                          NULL,         'MASON',      'CUM',  0.900000),
    ('Masonry',                          NULL,         'HELPER',     'CUM',  1.100000),

    ('Roofing & Waterproofing',          NULL,         'MASON',      'SQM',  0.120000),
    ('Roofing & Waterproofing',          NULL,         'HELPER',     'SQM',  0.150000),

    ('Flooring & Finishes',              NULL,         'MASON',      'SQM',  0.100000),
    ('Flooring & Finishes',              NULL,         'HELPER',     'SQM',  0.120000),
    ('Flooring & Finishes',              'Painting',   'PAINTER',    'SQM',  0.025000),
    ('Flooring & Finishes',              'Painting',   'HELPER',     'SQM',  0.010000),

    ('Doors, Windows & Joinery',         NULL,         'CARPENTER',  'SQM',  0.350000),
    ('Doors, Windows & Joinery',         NULL,         'HELPER',     'SQM',  0.200000),

    ('Plumbing & Sanitary',              NULL,         'PLUMBER',    'MTR',  0.120000),
    ('Plumbing & Sanitary',              NULL,         'HELPER',     'MTR',  0.100000),

    ('Electrical',                       NULL,         'ELECTRICIAN','NOS',  0.350000),
    ('Electrical',                       NULL,         'HELPER',     'NOS',  0.300000),

    ('External Development',             NULL,         'MASON',      'SQM',  0.080000),
    ('External Development',             NULL,         'HELPER',     'SQM',  0.150000)
) AS v(category, sub_type, skill_code, unit_code, man_days)
JOIN skill_categories s ON s.org_id = o.id AND s.code = v.skill_code
JOIN units u            ON u.org_id = o.id AND u.code = v.unit_code
WHERE NOT EXISTS (
    SELECT 1 FROM labour_productivity_norms n
     WHERE n.org_id = o.id
       AND n.work_category = v.category
       AND COALESCE(n.work_sub_type, '') = COALESCE(v.sub_type, '')
       AND n.skill_category_id = s.id
);

INSERT INTO work_sequence_norms (org_id, work_type_profile_id, work_category, sequence_rank,
                                 max_overlap_percent, max_concurrent_gangs, monsoon_sensitive)
SELECT o.id, NULL, v.category, v.rank, v.overlap, v.gangs, v.monsoon
FROM organisations o
CROSS JOIN (VALUES
    ('Testing & Investigation',          1,  0.00,  2, false),
    ('Earthwork',                        2,  20.00, 4, true),
    ('Reinforcement & Structural Steel', 3,  30.00, 4, false),
    ('Concrete & RCC',                   4,  50.00, 3, true),
    ('Masonry',                          5,  40.00, 6, false),
    ('Roofing & Waterproofing',          6,  20.00, 3, true),
    ('Plumbing & Sanitary',              7,  50.00, 4, false),
    ('Electrical',                       8,  60.00, 4, false),
    ('Doors, Windows & Joinery',         9,  40.00, 4, false),
    ('Flooring & Finishes',             10,  30.00, 8, false),
    ('HVAC & Mechanical',               11,  40.00, 3, false),
    ('Firefighting & Fire Alarm',       12,  40.00, 3, false),
    ('IT, CCTV & Communications',       13,  50.00, 3, false),
    ('Solar & Renewable Energy',        14,  50.00, 2, false),
    ('External Development',            15,  30.00, 4, true),
    ('Miscellaneous',                   16,  80.00, 4, false)
) AS v(category, rank, overlap, gangs, monsoon)
WHERE NOT EXISTS (
    SELECT 1 FROM work_sequence_norms n
     WHERE n.org_id = o.id AND n.work_type_profile_id IS NULL AND n.work_category = v.category
);

INSERT INTO material_lead_times (org_id, material_id, lead_days, buffer_days, shelf_life_days,
                                 storable, notes)
SELECT o.id, m.id, v.lead, v.buffer, v.shelf, true,
       'Starter value. Replace with your own suppliers'' actual lead times.'
FROM organisations o
CROSS JOIN (VALUES
    ('CEM-OPC43',    3, 2,   90),
    ('CEM-PPC',      3, 2,   90),
    ('STL-FE500D',  10, 5, NULL),
    ('STL-BINDWIRE', 5, 3, NULL),
    ('AGG-20MM',     3, 2, NULL),
    ('AGG-10MM',     3, 2, NULL),
    ('SAND-COARSE',  4, 3, NULL),
    ('SAND-FINE',    4, 3, NULL),
    ('BRICK-1C',     7, 3, NULL),
    ('MISC-PLY',     7, 3, NULL)
) AS v(material_code, lead, buffer, shelf)
JOIN materials m ON m.org_id = o.id AND m.code = v.material_code
WHERE NOT EXISTS (
    SELECT 1 FROM material_lead_times t WHERE t.org_id = o.id AND t.material_id = m.id
);
