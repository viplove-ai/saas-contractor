-- Nirman — the day the site did not work.
--
-- Until now a daily report has had one shape, and it is the shape of a working day: what was
-- built, who was there, what it consumed. A site that did not work has none of those, so the
-- day was either written up as a report full of dashes — indistinguishable from a report
-- somebody started and abandoned — or not written at all, which is worse. A missing report
-- means nothing: it might be rain, it might be a supervisor who forgot. A report that says
-- "no work, rain" means something, and it is the sentence the department asks for when an
-- extension of time is claimed.
--
-- So the first question the report asks is whether the site worked at all, and the two answers
-- lead to two different documents.
--
-- ---------------------------------------------------------------- the flag
-- Default true, because every report written before this migration was written about a day the
-- site worked — that is what the old form could describe. Backfilling anything else would be
-- inventing history.
ALTER TABLE daily_progress_reports
    ADD COLUMN site_operational boolean NOT NULL DEFAULT true;

-- ---------------------------------------------------------------- why
-- A picked cause rather than a sentence, and the reason is arithmetic. "We lost nine days to
-- rain in July" is a claim somebody can make against the department; "we lost some days, see
-- the notes" is not. Free text cannot be counted, and a delay that cannot be counted cannot be
-- priced. The note beside it is where the sentence goes — which road flooded, which department
-- letter stopped the work — because the cause is the countable half and the note is the half a
-- human reads.
--
-- Stored as a string rather than a lookup table for the same reason weather is: this is a
-- closed list the code enumerates, not master data an organisation edits. A new cause is a
-- migration, deliberately, because every reading that groups by cause has to be told about it.
ALTER TABLE daily_progress_reports
    ADD COLUMN non_operational_cause varchar(30);

ALTER TABLE daily_progress_reports
    ADD COLUMN non_operational_note varchar(2000);

-- The flag and the cause are one fact recorded in two columns, so the database keeps them
-- agreeing. A day marked non-operational with no cause is a report that says the site did not
-- work and refuses to say why, which is the report this whole migration exists to prevent; a
-- working day carrying a cause is a leftover from an answer somebody changed their mind about,
-- and it would print on the PDF beside a full day's work.
ALTER TABLE daily_progress_reports
    ADD CONSTRAINT ck_dpr_operational_cause CHECK (
        (site_operational AND non_operational_cause IS NULL)
        OR (NOT site_operational AND non_operational_cause IS NOT NULL)
    );

COMMENT ON COLUMN daily_progress_reports.site_operational IS
    'Whether the site worked at all that day. False turns the report into a record of why it '
    'did not: no work items, no observations, and a cause that can be counted across a month.';

COMMENT ON COLUMN daily_progress_reports.non_operational_cause IS
    'Why the site did not work, off the closed list in DailyProgressReport.NonOperationalCause. '
    'Null exactly when site_operational is true, and never otherwise.';

COMMENT ON COLUMN daily_progress_reports.non_operational_note IS
    'The sentence beside the cause — which road flooded, which letter stopped the work. '
    'Required only for OTHER, where the cause on its own says nothing.';

-- ---------------------------------------------------------------- no new permission
-- The report is now written by two people: the supervisor says what the day was and hands it
-- over, the engineer says what was built and signs. That split needs no new permission, because
-- the two that exist already name it exactly — dpr:draft is the man who was there and
-- dpr:verify is the man who signs. DprService is where the line sits: a caller holding only
-- dpr:draft may write the day's conditions and never the work claimed against the contract,
-- which is the same rule the measurement book has always followed, moved one step earlier.
