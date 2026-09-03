-- Nirman — the letter reference carries the firm's name too.
--
-- V57 put the firm's real name across the top of the offer letter and deliberately left
-- `organisations.code` alone, on the argument that a reference number has to keep matching the
-- paper already in the drawer. That argument holds for references already issued and not for
-- the ones still to be written: a letter headed Shivadri Projects and numbered NIRMAN/HR/2026
-- names two firms on one page, and the candidate reading it is the person least able to know
-- they are the same one.
--
-- The column is worth renaming precisely because of how little it reaches. It is read in one
-- place in the whole system — `OfferLetterService.defaultReference`, which builds
-- CODE/HR/<year>/<employee number> — so this changes the prefix of references written from now
-- on and nothing else. Nothing is keyed on it, no file is named after it, and no earlier
-- document is touched: a letter already filed keeps the reference printed on it, in
-- `staff_documents.note`, where it goes on matching the copy the candidate holds.
--
-- Guarded by the old value for the same reason V57 was, and for the same reason it had to be a
-- migration at all: `organisations` has an entity, a repository and no controller, so there is
-- no screen from which an office could correct its own record.
-- ==============================================================================================

UPDATE organisations
   SET code = 'SHIVADRI',
       updated_at = now()
 WHERE code = 'NIRMAN';
