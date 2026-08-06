/**
 * Tenders: reading a Notice Inviting Tender and turning it into a project.
 *
 * <p>Owns {@code nit_documents}. Other modules must not reach into this package's
 * repositories or entities.</p>
 *
 * <p>This module depends on {@code project} and {@code masterdata}, never the other way
 * round, and only through their published lookup interfaces —
 * {@link in.nirman.modules.project.service.ProjectProvisioning} and
 * {@link in.nirman.modules.masterdata.service.UnitLookup}. The BOQ rows an import creates are
 * written by the project module, so the invariants on {@code boq_items} stay owned in one
 * place.</p>
 */
package in.nirman.modules.tender;
