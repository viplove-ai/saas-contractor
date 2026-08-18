/**
 * Treasury: the company's money that is not with the company.
 *
 * <p>Owns {@code project_securities} — the earnest money, performance guarantees and
 * retentions lodged against each contract, and the dates each one comes back on. Everything
 * this module reports is either a row in that register or arithmetic over it; no total is
 * stored.</p>
 *
 * <p>It depends on {@code project} and {@code tender} and never the other way round, and only
 * through their published lookups — {@link in.nirman.modules.project.service.ProjectLookup}
 * for the contract and {@link in.nirman.modules.tender.service.NitLookup} for what the notice
 * demanded. The notice proposes an amount; this register records the one actually lodged.</p>
 */
package in.nirman.modules.treasury;
