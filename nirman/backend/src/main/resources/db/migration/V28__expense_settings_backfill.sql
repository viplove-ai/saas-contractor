-- Nirman — the expense policy row every organisation was assumed to have.
--
-- V14's argument, one table along. `expense_settings` holds one row per organisation and is
-- inserted nowhere but the dev seed, which the prod profile never loads; no screen and no
-- endpoint in the app creates it. So a deployed organisation had no row, and the first thing
-- that reads one is the draft save on an expense — it reads it only to ask whether duplicate
-- checking is on — which meant nobody could book an expense at all, and the sentence they got
-- named a setting they had no way to write.
--
-- The service no longer throws on a missing row; it falls back to the same defaults declared
-- on the column and on the entity. This file is the other half: an organisation's policy
-- should be a row it can be shown and later edit, not a default living in three places. The
-- values are the column defaults, so the backfill changes nothing about how the app behaves
-- today — it only makes the assumption true.
--
-- Idempotent, per organisation. An organisation that already has a row keeps it exactly as it
-- stands; nothing here overwrites a threshold somebody chose. In dev and test the organisation
-- itself arrives in V900, which runs after this, so this inserts nothing there and the seed's
-- own row stands alone.

INSERT INTO expense_settings (org_id)
SELECT o.id
FROM organisations o
WHERE NOT EXISTS (
    SELECT 1 FROM expense_settings s WHERE s.org_id = o.id
);
