-- ==============================================================================================
-- V33 — the guarantee a low bid has to find
--
-- The performance guarantee is not a small percentage of the contract. Under the CPWD form it is
-- "5% of the estimated cost put to tender (ECPT) *or* the contract amount, whichever is higher" —
-- so bidding below the estimate does not shrink it, and the plan must not compute it off the
-- quoted amount.
--
-- Below 80% of the estimate a second guarantee appears on top. One notice in the corpus states
-- it with a worked example:
--
--   "If the quoted bid amount is lesser than 80% of the estimated cost put to tender, the bidder
--    shall be required to submit Additional performance guarantee (APG) ... equal to the
--    difference between the 80% amount of ECPT and quoted amount. (e.g. if ECPT is A and quoted
--    amount is 0.7A then the amount of APG shall be 0.8A - 0.7A)"
--
-- On a ₹1 crore estimate bid 30% below, that is ₹5 lakh of PG *plus* ₹10 lakh of APG — fifteen
-- lakh of bank guarantee to find before a rupee of work is billable. A contractor deciding how
-- deep to bid is deciding how much guarantee to raise, and until now the plan said nothing
-- about it.
--
-- Extracted rather than hardcoded, because the rule is not uniform. Other departments levy a
-- flat percentage of the tendered amount instead of the difference, and one of the ten notices
-- here carries no APG clause at all. A null means the notice did not say, and the plan then
-- applies no additional guarantee and records that it did not.
-- ==============================================================================================

ALTER TABLE nit_documents
    -- The bid must fall below this share of the estimate before an APG is due at all.
    ADD COLUMN apg_threshold_percent numeric(6,3),
    -- DIFFERENCE  = threshold share of ECPT, less the quoted amount (the CPWD form)
    -- PERCENT_OF_BID = a flat percentage of the tendered amount (other departments)
    ADD COLUMN apg_method            varchar(20),
    ADD COLUMN apg_percent           numeric(6,3),

    ADD CONSTRAINT ck_nit_apg_method
        CHECK (apg_method IS NULL OR apg_method IN ('DIFFERENCE', 'PERCENT_OF_BID')),
    ADD CONSTRAINT ck_nit_apg_threshold
        CHECK (apg_threshold_percent IS NULL
               OR (apg_threshold_percent > 0 AND apg_threshold_percent <= 100)),
    ADD CONSTRAINT ck_nit_apg_percent
        CHECK (apg_percent IS NULL OR (apg_percent >= 0 AND apg_percent <= 100));

COMMENT ON COLUMN nit_documents.apg_threshold_percent IS
    'Bid below this share of the estimate triggers an additional performance guarantee.';
