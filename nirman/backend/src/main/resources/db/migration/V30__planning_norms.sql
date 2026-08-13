-- ==============================================================================================
-- V30 — the norms a plan is derived from
--
-- The chain the planner runs on is: BOQ line -> category -> norms -> time, men, material. Two of
-- the three links already exist. boq_items.category is populated by the NIT classifier and
-- shares a string space with material_consumption_norms.work_category, which was built for
-- exactly this. What is missing is everything about labour and time.
--
-- Four tables, and each answers one question the engine cannot answer without it:
--
--   labour_productivity_norms  how many man-days of which trade does a unit of this work take
--   work_sequence_norms        what must finish before it starts, and how many gangs fit
--   material_lead_times        how far ahead must it be ordered, and how long will it keep
--   work_type_profiles         which of the above applies, because a road is not a school
--
-- Every value shipped here is a STARTING value. A contractor's own productivity is his
-- competitive advantage and the number he will most want to correct, so all of it is org-scoped
-- and editable, and every row records where it came from. Once two or three projects have
-- finished, these can be derived from the org's own verified attendance and stock ledger — which
-- is the version of this worth building second, and why `source` exists from the start.
--
-- Seeded with V14's guard: every organisation that lacks a row gets one, nothing overwrites what
-- an organisation has already entered, and the joins to units and skill_categories mean a row
-- whose vocabulary an org does not have is skipped rather than failing the migration.
--
-- See docs/10-planning-and-execution-strategy.md §4.
-- ==============================================================================================

-- ---------------------------------------------------------------- work type profiles
-- A school, a road, an internal water supply scheme and an E&M package are four different plans.
-- A single sequencing rule flatters all four and fits none, so the kind of work is a first-class
-- thing that selects the rest.
CREATE TABLE work_type_profiles (
    id                       uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id                   uuid NOT NULL REFERENCES organisations(id),
    code                     varchar(40)  NOT NULL,
    name                     varchar(120) NOT NULL,
    description              text,

    -- What a phase is cut by on this kind of work. A building phases by floor, a road by
    -- chainage, and a maintenance tender does not phase at all — it is many small packages
    -- running beside each other, which is CPWD's commonest tender and the worst fit for the
    -- floor-based default.
    phase_basis              varchar(20) NOT NULL DEFAULT 'FLOOR',

    -- Whether the monsoon stops this work rather than merely slowing it. On the two-to-twelve
    -- month tenders in the corpus a wet season is not a seasonal adjustment, it is potentially
    -- the whole programme.
    monsoon_sensitive        boolean NOT NULL DEFAULT false,

    -- The contractor's own establishment and overhead, as a share of contract value. A starting
    -- figure; the org's accounts are the real answer.
    default_overhead_percent numeric(5,2) NOT NULL DEFAULT 8.00,

    is_active                boolean NOT NULL DEFAULT true,
    created_at               timestamptz NOT NULL DEFAULT now(),
    updated_at               timestamptz NOT NULL DEFAULT now(),
    created_by               uuid,
    updated_by               uuid,
    version                  bigint NOT NULL DEFAULT 0,

    CONSTRAINT uq_wtp_org_code UNIQUE (org_id, code),
    CONSTRAINT ck_wtp_phase_basis
        CHECK (phase_basis IN ('FLOOR', 'CHAINAGE', 'BLOCK', 'PARALLEL', 'SEASON')),
    CONSTRAINT ck_wtp_overhead
        CHECK (default_overhead_percent >= 0 AND default_overhead_percent <= 100)
);

