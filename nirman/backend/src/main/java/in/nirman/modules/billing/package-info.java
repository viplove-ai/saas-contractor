/**
 * Running account bills, and the measurements they are built from.
 *
 * <p>The module owns two things the rest of the system deliberately does not touch. The
 * <b>measurement sheet</b> is the engineer's ruled page — one contract item, a dozen rows of
 * {@code nos × mult × L × B × H}, and a total he worked out by hand before the app ever saw
 * it. The <b>running account bill</b> sweeps signed sheets up to a cutoff date, freezes what
 * they came to, and never recomputes it again.</p>
 *
 * <p>It reads the contract through {@link in.nirman.modules.project.service.BoqLookup} and
 * never touches {@code boq_items} itself, which is the same boundary every other module
 * keeps. It is scoped by {@link in.nirman.security.SiteAccessGuard} on every read and write,
 * including for BILLING_ONLY projects — which is exactly why those projects are given a site
 * they did not ask for.</p>
 *
 * <p><b>What this module is not.</b> It does not post to {@code boq_progress_entries}. A
 * daily progress report says what a day <i>reported</i>; a measurement sheet says what a tape
 * later <i>measured</i>, and the two are allowed to differ. Reconciling them by posting
 * automatic adjustments between the ledgers is the part that can quietly rewrite a figure
 * somebody signed, so it is deferred rather than guessed at. See the V46 header.</p>
 */
package in.nirman.modules.billing;
