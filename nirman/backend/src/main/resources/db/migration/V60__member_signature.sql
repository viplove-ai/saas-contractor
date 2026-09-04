-- Nirman — the member's own signature, on the record and on the documents that carry his name.
--
-- Three documents already print a signature line with a name under it and nothing above it: the
-- offer letter ("Yours sincerely" over the administrator's name), and the daily report's two
-- lines, "Prepared by" the supervisor and "Verified by" the engineer. Each was a PDF that had to
-- be printed, signed with a pen, and scanned back — or, more usually, went out with the line
-- blank, which is a document that claims a signature and shows none.
--
-- So a member holds one picture of his signature, and the documents draw it where his name is.
-- It hangs off `users` rather than `staff_profiles` for the reason V51 keyed the papers there: a
-- member exists as a login on the day he starts, and a signature that could only be uploaded
-- once somebody had typed his bank account would wait for the office. It is also his own to
-- upload and his own to replace — nobody signs for another man — which is why no permission is
-- minted: being signed in is the whole of the authorisation, and an administrator does not hold
-- the write on another member's signature any more than he holds the pen.
--
-- The file itself is in `attachments`, claimed to the user id exactly as a paper is claimed to
-- its `staff_documents` row, so the ordinary delete path refuses to discard it out from under
-- the documents that will draw it. The picture is cropped on the device to one fixed shape
-- before it is sent, so every signature prints in the same box on every document; nothing here
-- stores a size, because a size stored beside a picture is a second statement of what the
-- picture already is.
--
-- One column and no history. What a document carries is the signature *at the moment it was
-- rendered* — the offer letter is filed as a PDF, so the letter that went out keeps the picture
-- that went out on it, and a signature replaced later changes nothing already issued.
-- ==============================================================================================

ALTER TABLE users
    ADD COLUMN signature_attachment_id uuid REFERENCES attachments(id);

COMMENT ON COLUMN users.signature_attachment_id IS
    'The member''s own signature, drawn onto the documents that carry his name. Claimed to the '
    'user id in attachments; null until he uploads one.';
