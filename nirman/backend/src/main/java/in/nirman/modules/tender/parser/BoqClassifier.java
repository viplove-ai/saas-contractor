package in.nirman.modules.tender.parser;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Sorts a BOQ line into a trade category, and from there into civil or electrical work.
 *
 * <p>The category lands on {@code boq_items.category}, which shares a string space with
 * {@code material_consumption_norms.work_category} — so classifying a line is what lets a
 * consumption norm find it without a human mapping every row by hand.</p>
 *
 * <p>Rules are ordered, and the order is the point: the most specific trade wins. A fire
 * hydrant line mentions pipes, and a solar panel line mentions cables, so if plumbing and
 * electrical were tested first every specialist item would disappear into them.</p>
 */
public final class BoqClassifier {

    private record Rule(String category, List<String> keywords) {}

    public static final String UNALLOCATED = "Unallocated BOQ Balance";
    private static final String FALLBACK = "Miscellaneous";

    private static final List<Rule> RULES = List.of(
            new Rule("Firefighting & Fire Alarm", List.of("fire alarm", "fire fighting",
                    "firefighting", "hydrant", "hose reel", "smoke detector", "heat detector",
                    "extinguisher", "sprinkler")),
            new Rule("Solar & Renewable Energy", List.of("solar", "photovoltaic", "pv module",
                    "inverter", "dcdb")),
            new Rule("IT, CCTV & Communications", List.of("cctv", "camera", "nvr", "epabx",
                    "epbax", "telephone", "cat-6", "cat 6", "lan", "network", "data socket",
                    "wifi", "wi-fi", "wireless access point")),
            new Rule("HVAC & Mechanical", List.of("hvac", "air conditioning", "air conditioner",
                    "split type ac", "ton capacity", "ventilation", "duct", "chiller", "ahu",
                    "exhaust fan")),
            new Rule("Plumbing & Sanitary", List.of("sanitary", "water supply", "soil pipe",
                    "waste pipe", "sewer", "drainage", "wash basin", "water closet", "urinal",
                    "cp brass", "g.i. pipe", "borewell", "tube well", "pump set", "nominal bore",
                    "s.w. pipe", "hubless", "epdm rubber gasket", "lpm at")),
            new Rule("Electrical", List.of("wiring", "cable", "conduit", "mccb", "mcb",
                    "distribution board", "switch", "socket", "luminaire", "light fitting",
                    "earthing", "lightning conductor", "electrical", "transformer", "generator",
                    "ups", "sqmm", "led module", "geyser")),
            new Rule("Doors, Windows & Joinery", List.of("door", "window", "shutter", "frame",
                    "joinery", "cupboard", "wpc", "upvc", "aluminium glazing", "sal wood",
                    "teak wood", "butt hinges")),
            new Rule("Roofing & Waterproofing", List.of("roofing", "waterproofing",
                    "water proofing", "water proof", "damp proof", "bitumen",
                    "terrace treatment", "rain water", "gutter", "khurra",
                    "sheet shall be fixed")),
            new Rule("Flooring & Finishes", List.of("flooring", "tile", "granite", "marble",
                    "plaster", "painting", "paint", "white washing", "finishing",
                    "false ceiling", "polishing", "cladding", "new work", "two or more coats",
                    "cement based putty", "cement primer", "kota stone")),
            new Rule("Reinforcement & Structural Steel", List.of("reinforcement", "tmt",
                    "steel bar", "structural steel", "steel work", "m.s.", "mild steel",
                    "railing", "grating", "thermo-mechanically", "welded type tubes",
                    "guard bar", "bars of grade")),
            new Rule("Concrete & RCC", List.of("concrete", "r.c.c", "rcc", "centering",
                    "shuttering", "form work", "cement content", "columns", "pillars",
                    "abutments", "suspended floors", "lintels", "beams", "cantilevers",
                    "walls (any thickness)", "area of slab", "1:2:4", "1:5:10")),
            new Rule("Masonry", List.of("brick work", "brickwork", "masonry", "aac block",
                    "stone work", "cement mortar")),
            new Rule("Earthwork", List.of("earth work", "earthwork", "excavation", "excavating",
                    "excavated", "soil", "trench", "filling", "sand filling")),
            new Rule("External Development", List.of("road work", "paver", "kerb", "boundary",
                    "fencing", "landscaping", "retaining wall", "filter media", "weep hole",
                    "septic tank", "pvc coated")),
            new Rule("Testing & Investigation", List.of("soil testing", "bearing capacity",
                    "bore hole", "plate load", "testing laboratory", "investigation", "survey")));

