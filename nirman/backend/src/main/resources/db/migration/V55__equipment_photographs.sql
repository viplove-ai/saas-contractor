-- Nirman — a machine is photographed as many times as it takes.
--
-- V25 gave `site_equipment` one `photo_attachment_id`, and one is the wrong number. A mixer is
-- identified by its plate, its condition is argued about from the state of its drum, and the
-- crack in a breaker's jaw is not in the same frame as the asset code stencilled on its side.
-- The office accepting the entry is being asked to agree that a particular machine is standing
-- at a particular site in a particular condition, and one photograph can carry at most two of
-- those three claims.
--
-- What the single column actually produced was a supervisor photographing the plate, then
-- photographing the damage, and the second picture silently replacing the first — the register
-- keeping whichever he took last and no record that there had ever been another. That is worse
-- than refusing the second, because nothing on the screen said anything was lost.
--
-- ---------------------------------------------------------------- the shape
-- The same shape `goods_receipt_attachments` (V52), `expense_attachments` (V40) and
-- `staff_documents` (V51) already have: the link only, with the file itself in `attachments`.
-- No `doc_type` here, unlike the delivery's two — a receipt has to distinguish the load from
-- the paper because they are two different claims that can disagree. Pictures of a machine are
-- all the same kind of claim, and a closed list would be four words nobody chooses between.
CREATE TABLE site_equipment_photos (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id        uuid NOT NULL REFERENCES organisations(id),
    equipment_id  uuid NOT NULL REFERENCES site_equipment(id) ON DELETE CASCADE,
    attachment_id uuid NOT NULL REFERENCES attachments(id),

    created_at    timestamptz NOT NULL DEFAULT now(),
    created_by    uuid REFERENCES users(id),

    -- One row per file. The same picture listed twice is two answers to "how many photographs
    -- has this machine got", and the second is always the one somebody deletes.
    CONSTRAINT uq_equipment_photo_attachment UNIQUE (attachment_id)
);

-- The whole of the read: one machine's pictures, oldest first, because the first one taken is
-- the one of the machine itself and the later ones are of what went wrong with it.
CREATE INDEX ix_equipment_photos_machine
    ON site_equipment_photos (equipment_id, created_at);

COMMENT ON TABLE site_equipment_photos IS
    'The pictures of one machine. Many, because the plate, the condition and the damage are '
    'rarely in one frame.';

-- ---------------------------------------------------------------- what the column held
-- Carried across before it goes. The pictures already on the register are the ones the office
-- accepted those entries on, and losing them would quietly empty the evidence out of every row
-- entered before today.
--
-- `created_by` is the row's own author rather than the person running this migration: the
-- photograph was taken by whoever entered the machine, and attributing it to a deployment
-- would put a name on it that never saw the thing.
INSERT INTO site_equipment_photos (id, org_id, equipment_id, attachment_id, created_at, created_by)
SELECT gen_random_uuid(), e.org_id, e.id, e.photo_attachment_id, e.created_at, e.created_by
  FROM site_equipment e
 WHERE e.photo_attachment_id IS NOT NULL
   AND EXISTS (SELECT 1 FROM attachments a WHERE a.id = e.photo_attachment_id);

-- And the column goes. Keeping it as "the first picture" would be a second version of the
-- truth — the one that stops matching the day somebody deletes that picture and the register
-- still claims it — which is the reason V19 dropped the two site staffing columns rather than
-- keeping them as a summary of the list that replaced them.
ALTER TABLE site_equipment DROP COLUMN photo_attachment_id;
