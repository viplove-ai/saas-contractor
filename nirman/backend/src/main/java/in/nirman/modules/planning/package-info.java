/**
 * Planning: the norms a construction programme is derived from, and later the programme itself.
 *
 * <p>Owns {@code work_type_profiles}, {@code labour_productivity_norms},
 * {@code work_sequence_norms} and {@code material_lead_times}. Other modules must not reach into
 * this package's repositories or entities.</p>
 *
 * <p>These are master data rather than transactions, and they are here rather than in
 * {@code masterdata} because nothing but planning consumes them: a productivity norm has no
 * meaning to attendance, and a lead time has none to the stock ledger. {@code masterdata} holds
 * what the whole system transacts in — units, materials, vendors, expense heads.</p>
 *
 * <p>The values shipped in {@code V30} are starting values, not answers. A contractor's own
 * productivity is his competitive advantage and the figure he will most want to correct, which
 * is why every norm is org-scoped, editable, and records its {@code source}. Deriving them from
 * the org's own verified attendance and stock ledger is the version worth building once a few
 * projects have finished.</p>
 *
 * <p>See {@code docs/10-planning-and-execution-strategy.md}.</p>
 */
package in.nirman.modules.planning;
