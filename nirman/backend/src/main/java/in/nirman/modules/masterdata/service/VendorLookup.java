package in.nirman.modules.masterdata.service;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * Supplier names, for modules that hold a vendor id and have to print it.
 *
 * <p>A hired machine, a delivery and a bill all name the man who sent them, and every screen
 * that shows one shows his name rather than his uuid. The alternative is each module reaching
 * into {@code VendorRepository}, which is the coupling the module boundaries exist to
 * prevent.</p>
 */
public interface VendorLookup {

    /**
     * Names for the ids given, in one query. Ids the organisation does not hold are simply
     * absent — a caller printing a register must not fail because one row points at a
     * supplier somebody deleted.
     */
    Map<UUID, String> names(Collection<UUID> vendorIds);

    /**
     * @throws in.nirman.common.BusinessException 404 if no such live vendor in the caller's
     *                                            organisation
     */
    String requireName(UUID vendorId);
}
