-- Nirman — the tax numbers the documents were stating, which were never the firm's.
--
-- `organisations.gstin` held 05ABCDE1234F1Z5 and `pan` held ABCDE1234F. Both are the sample
-- values the dev seed was written with, and both reached production, where they are not
-- decoration: the GSTIN prints on the offer letter's letterhead and the PAN prints on the head
-- of every payslip and every page of the payroll register. So a candidate and every member of
-- staff have been handed a document stating a tax registration that does not exist.
--
-- Neither is a near miss that somebody typed wrong. They do not survive their own arithmetic:
--
--   * 05ABCDE1234F1Z5 fails the GSTIN check digit. The first fourteen characters compute to a
--     15th of '6' and the stored value ends in '5', so no registration anywhere can carry it.
--   * ABCDE1234F is the textbook dummy PAN, and its fourth character — the holder type — is
--     'D', which is not one of the codes the department issues (a firm or LLP is 'F').
--
-- That is why this clears them rather than correcting them: the real numbers are on the
-- registration certificate and nobody typed them here, so there is nothing to preserve and no
-- figure to guess. A letterhead with no GSTIN on it is an omission the office notices the first
-- time it looks; one carrying a number that fails its own checksum is a false statement nobody
-- reads closely enough to catch. The templates already print each line only where the record
-- carries the value, so both simply stop appearing until the office supplies the real ones.
--
-- Guarded on the exact placeholder strings. If either column has since been given a real value
-- this migration passes over it and changes nothing — the point is to remove two known-false
-- numbers, never to empty a field somebody has filled in.
-- ==============================================================================================

UPDATE organisations
   SET gstin = NULL,
       updated_at = now()
 WHERE gstin = '05ABCDE1234F1Z5';

UPDATE organisations
   SET pan = NULL,
       updated_at = now()
 WHERE pan = 'ABCDE1234F';
