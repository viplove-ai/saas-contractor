-- Nirman — what the firm provides on top of the salary.
--
-- A junior engineer posted to Mussoorie is offered twenty thousand a month, a bed in the site
-- guest house and the petrol for the motorcycle he rides between the two blocks. Two of those
-- three were nowhere on the record, so the offer letter could not state them and the argument
-- in the third month — "you said the room was included" — had nothing behind it.
--
-- ---------------------------------------------------------------- why not on the structure
-- These are deliberately NOT components of `staff_salary_revisions`, and the reason is the
-- same statute the structure exists to satisfy. The Code on Wages excludes from "wages" both
-- the value of house accommodation and a sum paid to defray special expenses entailed by the
-- nature of the employment — which is what fuel reimbursed for running between two blocks is.
-- Putting either in the packet would inflate the total the fifty-per-cent test is run against
-- and quietly move the provident fund wage of every member who gets a room.
--
-- So they sit on the profile, beside the notice period and the designation: terms that were
-- agreed, corrected in place, and stated on the letter. They are facilities, not money, and
-- nothing here reaches a payslip.
--
-- ---------------------------------------------------------------- a flag and a sentence
-- The same split V34 drew for a lost day and V53 for a supplier's fifth kind: the flag is the
-- half that can be counted — "how many of our staff are we housing" is a question an office
-- asks when a lease comes up for renewal — and the note is the half a person reads, because
-- "shared room at the Mussoorie guest house, electricity included" is not a value in a list.
ALTER TABLE staff_profiles
    ADD COLUMN accommodation_provided boolean NOT NULL DEFAULT false,
    ADD COLUMN accommodation_note     varchar(200),
    ADD COLUMN fuel_provided          boolean NOT NULL DEFAULT false,
    -- Null where the fuel is met at actuals against bills, which is the commoner arrangement
    -- and is not the same statement as zero. A figure here is a fixed monthly allowance.
    ADD COLUMN fuel_monthly_amount    numeric(14,2),
    ADD COLUMN fuel_note              varchar(200);

COMMENT ON COLUMN staff_profiles.fuel_monthly_amount IS
    'A fixed monthly figure, or null where fuel is reimbursed at actuals against bills. Null '
    'is an arrangement, not a gap: it is not the same statement as zero.';

-- A note about a room nobody is given, or a figure for fuel nobody is paid, describes nothing.
-- The check runs one way only — it refuses the detail without the offer, and never demands
-- detail alongside it, because "yes, a room" with nothing said about which room is a true and
-- ordinary answer.
ALTER TABLE staff_profiles
    ADD CONSTRAINT ck_staff_accommodation_detail
        CHECK (accommodation_provided OR accommodation_note IS NULL),
    ADD CONSTRAINT ck_staff_fuel_detail
        CHECK (fuel_provided OR (fuel_monthly_amount IS NULL AND fuel_note IS NULL)),
    ADD CONSTRAINT ck_staff_fuel_amount
        CHECK (fuel_monthly_amount IS NULL OR fuel_monthly_amount >= 0);

-- ---------------------------------------------------------------- no new permission
-- What was agreed when somebody was taken on is `staff:write`, and has been since V22. A room
-- and a tank of petrol are terms of the engagement in exactly the way the notice period is.
