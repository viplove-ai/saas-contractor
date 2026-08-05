-- Hours as an assertion rather than a derivation.
--
-- The muster roll now asks the supervisor how many hours a man worked instead of asking for
-- a check-in and a check-out. On a site where nobody carries a clock, "he did nine hours"
-- is the fact that actually exists; the times were being invented to express it.
--
-- Kept separate from worked_hours on purpose. worked_hours is an output — what the
-- calculator decided, whichever way it got there — and entered_hours is the input that
-- produced it. Collapsing them would leave no way to tell a typed nine from a nine measured
-- off the clock, and verification re-runs the calculation months later.
--
-- Nullable, because every row written before this migration derived its hours from times or
-- from the status alone, and those rows must keep calculating exactly as they did.

ALTER TABLE attendance_records
    ADD COLUMN entered_hours numeric(6,2);

ALTER TABLE attendance_records
    ADD CONSTRAINT ck_att_entered_hours
    CHECK (entered_hours IS NULL OR (entered_hours >= 0 AND entered_hours <= 24));

COMMENT ON COLUMN attendance_records.entered_hours IS
    'Hours the supervisor typed. Overrides check_in_time/check_out_time when present; null means the hours were derived from the clock or implied by the status.';
