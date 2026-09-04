package in.nirman.modules.dpr.service;

import java.time.LocalDate;
import java.util.UUID;

/**
 * The report module's public read API for the one question another module asks of a day:
 * has the office closed the book on it.
 *
 * <p>The muster reaches back for a man taken on late — see {@code AttendanceService} — and
 * the day it may not reach back into is one whose report the office has countersigned,
 * because the head count on an approved report is a figure the department has been given.
 * Anything short of that (draft, handed over, signed by the engineer) is still a document
 * whose muster can differ from today's records, which docs/09 already calls information
 * rather than a fault.</p>
 *
 * <p>Unguarded: the caller already passed the site check that got it there, and a status is
 * not a figure.</p>
 */
public interface DprLookup {

    /** True when a live report for the site and day stands {@code APPROVED}. */
    boolean approvedOn(UUID siteId, LocalDate date);
}
