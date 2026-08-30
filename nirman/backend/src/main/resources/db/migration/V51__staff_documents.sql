-- Nirman — the papers behind a staff record.
--
-- V22 gave the office what an employer has to hold about somebody: the address, the next of
-- kin, the bank account, what was agreed about the pay. Every one of those is a figure typed
-- off a document — the Aadhaar card, the cancelled cheque, the appointment letter somebody
-- signed — and the documents themselves stayed in a folder in a drawer at head office. So
-- the record could say the account number and never show the passbook it was copied from,
-- and the man who typed it is the only person who can say whether he read it right.
--
-- `attachments` has held files since V1 and this needs no new storage: what was missing is a
-- register saying which files are whose, and what each one is.
--
-- ---------------------------------------------------------------- keyed to the person
-- On `user_id`, not on `staff_profiles.id`. A member exists as a login on the day he starts
-- and his record is filled in when the office gets round to it — `StaffRecordService.get`
-- deliberately answers a member with no profile with blanks rather than a 404, because the
-- screen's job is to collect what is missing. Hanging his papers off a row that may not
-- exist would mean the photograph of his Aadhaar card could not be taken until somebody had
-- typed his blood group, which is the ordering that puts documents back in the drawer.
--
-- ---------------------------------------------------------------- what it is
-- A closed list and a free note beside it, the same split V34 drew for a lost day: the type
-- is the countable half — "who has no PAN copy on file" is a question an office asks and a
-- caption cannot answer — and the note is the half a person reads ("front and back on one
-- page", "the 2019 one, superseded"). A caption alone would leave every answer spelled four
-- ways; a list alone would lose everything that makes one scan different from the next.
CREATE TABLE staff_documents (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id        uuid NOT NULL REFERENCES organisations(id),
    user_id       uuid NOT NULL REFERENCES users(id),
    attachment_id uuid NOT NULL REFERENCES attachments(id),

    doc_type      varchar(30) NOT NULL,
    note          varchar(200),

    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now(),
    created_by    uuid REFERENCES users(id),
    updated_by    uuid REFERENCES users(id),
    version       bigint NOT NULL DEFAULT 0,

    CONSTRAINT ck_staff_document_type CHECK (doc_type IN (
        'AADHAAR', 'PAN', 'BANK', 'APPOINTMENT', 'EDUCATION',
        'POLICE_VERIFICATION', 'PHOTOGRAPH', 'OTHER')),
    -- One row per file. The same scan listed twice under two names is two answers to "has he
    -- given us his PAN", and the second one is always the one somebody deletes.
    CONSTRAINT uq_staff_document_attachment UNIQUE (attachment_id)
);

-- The whole of the read: one member's papers, newest first.
CREATE INDEX ix_staff_documents_member ON staff_documents (org_id, user_id, created_at DESC);

COMMENT ON TABLE staff_documents IS
    'The papers behind a staff record — one row per file, keyed to the member rather than to '
    'his profile, because a login exists before the record is filled in.';

-- ---------------------------------------------------------------- no new permission
-- `staff:read` and `staff:write` (V22) are already exactly these two questions. Holding
-- somebody's bank account number and holding the photograph of the passbook it was read off
-- are the same act of custody, and an organisation that could grant one without the other
-- would be deciding something it has no way to think about.
--
-- ---------------------------------------------------------------- and it is really deleted
-- Not voided with a reason, which is the rule for financial records and right for them: an
-- approved bill that vanishes is money nobody can account for. A document is not a figure
-- anything was computed from — the figures were typed off it and stay where they are — and
-- the ordinary reason to remove one is that it is the wrong man's Aadhaar card or a thumb
-- over the lens. Keeping a scan of somebody's identity document because the register cannot
-- bear to lose a row is the worse of the two failures, so the row goes and the file behind
-- it is soft-deleted with it, which stops any further signed link being minted for it.