-- ---------------------------------------------------------------- labour productivity
-- Man-days of one trade per unit of work in a category. Two masons and three helpers per 10 cum
-- of brickwork per day is the shape of it.
--
-- Keyed exactly as material_consumption_norms is — work_category plus an optional sub-type — so
-- one classification of a BOQ line serves both, and referencing skill_categories so the
-- skilled/unskilled split falls out of data the org already holds rather than being restated.
CREATE TABLE labour_productivity_norms (
    id                     uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id                 uuid NOT NULL REFERENCES organisations(id),
    work_category          varchar(80) NOT NULL,
    work_sub_type          varchar(80),                       -- grade or operation: M25, Painting
    skill_category_id      uuid NOT NULL REFERENCES skill_categories(id),
    work_unit_id           uuid NOT NULL REFERENCES units(id),
    man_days_per_work_unit numeric(18,6) NOT NULL,
    source                 varchar(20) NOT NULL DEFAULT 'INTERNAL',
    is_active              boolean NOT NULL DEFAULT true,
    notes                  text,
    created_at             timestamptz NOT NULL DEFAULT now(),
    updated_at             timestamptz NOT NULL DEFAULT now(),
    created_by             uuid,
    updated_by             uuid,
    version                bigint NOT NULL DEFAULT 0,

    CONSTRAINT ck_lpn_qty CHECK (man_days_per_work_unit > 0),
    CONSTRAINT ck_lpn_source CHECK (source IN ('CPWD_AOR', 'INTERNAL', 'PROJECT'))
);
-- An index rather than a table constraint, for the reason uq_norm_scope gives in V1: work_sub_type
-- is nullable and NULLs compare as distinct, so the same norm could otherwise be entered twice.
CREATE UNIQUE INDEX uq_lpn_scope ON labour_productivity_norms
    (org_id, work_category, COALESCE(work_sub_type, ''), skill_category_id);
CREATE INDEX ix_lpn_category ON labour_productivity_norms (org_id, work_category)
    WHERE is_active;

-- ---------------------------------------------------------------- sequence and working front
-- Plaster does not precede masonry, and painting does not precede plaster. Some overlap is
-- normal and some is impossible, and the difference is per trade.
--
-- max_concurrent_gangs is the constraint a naive planner forgets. Dividing the work by the time
-- available always yields a head count, and on a compressed programme it yields one no site can
-- physically hold — four hundred masons on a two-hundred-square-metre slab. A plan has to be
-- limited by the working front, not only by arithmetic.
CREATE TABLE work_sequence_norms (
    id                   uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id               uuid NOT NULL REFERENCES organisations(id),
    -- Null means the rule holds whatever the kind of work. A profile-specific row overrides it.
    work_type_profile_id uuid REFERENCES work_type_profiles(id),
    work_category        varchar(80) NOT NULL,
    sequence_rank        int NOT NULL,
    -- How far into its predecessor this work may start, as a share of the predecessor's
    -- duration. Electrical conduiting runs inside the slab it is cast into; plaster cannot.
    max_overlap_percent  numeric(5,2) NOT NULL DEFAULT 0,
    max_concurrent_gangs int NOT NULL DEFAULT 4,
    monsoon_sensitive    boolean NOT NULL DEFAULT false,
    is_active            boolean NOT NULL DEFAULT true,
    notes                text,
    created_at           timestamptz NOT NULL DEFAULT now(),
    updated_at           timestamptz NOT NULL DEFAULT now(),
    created_by           uuid,
    updated_by           uuid,
    version              bigint NOT NULL DEFAULT 0,

    CONSTRAINT ck_wsn_rank CHECK (sequence_rank > 0),
    CONSTRAINT ck_wsn_overlap CHECK (max_overlap_percent >= 0 AND max_overlap_percent <= 100),
    CONSTRAINT ck_wsn_gangs CHECK (max_concurrent_gangs > 0)
);
CREATE UNIQUE INDEX uq_wsn_scope ON work_sequence_norms
    (org_id, COALESCE(work_type_profile_id, '00000000-0000-0000-0000-000000000000'::uuid),
     work_category);

-- ---------------------------------------------------------------- material lead times
-- What separates the requirement curve from the procurement curve. The first answers "what will
-- be consumed in March"; the second answers "what has to be ordered in January so March can
-- happen". Conflating them is how a site runs out of cement while the plan says it has plenty.
--
-- shelf_life_days and storable are what stop the answer being "order the whole job in month one".
-- Cement keeps about three months; ready-mix concrete keeps ninety minutes.
CREATE TABLE material_lead_times (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id          uuid NOT NULL REFERENCES organisations(id),
    material_id     uuid NOT NULL REFERENCES materials(id),
    lead_days       int NOT NULL DEFAULT 7,
    -- Held on top of the lead time, because a delivery that is late by its own average is late.
    buffer_days     int NOT NULL DEFAULT 3,
    shelf_life_days int,                                  -- null = does not deteriorate
    storable        boolean NOT NULL DEFAULT true,
    is_active       boolean NOT NULL DEFAULT true,
    notes           text,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    created_by      uuid,
    updated_by      uuid,
    version         bigint NOT NULL DEFAULT 0,

    CONSTRAINT uq_mlt_org_material UNIQUE (org_id, material_id),
    CONSTRAINT ck_mlt_lead CHECK (lead_days >= 0 AND buffer_days >= 0),
    CONSTRAINT ck_mlt_shelf CHECK (shelf_life_days IS NULL OR shelf_life_days > 0)
);

