package in.nirman.common;

/**
 * Whose cost an expense is.
 *
 * <p>Every expense is typed at a site, and until V36 that was also the answer: the site's. It
 * is the right answer for the lorry of sand and the wrong one for the accountant's salary, and
 * booking the second against the site overstates the job — the same double-count docs/09
 * chased out of material and wages, arriving through a different door.</p>
 *
 * <p>{@link #SPLIT} exists because one bill is sometimes two costs — diesel that ran the site
 * mixer and the office car — and an organisation forced to pick one answer either books the
 * whole thing wrong or tears the bill into two rows that no longer match the paper.</p>
 *
 * <p><b>Why it lives in {@code common} and not in the expense module.</b> Two modules have to
 * name the same three values: the expense module, where the decision is taken, and master
 * data, where an expense head carries the answer its rows almost always have. Expense already
 * reads master data; master data reading expense back would be the cycle the module boundaries
 * exist to prevent, and a bare string column in one of them is a spelling mistake waiting to
 * happen.</p>
 */
public enum CostAllocation {

    /** The site's project carries the whole of it. */
    SITE,

    /** Organisation overhead: no site's project carries any of it. */
    COMPANY,

    /**
     * Part each, by amount. The site's part is stored; the company's is the remainder and is
     * never stored beside it, so a corrected total cannot leave the two disagreeing.
     *
     * <p>Never a head's default: a split is an amount, and an amount is a fact about one bill
     * rather than about a category.</p>
     */
    SPLIT;

    /** What a head may propose. A split is decided on the bill, never in the taxonomy. */
    public boolean isProposable() {
        return this != SPLIT;
    }
}
