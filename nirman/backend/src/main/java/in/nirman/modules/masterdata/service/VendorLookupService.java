package in.nirman.modules.masterdata.service;

import in.nirman.common.BusinessException;
import in.nirman.modules.masterdata.domain.Vendor;
import in.nirman.modules.masterdata.repository.VendorRepository;
import in.nirman.security.CurrentUserProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * {@link VendorLookup}, kept apart from {@link MasterDataService} for the reason
 * {@link UnitLookupService} is: this resolves a label for a caller that has already passed
 * the check which got it here, so it carries no permission of its own.
 */
@Service
@Transactional(readOnly = true)
public class VendorLookupService implements VendorLookup {

    private final VendorRepository vendors;
    private final CurrentUserProvider currentUser;

    public VendorLookupService(VendorRepository vendors, CurrentUserProvider currentUser) {
        this.vendors = vendors;
        this.currentUser = currentUser;
    }

    @Override
    public Map<UUID, String> names(Collection<UUID> vendorIds) {
        List<UUID> wanted = vendorIds.stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (wanted.isEmpty()) {
            return Map.of();
        }
        UUID orgId = currentUser.currentOrgId();
        return vendors.findAllById(wanted).stream()
                .filter(vendor -> vendor.getOrgId().equals(orgId))
                .collect(Collectors.toMap(Vendor::getId, Vendor::getName));
    }

    @Override
    public String requireName(UUID vendorId) {
        return vendors.findByIdAndOrgIdAndDeletedAtIsNull(vendorId, currentUser.currentOrgId())
                .map(Vendor::getName)
                .orElseThrow(() -> BusinessException.notFound("Vendor", vendorId));
    }
}