-- ==============================================================================================
-- Starter catalogue
-- ==============================================================================================

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

-- ---------------------------------------------------------------- productivity
-- Man-days per unit of work, per trade. These are planning figures of the kind an estimator
-- carries in his head — one mason lays about a cubic metre of brickwork a day, one painter
-- covers about forty square metres in two coats — and they are marked INTERNAL rather than
-- CPWD_AOR because they are round numbers to start from, not a citation of the Analysis of
-- Rates. Correcting them against the org's own muster is the point.
--
-- The joins to units and skill_categories are the guard: a row whose unit or trade an
-- organisation does not have is skipped, rather than failing the migration for everyone.
INSERT INTO labour_productivity_norms (org_id, work_category, work_sub_type, skill_category_id,
                                       work_unit_id, man_days_per_work_unit, source, notes)
SELECT o.id, v.category, v.sub_type, s.id, u.id, v.man_days, 'INTERNAL',
       'Starter value. Replace with your own site figures once a project has finished.'
FROM organisations o
CROSS JOIN (VALUES
    -- category                          sub_type      skill         unit    man-days/unit
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

-- ---------------------------------------------------------------- sequence
-- The default ordering, holding for any kind of work until a profile-specific row overrides it.
-- Reinforcement outranks concrete because the steel is tied before the pour, even though the two
-- interleave on site — which is what max_overlap_percent is for.
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

-- ---------------------------------------------------------------- lead times
INSERT INTO material_lead_times (org_id, material_id, lead_days, buffer_days, shelf_life_days,
                                 storable, notes)
SELECT o.id, m.id, v.lead, v.buffer, v.shelf, true,
       'Starter value. Replace with your own suppliers'' actual lead times.'
FROM organisations o
CROSS JOIN (VALUES
    -- Cement keeps about three months in a dry shed, which is what stops a plan buying the
    -- whole job's cement in month one.
    ('CEM-OPC43',    3, 2,   90),
    ('CEM-PPC',      3, 2,   90),
    -- Reinforcement is rolled to order and is the longest ordinary lead on a building.
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

-- ==============================================================================================
-- Permissions
--
-- Only the two the code now uses. planning:generate and planning:baseline arrive with the engine
-- that can honour them — a permission nothing checks is a permission nobody can rely on.
-- ==============================================================================================
INSERT INTO permissions (code, module, description) VALUES
    ('planning:read',        'planning', 'View the planning norms and work type profiles'),
    ('planning:norms:write', 'planning', 'Correct the productivity, sequence and lead time norms');

-- V2 granted ADMIN everything by CROSS JOIN over the catalogue as it stood then; anything added
-- later has to be granted explicitly.
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
  FROM roles r
  JOIN permissions p ON p.code IN ('planning:read', 'planning:norms:write')
 WHERE r.code = 'ADMIN' AND r.is_system
   AND NOT EXISTS (SELECT 1 FROM role_permissions rp
                    WHERE rp.role_id = r.id AND rp.permission_id = p.id);

-- A norm quietly changed is every future plan quietly changed, so only an administrator writes
-- them. The engineer and the accountant read: one is asked how long the work will take, and the
-- other is asked what it will cost.
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
  FROM roles r
  JOIN permissions p ON p.code = 'planning:read'
 WHERE r.code IN ('ENGINEER', 'ACCOUNTANT') AND r.is_system
   AND NOT EXISTS (SELECT 1 FROM role_permissions rp
                    WHERE rp.role_id = r.id AND rp.permission_id = p.id);
