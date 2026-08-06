package in.nirman.modules.masterdata.service;

import in.nirman.modules.masterdata.domain.Unit;
import in.nirman.modules.masterdata.repository.UnitRepository;
import in.nirman.security.CurrentUserProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link UnitLookup}, kept apart from {@link MasterDataService} for the same reason
 * {@link MaterialLookupService} is.
 *
 * <p>{@code MasterDataService} carries a class-level {@code masterdata:read} check because it
 * serves the master-data screens. This does not: it resolves a label for a caller already
 * checked for the permission that got it here. Gating it again would mean someone allowed to
 * import a tender cannot import one that happens to price work per point.</p>
 */
@Service
@Transactional
public class UnitLookupService implements UnitLookup {

    private final UnitRepository units;
    private final CurrentUserProvider currentUser;

    public UnitLookupService(UnitRepository units, CurrentUserProvider currentUser) {
        this.units = units;
        this.currentUser = currentUser;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UUID> byCode(String code) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        return units.findByOrgIdAndCode(currentUser.currentOrgId(), normalise(code))
                .map(Unit::getId);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Created lazily on first use rather than seeded into every organisation, because most
     * of these units belong to one tender's vocabulary and would otherwise be clutter on the
     * master-data screen of a firm that never bids electrical work.</p>
     */
    @Override
    public UUID resolveOrCreate(String code, String name, int decimalPlaces) {
        UUID orgId = currentUser.currentOrgId();
        String normalised = normalise(code);
        return units.findByOrgIdAndCode(orgId, normalised)
                .map(Unit::getId)
                .orElseGet(() -> units.save(new Unit(orgId, normalised,
                        name == null || name.isBlank() ? normalised : name.strip(),
                        decimalPlaces)).getId());
    }

    /** Unit codes are upper case throughout the master data; the column allows 20 characters. */
    private static String normalise(String code) {
        String upper = code.strip().toUpperCase(Locale.ROOT);
        return upper.length() > 20 ? upper.substring(0, 20) : upper;
    }
}
