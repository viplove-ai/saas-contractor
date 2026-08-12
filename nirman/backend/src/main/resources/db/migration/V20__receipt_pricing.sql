-- Nirman — what came off the lorry, and what it cost, are two different people's answers.
--
-- The receive screen asked the storekeeper for a rate, and he is the one person at the site
-- who does not know it. He has a challan in his hand; the challan is a delivery note and
-- often carries no price at all. So the number he typed was a guess, or last month's rate,
-- or whatever the driver said — and it went straight into the moving average that values
-- every issue afterwards. A wrong rate on a receipt is not a wrong line on a document, it
-- is a wrong cost on every job that draws that material for months.
--
-- The rate is the office's answer, against the invoice. It is set before the receipt is
-- verified, by whoever holds the new permission, and verification refuses a receipt with a
-- line still unpriced — which is the point at which stock moves, so nothing enters the
-- ledger without a value behind it.
--
-- Two halves: a rate that may be absent for a while, and a permission that says who may
-- supply it.

-- ---------------------------------------------------------------- a rate may be missing
-- Not a default of zero. Zero is a real rate — free issue from the department happens — and
-- a receipt that says zero is a receipt somebody priced. Null is nobody having priced it
-- yet, and the two must not print the same.
ALTER TABLE goods_receipt_items ALTER COLUMN rate DROP NOT NULL;
ALTER TABLE goods_receipt_items ALTER COLUMN rate_base DROP NOT NULL;

COMMENT ON COLUMN goods_receipt_items.rate IS
    'Per entered unit, excluding tax. Null until the office prices the line against the '
    'invoice; verification refuses a receipt that still has one.';

-- ---------------------------------------------------------------- who may price it
INSERT INTO permissions (code, module, description) VALUES
    ('inventory:price', 'inventory', 'Set the rate on a goods receipt');

-- V2 gave ADMIN every permission by CROSS JOIN, but that ran against the catalogue as it
-- stood then; a permission added later has to be granted explicitly.
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
  FROM roles r
  JOIN permissions p ON p.code = 'inventory:price'
 WHERE r.code IN ('ADMIN', 'ENGINEER')
   AND r.is_system
   AND NOT EXISTS (SELECT 1 FROM role_permissions rp
                    WHERE rp.role_id = r.id AND rp.permission_id = p.id);

-- The engineer and not the accountant, deliberately. The engineer already holds
-- inventory:verify and is the one standing over the delivery with the paperwork; pricing and
-- checking are one act at a site, and splitting them across two people who are never in the
-- same place would leave receipts sitting unverified for a week. The accountant keeps the
-- books and can see every figure — what they cannot do is originate one against a delivery
-- they did not see.

-- ---------------------------------------------------------------- the ones already booked
-- Nothing to backfill. Every existing line has a rate, because the column was NOT NULL
-- until this migration; what changes is only what the next receipt is allowed to leave out.
