-- Server-assigned document numbers.
--
-- docs/09-design-decisions.md lists this as the highest-risk open question: expense_number,
-- grn_number, issue_number, advance_number and six others are all NOT NULL under a per-org
-- unique constraint, and nothing generated them. The offline design has devices creating
-- records while disconnected, so two phones would both produce ADV-2025-0001 and the second
-- sync would fail a unique constraint that idempotency cannot resolve — the primary keys
-- differ, so it is not a replay, it is a genuine collision over a human-readable label.
--
-- Resolved the way that document recommended: the number is assigned by the server when the
-- record lands, and a device shows its own provisional reference until then. The alternative,
-- handing each site a pre-allocated range, needs range administration nobody will do.
--
-- One counter row per organisation, document type and year. Incrementing is a single
-- atomic statement (INSERT ... ON CONFLICT DO UPDATE ... RETURNING), so concurrent callers
-- serialise on the row rather than racing to read-then-write.

CREATE TABLE document_number_counters (
    org_id      uuid        NOT NULL REFERENCES organisations(id),
    doc_type    varchar(30) NOT NULL,   -- WORKER_ADVANCE | EXPENSE | GRN | ISSUE | ...
    year        int         NOT NULL,
    next_value  bigint      NOT NULL DEFAULT 1,
    updated_at  timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (org_id, doc_type, year),
    CONSTRAINT ck_counter_next_positive CHECK (next_value > 0)
);

COMMENT ON TABLE document_number_counters IS
    'Server-side sequence per org, document type and year. See V3 header and docs/09.';
