-- Nirman — the supplier's account, which the system could describe but not keep.
--
-- Everything about a vendor was here except the two things anybody actually asks about him:
-- what has he sent us, and where does his account stand. The master row carried his address,
-- his GSTIN and his bank details; the deliveries carried his name; the payments carried his
-- id. What was missing was the arithmetic that ties them together, and one act it could not
-- record at all.
--
-- That act is the advance. `PaymentService` already tells the accountant to "record the
-- excess as a separate advance to the vendor rather than against this bill" — and there was
-- no way to do it. Money paid before a bill exists is how a lorry of steel gets loaded, and
-- an accounting system that cannot record it makes the accountant keep a second book.
--
-- A payment with a vendor and no expense is that advance. It is not a new table: it is the
-- payments table finally allowed to mean what its nullable expense_id always implied.

-- ---------------------------------------------------------------- a payment names somebody
-- Either the bill it settles or the supplier it is on account with. A payment that names
-- neither is money that left with nothing to reconcile against, which is the one row this
-- table must never hold — it would sit in the cash total and in no vendor's account.
ALTER TABLE payments
    ADD CONSTRAINT ck_payment_has_a_counterparty
    CHECK (expense_id IS NOT NULL OR vendor_id IS NOT NULL);

COMMENT ON COLUMN payments.expense_id IS
    'The bill this settles. Null for an advance paid on account, which is money against the '
    'vendor rather than against any one of his bills; it is set off as his bills arrive.';

-- His account, in date order, is the read. Both halves of it are keyed the same way.
CREATE INDEX ix_payments_vendor_account ON payments (org_id, vendor_id, payment_date)
    WHERE vendor_id IS NOT NULL;

-- ---------------------------------------------------------------- who onboards a supplier
-- Its own permission, taken off masterdata:write. Onboarding a dealer is the accountant's
-- work — he is the one holding the GSTIN, the bank details and the credit terms — and the
-- accountant deliberately does not hold masterdata:write, because units, materials and the
-- expense taxonomy are the administrator's and a mistake there reaches every screen.
INSERT INTO permissions (code, module, description) VALUES
    ('vendor:write', 'masterdata', 'Onboard and edit vendors and dealers');

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
  FROM roles r
  JOIN permissions p ON p.code = 'vendor:write'
 WHERE r.code IN ('ADMIN', 'ACCOUNTANT')
   AND r.is_system
   AND NOT EXISTS (SELECT 1 FROM role_permissions rp
                    WHERE rp.role_id = r.id AND rp.permission_id = p.id);

-- vendor:balance:manage already exists and already sits with ADMIN and ACCOUNTANT, so the
-- account page needs no new grant: what somebody may see of a supplier's money is the same
-- question it always was.

-- ---------------------------------------------------------------- the purchase history
-- No new table. What came from a supplier, in what quantity and at what rate, is already
-- written down — in goods_receipts and their lines, which carry his id, his invoice number
-- and the rate the office put on each line. Storing it a second time on the vendor would be
-- a second version of the truth, and the version nobody posts to.
--
-- What it lacked was an index for the question "everything from this supplier", which until
-- now was only ever asked as "this supplier and this invoice".
CREATE INDEX ix_grn_vendor_date ON goods_receipts (org_id, vendor_id, receipt_date DESC)
    WHERE vendor_id IS NOT NULL;
