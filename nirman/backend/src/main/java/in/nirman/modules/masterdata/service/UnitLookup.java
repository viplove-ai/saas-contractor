package in.nirman.modules.masterdata.service;

import java.util.Optional;
import java.util.UUID;

/**
 * Units of measure, for modules that need to reference one without owning master data.
 *
 * <p>{@code boq_items.unit_id} is NOT NULL, so anything importing a schedule has to resolve
 * every unit it read or fail. A tender prices work in units an organisation may never have
 * entered — {@code point}, {@code kWp}, {@code Lot} — and refusing an import over a missing
 * reference row would be the wrong trade: the unit is a label, the schedule is the data.
 * Hence {@link #resolveOrCreate}.</p>
 */
public interface UnitLookup {

    Optional<UUID> byCode(String code);

    /** Resolves a unit by code for the current organisation, creating it if it has none. */
    UUID resolveOrCreate(String code, String name, int decimalPlaces);
}
