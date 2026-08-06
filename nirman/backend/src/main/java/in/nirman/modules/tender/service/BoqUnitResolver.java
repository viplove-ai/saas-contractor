package in.nirman.modules.tender.service;

import in.nirman.modules.masterdata.service.UnitLookup;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Turns the unit a tender printed into a unit the system holds.
 *
 * <p>{@code boq_items.unit_id} is NOT NULL, so every imported line has to resolve to
 * something. A schedule prices work in {@code cum}, {@code sqm}, {@code Metre}, {@code each},
 * {@code point}, {@code kWp} and {@code per bag of 50 kg cement used}; an organisation's
 * master data typically holds ten codes, none of them the last four.</p>
 *
 * <p>So known spellings are folded onto the seeded codes, and anything else becomes a new
 * unit for that organisation rather than a rejected import. The preview marks which lines
 * were not recognised, because a unit the system invented is exactly the sort of thing worth
 * a glance before it is saved.</p>
 */
@Component
public class BoqUnitResolver {

    /** What a resolution produced, including whether it was a guess worth showing the user. */
    public record ResolvedUnit(UUID unitId, String code, boolean recognised) {}

    private static final Pattern NON_CODE = Pattern.compile("[^A-Z0-9]");

    /**
     * Spellings seen across the tender corpus, folded onto one code each. The right-hand
     * values match the codes seeded in master data where one exists.
     */
    private static final Map<String, String> ALIASES = Map.ofEntries(
            Map.entry("cum", "CUM"), Map.entry("cu m", "CUM"), Map.entry("cubic metre", "CUM"),
            Map.entry("sqm", "SQM"), Map.entry("sq m", "SQM"), Map.entry("square metre", "SQM"),
            Map.entry("kg", "KG"), Map.entry("kilogram", "KG"),
            Map.entry("quintal", "QTL"), Map.entry("qtl", "QTL"),
            Map.entry("mt", "MT"), Map.entry("metric tonne", "MT"),
            Map.entry("mtr", "MTR"), Map.entry("metre", "MTR"), Map.entry("meter", "MTR"),
            Map.entry("rmt", "MTR"), Map.entry("running metre", "MTR"),
            Map.entry("ltr", "LTR"), Map.entry("litre", "LTR"),
            Map.entry("nos", "NOS"), Map.entry("no", "NOS"), Map.entry("number", "NOS"),
            Map.entry("each", "EACH"), Map.entry("lot", "LOT"), Map.entry("job", "JOB"),
            Map.entry("point", "POINT"), Map.entry("pair", "PAIR"), Map.entry("set", "SET"),
            Map.entry("hour", "HOUR"), Map.entry("kwp", "KWP"),
            Map.entry("box", "BOX"), Map.entry("bag", "BAG"), Map.entry("per bag", "BAG"),
            Map.entry("per bag of 50 kg cement used", "BAG"));

    /** How finely each code is measured. Whole units for countable things, decimals for bulk. */
    private static final Map<String, Integer> DECIMALS = Map.of(
            "NOS", 0, "EACH", 0, "LOT", 0, "JOB", 0, "POINT", 0, "PAIR", 0, "SET", 0,
            "BAG", 0, "BOX", 0);

    private final UnitLookup units;

    public BoqUnitResolver(UnitLookup units) {
        this.units = units;
    }

    public ResolvedUnit resolve(String printedUnit) {
        String code = codeFor(printedUnit);
        boolean recognised = ALIASES.containsValue(code);
        UUID id = units.resolveOrCreate(code, displayName(printedUnit, code),
                DECIMALS.getOrDefault(code, 3));
        return new ResolvedUnit(id, code, recognised);
    }

    /** Resolves a code the client sent back after review, without inventing a display name. */
    public UUID resolveCode(String code) {
        String normalised = codeFor(code);
        return units.byCode(normalised)
                .orElseGet(() -> units.resolveOrCreate(normalised, normalised,
                        DECIMALS.getOrDefault(normalised, 3)));
    }

    /** @return the master-data code this printed unit belongs under */
    public String codeFor(String printedUnit) {
        if (printedUnit == null || printedUnit.isBlank()) {
            // A priced line with no unit is still a priced line; "Lot" is the honest label
            // for work quantified as one of something.
            return "LOT";
        }
        String cleaned = printedUnit.strip().toLowerCase(Locale.ROOT)
                .replace(".", " ").replaceAll("\\s+", " ").strip();
        String alias = ALIASES.get(cleaned);
        if (alias != null) {
            return alias;
        }
        String fallback = NON_CODE.matcher(cleaned.toUpperCase(Locale.ROOT)).replaceAll("");
        if (fallback.isEmpty()) {
            return "LOT";
        }
        return fallback.length() > 20 ? fallback.substring(0, 20) : fallback;
    }

    public Optional<UUID> existing(String code) {
        return units.byCode(code);
    }

    /** An unrecognised unit keeps its printed spelling as its name, so it reads as it did. */
    private static String displayName(String printedUnit, String code) {
        if (printedUnit == null || printedUnit.isBlank()) {
            return "Lot";
        }
        return ALIASES.containsValue(code) ? code : printedUnit.strip();
    }
}
