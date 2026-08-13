package in.nirman.modules.masterdata.service;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The master-data module's public read API for trades.
 *
 * <p>Planning holds a productivity norm per trade and has to show a person "Mason" rather than a
 * UUID; the skilled flag is what lets a labour forecast split into skilled and unskilled without
 * planning restating a classification the master data already owns.</p>
 */
public interface SkillLookup {

    /** @param skilled false for a helper, true for every trade that carries a skilled rate */
    record SkillInfo(UUID id, String code, String name, boolean skilled) {
    }

    /** Every trade the organisation has, in code order. */
    List<SkillInfo> all();

    /** Bulk form. Ids belonging to another organisation are absent from the map. */
    Map<UUID, SkillInfo> byIds(Collection<UUID> skillCategoryIds);
}
