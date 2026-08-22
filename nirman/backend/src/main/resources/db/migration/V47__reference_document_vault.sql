-- Nirman — the vault, and which of its documents a tender was priced under.
--
-- A bill is prepared against documents that are older than the bill, live longer than the
-- project, and are revised on their own timetable by somebody else. The Delhi Schedule of
-- Rates, the Delhi Analysis of Rates, the cost index circular that moves a station's
-- percentage, the specification a disputed item is read against. CPWD publishes them free and
-- only as PDF; a contractor keeps them in a drawer, on a laptop, and in an email.
--
-- Two problems follow, and this migration is about the second.
--
-- The first is that nobody can find the copy. That is what `reference_documents` fixes: one
-- shelf, the file itself in object storage beside the row, so a rate questioned in March can
-- be opened at its own page rather than argued from memory.
--
-- The second is worse and less obvious. **A tender is priced under a particular edition, and
-- that edition does not change when the next one is published.** A bill raised in 2026 against
-- an agreement let in 2025 is still a DSR 2023 bill; repricing it because DSR 2026 arrived
-- would be inventing money in either direction. So the link between a tender and the documents
-- governing it is a stored fact — `agreement_documents` — and not a lookup of "whichever is
-- current". Superseding an edition marks the new one current for tenders let afterwards and
-- moves nothing that already points at the old one.
--
--
-- WHY THE NIT ALREADY KNOWS THE ANSWER
--
-- `NitPdfParser` has read `civil_dsr_year` and `civil_cost_index_percent` off the notice since
-- the tender module was built, and `nit_documents` has been storing them. Nobody could do
-- anything with them: the agreement's rate chain was typed in by hand at the first bill, off
-- the same notice the system had already read. So the vault closes that loop — the agreement
-- form is offered the year and the percentage the notice stated, and the edition on the shelf
-- that matches. The office confirms rather than transcribes, which is the difference between a
-- figure somebody checked and a figure somebody retyped.
--
--
-- WHY NO NEW PERMISSION
--
-- `dsr:manage` already exists and was minted for exactly this custody: "a rate is the
-- multiplier on every quantity in the document". Deciding which edition governs a tender IS
-- deciding its rates — a second permission would let an organisation grant the shelf to
-- somebody it does not trust with the rates, which is the same power wearing a different hat.
-- Reading the vault goes with reading a bill, so `billing:read` covers it.

-- ---------------------------------------------------------------- the vault

CREATE TABLE reference_documents (
    id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id         uuid NOT NULL REFERENCES organisations(id),
    -- What kind of authority it carries, which decides what it may be attached to. DSR and
    -- DAR price work; COST_INDEX moves a station's percentage; SPECIFICATION and CIRCULAR are
    -- read by people and priced by nothing.
    kind           varchar(30) NOT NULL,
    -- As the profession says it: DSR-2023, DAR-2023-VOL-II, CI-MUSSOORIE-2025.
    code           varchar(60) NOT NULL,
    title          varchar(300) NOT NULL,
    -- The year the document is *of*, which is not always the year it was published.
    edition_year   integer,
    -- For a cost index: the station it applies to. Null for everything else.
    station        varchar(120),
    -- For a cost index: the percentage it sets. Null for everything else — a schedule of rates
    -- has no single number and must never be given one.
    index_percent  numeric(9, 4),
    effective_from date,
    effective_to   date,
    -- The file. Null while somebody has registered an edition they have not yet uploaded,
    -- which is a real state: the office knows the tender cites DSR 2023 before it finds a copy.
    attachment_id  uuid REFERENCES attachments(id),
    -- The edition this one replaces. A chain, so "what did we use before" has an answer.
    supersedes_id  uuid REFERENCES reference_documents(id),
    status         varchar(20) NOT NULL DEFAULT 'CURRENT',
    notes          text,
    created_at     timestamptz NOT NULL DEFAULT now(),
    updated_at     timestamptz NOT NULL DEFAULT now(),
    created_by     uuid,
    updated_by     uuid,
    version        bigint NOT NULL DEFAULT 0,
    deleted_at     timestamptz,
    CONSTRAINT ck_refdoc_kind CHECK (kind IN
        ('DSR', 'DAR', 'COST_INDEX', 'SPECIFICATION', 'CIRCULAR', 'OTHER')),
    CONSTRAINT ck_refdoc_status CHECK (status IN ('CURRENT', 'SUPERSEDED', 'WITHDRAWN')),
    -- A cost index without its percentage cannot do the one thing it exists for; anything else
    -- carrying one is claiming an authority it does not have.
    CONSTRAINT ck_refdoc_index_percent CHECK (
        (kind = 'COST_INDEX') = (index_percent IS NOT NULL)),
    -- An edition cannot supersede itself, which is the loop that makes a chain unwalkable.
    CONSTRAINT ck_refdoc_not_self_superseding CHECK (supersedes_id IS NULL OR supersedes_id <> id),
    CONSTRAINT uq_refdoc_code UNIQUE (org_id, code)
);

CREATE INDEX ix_refdoc_kind ON reference_documents (org_id, kind, edition_year DESC)
    WHERE deleted_at IS NULL;

CREATE INDEX ix_refdoc_current ON reference_documents (org_id, kind)
    WHERE status = 'CURRENT' AND deleted_at IS NULL;

COMMENT ON TABLE reference_documents IS
    'The shelf: schedules of rates, cost index circulars, specifications. The file lives in '
    'attachments; this is the edition and what it governs. See the V47 header.';

-- ---------------------------------------------------------------- what governs a tender

-- The stored answer to "which edition was this priced under". Not a lookup of whatever is
-- current: a 2025 agreement stays a DSR 2023 agreement after DSR 2026 is published, and a bill
-- that repriced itself when the shelf changed would invent money.
CREATE TABLE agreement_documents (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    agreement_id uuid NOT NULL REFERENCES agreements(id) ON DELETE CASCADE,
    document_id  uuid NOT NULL REFERENCES reference_documents(id),
    -- What the document does for this tender. One tender cites several, and the same document
    -- can play different parts for different tenders.
    role         varchar(30) NOT NULL DEFAULT 'SCHEDULE_OF_RATES',
    -- Civil Works | E&M Works, where a tender prices the two under different schedules — which
    -- is the ordinary case, and why the NIT reads a civil and an electrical DSR year.
    work_part    varchar(40),
    notes        text,
    created_at   timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_agreement_document_role CHECK (role IN
        ('SCHEDULE_OF_RATES', 'COST_INDEX', 'SPECIFICATION', 'OTHER')),
    CONSTRAINT uq_agreement_document UNIQUE (agreement_id, document_id, role, work_part)
);

CREATE INDEX ix_agreement_documents_agreement ON agreement_documents (agreement_id);

COMMENT ON TABLE agreement_documents IS
    'Which editions a tender was priced under, stored rather than looked up — publishing a '
    'newer schedule must not reprice a bill. See the V47 header.';

-- ---------------------------------------------------------------- the priced subset

-- A schedule of rates on the shelf whose rates have been read out of it. Not every vault
-- document has one: a specification is read by people and priced by nothing, and a DSR is
-- useful on the shelf long before anybody has parsed it.
ALTER TABLE dsr_schedules
    ADD COLUMN document_id uuid REFERENCES reference_documents(id);

COMMENT ON COLUMN dsr_schedules.document_id IS
    'The vault edition these rates were read out of, so a disputed rate can be opened at its '
    'own page in the original PDF.';
