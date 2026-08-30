-- Nirman — a supplier named at the gate, by the man the lorry is standing in front of.
--
-- The third turn of the screw V15 and V24 already made twice, on the register that had it
-- worst. `vendor:write` is the accountant's, and rightly so: he holds the GSTIN, the bank
-- details and the credit terms, and a mistake in any of them is money paid to the wrong
-- account. But a supplier is also the one master row a supervisor cannot get through a day
-- without — he names him on a delivery, on a day's outsourced labour, and on a bill he has
-- just paid out of his own float — and until now the answer to "he is not in the list" was
-- a telephone call to the office.
--
-- So the field may name him: what he is called, what he supplies, and how to reach him.
-- Nothing else, which is the invariant this endpoint's two elder twins were built on — the
-- field may name a thing and never value it. The tax numbers, the bank account and the
-- credit days are exactly the valuing, and they stay where they are.
--
-- `provisional` is what keeps that honest, and it does the same job here as on materials: a
-- row named this way is a firm's name off a challan, not a decision the office made, and
-- flagging it is what lets the office find the rows that still need a GSTIN before his tax
-- can be claimed back. It clears the moment somebody holding vendor:write edits the row,
-- which is the act of vetting it.

ALTER TABLE vendors
    ADD COLUMN provisional boolean NOT NULL DEFAULT false;

COMMENT ON COLUMN vendors.provisional IS
    'Named from the field with contact details only — no tax, bank or credit terms. Cleared when somebody holding vendor:write edits the row.';

-- The office's list of what to complete. Small by construction and rarely read, so a partial
-- index rather than a whole one.
CREATE INDEX ix_vendors_provisional ON vendors (org_id, created_at DESC)
    WHERE provisional AND deleted_at IS NULL;

-- ---------------------------------------------------------------- permission
-- Its own permission, not vendor:write. Naming the firm whose lorry is at the gate and
-- onboarding a dealer are different acts: this one may not set a GSTIN, a bank account, a
-- credit period or an opening balance, may not make a supplier inactive, and the row it
-- creates is marked as unvetted. Somebody who may book a delivery needs it; it does not
-- follow that he may put the office's numbers on the firm that sent it.
INSERT INTO permissions (code, module, description) VALUES
    ('masterdata:provisional:supplier', 'masterdata',
     'Name a supplier from the field and keep his contact details current');

-- V2 gave ADMIN every permission by CROSS JOIN, but that ran once against the catalogue as
-- it stood then. A permission added later has to be granted explicitly. The same three roles
-- V15 and V24 named: the two who stand at a gate, and the administrator who holds
-- everything anyway.
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
  FROM roles r
  JOIN permissions p ON p.code = 'masterdata:provisional:supplier'
 WHERE r.code IN ('ADMIN', 'ENGINEER', 'SUPERVISOR')
   AND r.is_system
   AND NOT EXISTS (SELECT 1 FROM role_permissions rp
                    WHERE rp.role_id = r.id AND rp.permission_id = p.id);
