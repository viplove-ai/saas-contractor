-- Nirman — the plant standing at a site, as a register rather than as somebody's memory.
--
-- A mixer, a vibrator, three centering plates and a hired JCB are not stock. Stock is
-- consumed: it arrives, it is issued to a work item, and the ledger says what is left.
-- Equipment is *held* — the same mixer is at the site in March and in June — so putting it
-- through `stock_transactions` would say a mixer was consumed by the raft slab and leave the
-- store's balance claiming there is nothing to pour with next week. It is a different kind of
-- fact and it gets its own table.
--
-- What it shares with stock is where it lives, which is why it hangs off a store: "at the
-- site" is not an answer when a site keeps a locked yard and an open one, and the screen
-- somebody reaches for this on is the store's.
--
-- ---------------------------------------------------------------- who may say what
-- The one rule the whole feature turns on: anybody at the site may say a machine is here,
-- and only the office may agree. A supervisor who cannot enter the mixer he is looking at
-- will not enter it at all, and the register is then a list of what the office remembered.
-- But an entry nobody checked is a claim, and a claim that counts as an asset is how a hired
-- breaker that went back on Tuesday stays on the register for a year. So an entry is PENDING
-- until an administrator accepts it, and the screens show the two apart.
--
-- An administrator's own entry arrives ACCEPTED. Asking him to approve himself is a ceremony
-- with no second pair of eyes in it, and a workflow that everybody learns to click through is
-- worse than no workflow.

CREATE TABLE site_equipment (
    id             uuid PRIMARY KEY,
    org_id         uuid NOT NULL REFERENCES organisations(id),
    site_id        uuid NOT NULL REFERENCES sites(id),
    -- Where at the site it is kept. Not null: a nullable store means "somewhere on site",
    -- which is the answer this register exists to stop being acceptable.
    store_id       uuid NOT NULL REFERENCES stores(id),

    name           varchar(150) NOT NULL,
    -- The number painted on it, or its registration. Nullable, because a site owns four
    -- identical centering frames and nobody has ever numbered them; unique per organisation
    -- when it is there, because two rows carrying one registration is two machines that are
    -- one machine.
    asset_code     varchar(60),

    -- How many, for the things that come in fours. A single machine is 1 and that is the
    -- default, so nobody counts a JCB.
    quantity       int NOT NULL DEFAULT 1,

    -- OWNED plant is an asset the contractor is carrying; HIRED is a running cost and
    -- somebody is being paid for every day it stands there. Reading the register without
    -- knowing which is reading half of it.
    ownership      varchar(20) NOT NULL DEFAULT 'OWNED',
    -- WORKING, IDLE or UNDER_REPAIR. A machine on site and broken is not capacity, and the
    -- daily report's plant section is the reason anybody looks.
    condition      varchar(20) NOT NULL DEFAULT 'WORKING',

    -- Who it is hired from, when it is hired. A vendor rather than free text: the man who
    -- sends a JCB is a supplier like the man who sends cement, which is what V23 settled.
    supplier_id    uuid REFERENCES vendors(id),
    remarks        text,

    status         varchar(20) NOT NULL DEFAULT 'PENDING',
    decided_at     timestamptz,
    decided_by     uuid REFERENCES users(id),
    decision_remarks varchar(500),

    -- Soft, like every other register here. A machine deleted in June must not take its own
    -- history out of March's report with it.
    deleted_at     timestamptz,
    deleted_by     uuid REFERENCES users(id),

    created_at     timestamptz NOT NULL DEFAULT now(),
    updated_at     timestamptz NOT NULL DEFAULT now(),
    created_by     uuid,
    updated_by     uuid,
    version        bigint NOT NULL DEFAULT 0,

    CONSTRAINT ck_equipment_quantity CHECK (quantity > 0),
    CONSTRAINT ck_equipment_ownership CHECK (ownership IN ('OWNED', 'HIRED')),
    CONSTRAINT ck_equipment_condition
        CHECK (condition IN ('WORKING', 'IDLE', 'UNDER_REPAIR')),
    CONSTRAINT ck_equipment_status CHECK (status IN ('PENDING', 'ACCEPTED', 'REJECTED')),
    -- A decision is a decision by somebody at a time, or it is not one.
    CONSTRAINT ck_equipment_decision_is_whole
        CHECK (status = 'PENDING' OR (decided_at IS NOT NULL))
);

COMMENT ON TABLE site_equipment IS
    'Plant held at a site: what it is, whose it is, and whether the office has accepted that '
    'it is there. Not stock — equipment is held rather than consumed, and a ledger posting '
    'would report a mixer as used up by a slab.';

COMMENT ON COLUMN site_equipment.status IS
    'PENDING until an administrator accepts it. Anybody at the site may enter a machine; '
    'only the office may agree that it is on the register.';

-- One registration is one machine.
CREATE UNIQUE INDEX uq_equipment_asset_code ON site_equipment (org_id, upper(btrim(asset_code)))
    WHERE asset_code IS NOT NULL AND btrim(asset_code) <> '' AND deleted_at IS NULL;

-- The screen's own query: this store's register, newest first.
CREATE INDEX ix_equipment_store ON site_equipment (store_id, status, created_at DESC)
    WHERE deleted_at IS NULL;

-- The administrator's queue, which is the same query across every site he runs.
CREATE INDEX ix_equipment_pending ON site_equipment (org_id, created_at DESC)
    WHERE status = 'PENDING' AND deleted_at IS NULL;

-- ---------------------------------------------------------------- permissions
-- Four, because the acts really are four and the split is the whole feature. Reading the
-- register goes with reading the store. Adding is the field's. Accepting and editing are the
-- office's, and are separate from each other: the day somebody keeps the plant register
-- without being trusted to accept entries onto it, the split is already there.
INSERT INTO permissions (code, module, description) VALUES
    ('equipment:read',    'inventory', 'View the equipment held at a site'),
    ('equipment:create',  'inventory', 'Enter equipment standing at a site, for acceptance'),
    ('equipment:approve', 'inventory', 'Accept or reject an equipment entry'),
    ('equipment:write',   'inventory', 'Correct or remove an equipment entry');

-- V2 granted ADMIN everything by CROSS JOIN over the catalogue as it stood then; anything
-- added later has to be granted explicitly.
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
  FROM roles r
  JOIN permissions p ON p.code IN ('equipment:read', 'equipment:create',
                                   'equipment:approve', 'equipment:write')
 WHERE r.code = 'ADMIN' AND r.is_system
   AND NOT EXISTS (SELECT 1 FROM role_permissions rp
                    WHERE rp.role_id = r.id AND rp.permission_id = p.id);

-- The two roles that stand at the site enter what they can see, and no more.
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
  FROM roles r
  JOIN permissions p ON p.code IN ('equipment:read', 'equipment:create')
 WHERE r.code IN ('ENGINEER', 'SUPERVISOR') AND r.is_system
   AND NOT EXISTS (SELECT 1 FROM role_permissions rp
                    WHERE rp.role_id = r.id AND rp.permission_id = p.id);

-- The accountant reads the stores already, and hired plant is a running cost he is the first
-- to be asked about.
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
  FROM roles r
  JOIN permissions p ON p.code = 'equipment:read'
 WHERE r.code = 'ACCOUNTANT' AND r.is_system
   AND NOT EXISTS (SELECT 1 FROM role_permissions rp
                    WHERE rp.role_id = r.id AND rp.permission_id = p.id);
