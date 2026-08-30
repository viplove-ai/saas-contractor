-- Nirman — what "Other" means, in the words of the person who picked it.
--
-- The supplier register asks what a firm supplies off a closed list of five, and the fifth is
-- Other. That answer is honest and it is also empty: a register row reading "Kausani Scaffolding
-- Works — Other" tells the next reader precisely what he already knew, which is that none of the
-- four fitted. And the four will never fit everything — the scaffolding hire, the surveyor, the
-- man with the water tanker, the JCB on a monthly contract — so Other is not a gap to be closed
-- by lengthening the list. It is a real answer that needs a sentence beside it.
--
-- The same split V34 drew for a lost day, and for the same two reasons. The list is the half
-- that can be counted — "how many transporters do we deal with" is a question an office asks,
-- and free text spelled four ways answers it four times — and the note is the half a person
-- reads. Neither does the other's work.
--
-- The note is refused on any other kind, and that direction is the one the database can check:
-- "Material dealer" with "supplies scaffolding" written beside it is two answers to one
-- question, and the second one is invisible on every screen that shows the first. It is *not*
-- required on OTHER by a constraint, because rows onboarded before this column existed carry
-- that kind with nothing beside it and they are not lies — they are simply old. What is new is
-- refused without it, in MasterDataService, where the sentence can say why.

ALTER TABLE vendors
    ADD COLUMN supplies_note varchar(120);

ALTER TABLE vendors
    ADD CONSTRAINT ck_vendor_other_supplies
    CHECK (vendor_type = 'OTHER' OR supplies_note IS NULL);

COMMENT ON COLUMN vendors.supplies_note IS
    'What he supplies, written out, when the closed list has no word for it. Only ever set '
    'alongside vendor_type = OTHER; required there for anything onboarded after V53.';
