-- Nirman — a worker may stand on more than one site at a time.
--
-- uq_alloc_open allowed one open posting per worker, and the entity's own comment called it
-- the rule that stops the same man being marked present at two sites on one day. That was
-- the right guard at the wrong table. The firm lends men between sites for a morning and a
-- month — a mason on the main block whose afternoons are the annexe's, a fitter both sites
-- call for — and the only shapes the register offered were a transfer (he leaves one roll
-- for the other) or nothing (he is marked on one roll and worked on two). The second is what
-- happened, and the annexe's labour cost was the main block's.
--
-- So a posting is now per site: a worker may hold one open posting at each site he is
-- shared with (uq_alloc_open_site), and a transfer still closes every posting he holds and
-- opens one. The roster and the register already read postings through EXISTS and need no
-- change; what changes is where the day is guarded. "One man, one roll, one morning" moves
-- to the attendance service as the arithmetic it always was: PRESENT is a whole day,
-- HALF_DAY is half of one, and his marks across every site on a date may not come to more
-- than a day. Two half days at two sites is the ordinary case this exists for; PRESENT at
-- one and anything but ABSENT or LEAVE at another is the double count the old index caught,
-- and it is still caught. No wage figure moves: a half day at each site prices at half his
-- rate each, which is what he earned.
--
-- No new permission. Sharing a man is worker:write, as the transfer is, and the fence is
-- site scope: the caller must hold a site the man stands on, and — unless he sees every
-- site — the one he is being shared with. A supervisor shares among the sites he
-- supervises; the office shares him anywhere.

DROP INDEX uq_alloc_open;
CREATE UNIQUE INDEX uq_alloc_open_site
    ON worker_site_allocations (worker_id, site_id) WHERE effective_to IS NULL;
