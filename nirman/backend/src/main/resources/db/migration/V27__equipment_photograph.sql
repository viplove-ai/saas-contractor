-- Nirman — a photograph of the machine.
--
-- V25 built the plant register on one rule: anybody at the site may say a machine is here,
-- and only the office may agree. The office agreeing has so far meant reading a line of text
-- typed by somebody standing in a yard forty kilometres away — "concrete mixer, no number on
-- it" — and either believing it or not. A picture is the evidence that was missing, and it is
-- the one thing the man in the yard can produce and the office cannot.
--
-- It is also what tells two of the four identical centering frames apart, what shows a hired
-- breaker really did stand here in March, and what settles UNDER_REPAIR without a phone call.
--
-- ---------------------------------------------------------------- the column
-- One photograph, not a gallery. A machine is one thing, and the question the register is
-- asked is "which machine is this" — the DPR is where a day's several pictures belong, and it
-- has its own dpr_photos table for exactly that reason. A second picture here would be
-- answering a question nobody is asking of a plant register.
--
-- Nullable, and that is the feature rather than a concession: the mixer is entered at the gate
-- in the rain and photographed on Thursday when somebody is standing next to it in daylight.
-- Making it required would mean the entry that could not be photographed is not made at all,
-- and the register goes back to being a list of what the office remembered.
ALTER TABLE site_equipment
    ADD COLUMN photo_attachment_id uuid REFERENCES attachments(id);

COMMENT ON COLUMN site_equipment.photo_attachment_id IS
    'The picture of the machine, attached when it is entered or any time afterwards. Null is '
    'ordinary: a photograph nobody could take yet is not a reason to refuse the entry.';

-- No new permission. Adding the photograph is not a fifth act on this register — it is part
-- of saying the machine is here, which is equipment:create, and part of correcting the entry,
-- which is equipment:write. SiteEquipmentService is where the line between them sits: the man
-- who entered it may photograph it while the office has not yet decided, and after that it is
-- the office's row like every other field on it.
