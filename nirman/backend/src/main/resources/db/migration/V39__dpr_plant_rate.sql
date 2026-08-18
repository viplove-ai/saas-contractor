-- Nirman — what the plant on the daily report cost, and who is allowed to say so.
--
-- `dpr_machinery` has carried hours and no money since V1, and the entity says why: docs/09
-- recorded machinery rental as not modelled, because the field data reviewed carried no
-- rental rates and inventing a rate table would have put a fabricated number into project
-- cost. That argument was about *inventing* the rate. It says nothing against recording one
-- that somebody actually agreed — and a hired JCB standing at the gate is billed by the hour
-- whether or not this system knows the figure.
--
-- ---------------------------------------------------------------- who fills it
-- The same division this codebase draws everywhere else: the field may name a thing and
-- never value it (V15, V24). The supervisor writes what stood on the site and for how long,
-- because he is the man who watched it; the rate is a commercial fact he does not hold, and
-- a rate box on his screen is a number guessed at seven in the evening. So the rate is
-- written after the handover, by whoever the report goes to.
--
-- **No new permission.** `dpr:verify` and `dpr:approve` already name the two people the
-- report goes to after it is handed over, and pricing plant is not a third act needing a
-- third grant — it is part of reading the day and signing for it. Minting `dpr:price` would
-- let an organisation grant it to somebody who cannot see the report it sits on.
--
-- ---------------------------------------------------------------- what a rate is
-- A number and a unit, or neither. A rate with no basis is a figure nobody can multiply, and
-- a basis with no rate is a unit for nothing; the check keeps the two together for the write
-- that never goes through the service.
--
-- HOUR and DAY, and no MONTH: this is one day's report, and a monthly rate would need a
-- divisor — how many days the month is worked — which is exactly the sort of assumption that
-- makes two screens disagree. An organisation hiring by the month enters the day rate it
-- works out to, once, where somebody can see it.
--
-- Nothing here is a total. The amount is derived on read from the rate and the hours already
-- on the row, because a stored total is a second version of the truth and this one would go
-- stale the moment a supervisor corrected the hours before signature.

ALTER TABLE dpr_machinery
    ADD COLUMN hire_rate   numeric(18,4),
    ADD COLUMN rate_basis  varchar(10),
    -- Priced by somebody at a time, like every other decision here. The report already
    -- records who verified and who approved; this is a third act by one of those two and
    -- deserves its own signature rather than being read off whichever came later.
    ADD COLUMN rate_set_at timestamptz,
    ADD COLUMN rate_set_by uuid REFERENCES users(id);

ALTER TABLE dpr_machinery
    ADD CONSTRAINT ck_dpr_machinery_rate_basis
        CHECK (rate_basis IS NULL OR rate_basis IN ('HOUR', 'DAY')),
    -- A number and a unit, or neither.
    ADD CONSTRAINT ck_dpr_machinery_rate_whole
        CHECK ((hire_rate IS NULL AND rate_basis IS NULL)
            OR (hire_rate IS NOT NULL AND rate_basis IS NOT NULL)),
    ADD CONSTRAINT ck_dpr_machinery_rate_positive
        CHECK (hire_rate IS NULL OR hire_rate >= 0),
    ADD CONSTRAINT ck_dpr_machinery_rate_signed
        CHECK (hire_rate IS NULL OR rate_set_at IS NOT NULL);

COMMENT ON COLUMN dpr_machinery.hire_rate IS
    'What the machine is charged at, filled after the handover by whoever the report goes '
    'to. Never the supervisor: he records what stood on the site, not what it costs.';

COMMENT ON COLUMN dpr_machinery.rate_basis IS
    'HOUR multiplies the hours run; DAY multiplies the number of machines, this being one '
    'day. No MONTH: a monthly rate needs a divisor, and a divisor assumed is two screens '
    'disagreeing.';