    /**
     * Compiled once. Each keyword is bounded so {@code lan} does not match {@code planning}
     * and {@code duct} does not match {@code conductor} — the boundary is alphanumeric rather
     * than {@code \b} because several keywords end in a full stop.
     */
    private static final Map<String, List<Pattern>> COMPILED = RULES.stream()
            .collect(java.util.stream.Collectors.toMap(Rule::category,
                    rule -> rule.keywords().stream()
                            .map(keyword -> Pattern.compile(
                                    "(?<![a-z0-9])" + Pattern.quote(keyword.strip()) + "(?![a-z0-9])"))
                            .toList(),
                    (a, b) -> a, java.util.LinkedHashMap::new));

    /** When nothing in the text is decisive, the numbering convention usually is. */
    private static final Map<Integer, String> NUMBER_PREFIX = Map.ofEntries(
            Map.entry(1, "Earthwork"),
            Map.entry(2, "Concrete & RCC"),
            Map.entry(3, "Concrete & RCC"),
            Map.entry(4, "Masonry"),
            Map.entry(5, "Roofing & Waterproofing"),
            Map.entry(6, "Doors, Windows & Joinery"),
            Map.entry(7, "Reinforcement & Structural Steel"),
            Map.entry(8, "Flooring & Finishes"),
            Map.entry(9, "Roofing & Waterproofing"),
            Map.entry(10, "Flooring & Finishes"),
            Map.entry(12, "Plumbing & Sanitary"),
            Map.entry(13, "Plumbing & Sanitary"),
            Map.entry(14, "Plumbing & Sanitary"),
            Map.entry(15, "Roofing & Waterproofing"),
            Map.entry(16, "Roofing & Waterproofing"),
            Map.entry(17, "Plumbing & Sanitary"),
            Map.entry(18, "Doors, Windows & Joinery"),
            Map.entry(19, "External Development"));

    private static final Set<String> ELECTRICAL_CATEGORIES = Set.of(
            "Electrical", "Firefighting & Fire Alarm", "Solar & Renewable Energy",
            "IT, CCTV & Communications", "HVAC & Mechanical");

    private static final Pattern NUMBER_PREFIX_PATTERN = Pattern.compile("(\\d+)\\.");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private BoqClassifier() {
    }

    public static String classify(BoqLine item) {
        String itemNo = item.itemNo() == null ? "" : item.itemNo();
        if (itemNo.toUpperCase(Locale.ROOT).startsWith("UNALLOCATED")) {
            return UNALLOCATED;
        }
        String description = WHITESPACE.matcher(
                item.description() == null ? "" : item.description())
                .replaceAll(" ").toLowerCase(Locale.ROOT);
        for (Map.Entry<String, List<Pattern>> rule : COMPILED.entrySet()) {
            for (Pattern keyword : rule.getValue()) {
                if (keyword.matcher(description).find()) {
                    return rule.getKey();
                }
            }
        }
        // A "point" is an electrical term of art: one wiring point, priced per point.
        if ("point".equals(item.unit() == null ? "" : item.unit().toLowerCase(Locale.ROOT))) {
            return "Electrical";
        }
        Matcher prefix = NUMBER_PREFIX_PATTERN.matcher(itemNo);
        if (prefix.lookingAt()) {
            String category = NUMBER_PREFIX.get(Integer.parseInt(prefix.group(1)));
            if (category != null) {
                return category;
            }
        }
        return FALLBACK;
    }

    /**
     * Which schedule the line belongs to. The document's own heading wins when it said;
     * otherwise the trade decides, because an electrical item in a civil schedule is still
     * electrical work when the costs are added up.
     */
    public static String workPart(BoqLine item) {
        String explicit = WHITESPACE.matcher(item.workPart() == null ? "" : item.workPart())
                .replaceAll(" ").strip();
        if (!explicit.isEmpty()) {
            return explicit;
        }
        return ELECTRICAL_CATEGORIES.contains(classify(item)) ? BoqLine.ELECTRICAL : BoqLine.CIVIL;
    }
}
