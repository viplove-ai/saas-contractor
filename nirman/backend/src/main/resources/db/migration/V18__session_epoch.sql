-- Nirman — a password reset ends every session now, not in fifteen minutes.
--
-- Revoking the refresh tokens was only half of it. The access token is a signed JWT that
-- nothing consults a database about, so a handset that already held one went on working
-- until it expired — up to fifteen minutes of a phone in the wrong hands doing exactly what
-- the reset was meant to stop. A reset is normally asked for because somebody has lost the
-- device or the number, and "in a quarter of an hour" is not an answer to that.
--
-- The fix is one counter. Every access token is stamped with the value the account held
-- when it was issued; the filter compares the stamp with the row on the way past, and a
-- token carrying a stale one is no longer a token. Signing out everywhere is then a single
-- increment, and it takes effect on the next request rather than on the next expiry.
--
-- A counter and not a timestamp on purpose. The comparison has to be exact, and a timestamp
-- is only ever as exact as the narrowest precision it passes through — microseconds in
-- Postgres, milliseconds in a JWT claim, and a truncation between them that would refuse
-- perfectly good tokens. Integers survive the round trip.

ALTER TABLE users
    ADD COLUMN session_epoch bigint NOT NULL DEFAULT 0;

COMMENT ON COLUMN users.session_epoch IS
    'Incremented whenever every session on this account must end at once — a password '
    'reset, a password change, a deactivation. Access tokens carry the value they were '
    'issued under and are refused once it no longer matches.';
