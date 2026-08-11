-- Nirman — a material named at the gate.
--
-- The storekeeper cannot book a delivery of something the catalogue has never heard of, and
-- the lorry does not wait while the office adds it. So the material picker gained a "not in
-- the list" answer, and what he types there becomes a real material — there is nowhere else
-- for it to go. `stock_transactions` keys on a material id, so a free-text name on a receipt
-- line would be a delivery the ledger could not carry.
--
-- `provisional` is what keeps that honest. A row created this way is a name off a challan,
-- not a decision the office made: no HSN, no GST rate, no standard rate, and quite possibly
-- the same cement somebody else already called something else. Flagging it is what lets the
-- office find those rows and merge them, instead of the catalogue quietly filling with
-- near-duplicates nobody can tell apart from the vetted ones. It clears the moment an
-- administrator edits the row, which is the act of vetting it.

ALTER TABLE materials
    ADD COLUMN provisional boolean NOT NULL DEFAULT false;

COMMENT ON COLUMN materials.provisional IS
    'Named from the field on a receipt rather than set up by the office. Cleared when an administrator edits the row.';

-- The office's list of what to tidy up. Small by construction and rarely read, so a partial
-- index rather than a whole one.
CREATE INDEX ix_materials_provisional ON materials (org_id, created_at DESC)
    WHERE provisional AND deleted_at IS NULL;

-- ---------------------------------------------------------------- permission
-- Its own permission, not masterdata:write. Naming a bag of something on a challan and
-- editing the material master are different acts: this one may not set a rate, a GST
-- percentage or an HSN code, and the row it creates is marked as unvetted. Somebody who may
-- book a delivery needs it; it does not follow that he may re-price the catalogue.
INSERT INTO permissions (code, module, description) VALUES
    ('masterdata:provisional', 'masterdata', 'Name a material from the field while booking a delivery');

-- V2 gave ADMIN every permission by CROSS JOIN, but that ran once against the catalogue as it
-- stood then. A permission added later has to be granted explicitly.
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON p.code = 'masterdata:provisional'
WHERE r.code IN ('ADMIN', 'ENGINEER', 'SUPERVISOR') AND r.is_system
  AND NOT EXISTS (
        SELECT 1 FROM role_permissions rp
        WHERE rp.role_id = r.id AND rp.permission_id = p.id);
