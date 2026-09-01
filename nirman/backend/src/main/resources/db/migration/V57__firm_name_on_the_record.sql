-- Nirman — the firm's real name, in the one place the documents read it from.
--
-- The rebrand went through the app in two commits: the shell, the login page, the manifest and
-- the Fly apps all say Shivadri Projects. What none of them touched is `organisations.name`,
-- which is not decoration — it is what the offer letter prints across the top of its
-- letterhead, so a candidate was being offered employment with a firm that no longer calls
-- itself that. The screens had been renamed and the paper had not.
--
-- ---------------------------------------------------------------- why this is in a migration
-- Org-specific data does not belong in a migration, and this file is the exception rather than
-- a loosening of the rule. The reason is that there is no other door: `organisations` has a
-- repository and an entity and no controller anywhere, so the row cannot be corrected from any
-- screen, and the alternative to this is somebody with a psql prompt against production doing
-- it by hand and nothing in the history saying it happened.
--
-- It is guarded by the old name rather than by an id, so it renames exactly the row that still
-- carries the placeholder and touches nothing belonging to any other organisation. A second
-- deployment that never used the name is left alone, and running it twice changes nothing the
-- first run did not.
--
-- ---------------------------------------------------------------- what it deliberately leaves
-- `code` stays NIRMAN. It is an identifier, not a name: it prefixes the letter reference
-- (NIRMAN/HR/2026/1005) and any row already issued under one of those references is filed
-- against it. Renaming identifiers was refused for the databases, the storage keys and the
-- packages when the app was rebranded, and a reference number is the same kind of thing — it
-- has to keep matching the paper in the drawer. The contact address is untouched for the same
-- reason it is not on the letterhead any more: it holds one person's email, and what to do
-- about that is a decision about the record and not about the rename.
-- ==============================================================================================

UPDATE organisations
   SET name = 'Shivadri Projects',
       updated_at = now()
 WHERE name = 'Nirman Constructions';
