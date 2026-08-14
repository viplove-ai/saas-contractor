-- ==============================================================================================
-- V32 — the bid, kept on the project
--
-- A percentage-rate tender prices the BOQ at DSR rates and pays the contractor those rates
-- adjusted by his own quote. Every rupee a plan predicts moves with it, and it is the one figure
-- no reader of the notice can supply: at the time the tender is read the quote has not been
-- decided yet.
--
-- It was previously typed into the planning screen every time a plan was generated, which made
-- it a property of the plan rather than of the contract — and two plans of the same project could
-- disagree about what the contractor had actually bid. It belongs here, entered once when the
-- project is created, and the planner reads it.
--
-- Nullable, because a project created before this column existed has no answer and a zero would
-- be a claim that the work was bid at par.
-- ==============================================================================================

ALTER TABLE projects
    ADD COLUMN quoted_percent numeric(7,3),
    ADD CONSTRAINT ck_projects_quoted_percent
        CHECK (quoted_percent IS NULL OR (quoted_percent > -100 AND quoted_percent < 1000));

COMMENT ON COLUMN projects.quoted_percent IS
    'Percentage above (+) or below (-) the estimated cost at which the work was bid.';
