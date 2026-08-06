package in.nirman.modules.tender.parser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

/**
 * Holds the Java parser to what the Python implementation extracts from the same documents.
 *
 * <p>The expected values under {@code nit/expected} are not hand-written. They were generated
 * by running the original {@code extract_nit_pdf} over these fixtures, which makes the
 * comparison total: every scalar field and every BOQ row, rather than the dozen values
 * somebody thought to transcribe. Regenerating them is a deliberate act, and reviewing that
 * diff is how a change of behaviour gets noticed.</p>
 *
 * <p>Failures report every mismatched field at once rather than stopping at the first, because
 * when a shared helper regresses the useful signal is the shape of the whole failure.</p>
 */
class NitPdfParserFixtureTest {

    private static final Path PDF_DIR = Path.of("src/test/resources/nit/pdf");
    private static final Path EXPECTED_DIR = Path.of("src/test/resources/nit/expected");
    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * Documents where the two implementations read the schedule differently, and neither
     * reads it perfectly.
     *
     * <p>Both are Dehradun notices whose schedule puts the item number in a column far enough
     * from the description that PDFBox ends the line between them. {@code reflowItemNumbers}
     * rejoins most of those, but not identically to how pypdf happened to lay them out, so
     * the row lists diverge. The reference is not a gold standard here — on
     * {@code dehradun-01} it produces item numbers like {@code 8279.04}, fails to find the
     * estimated cost at all, and captures ₹13.34 Cr of a schedule this parser reads as
     * ₹15.28 Cr.</p>
     *
     * <p>So these two are held to quality invariants instead of to the reference's exact
     * output: comparable row count, item numbers at least as well formed, and never claiming
     * more than the tender states. Asserting equality with a known-wrong answer would only
     * lock the wrongness in. The scalar fields are still compared exactly, above.</p>
     */
    private static final List<String> SCHEDULE_DIVERGES =
            List.of("dehradun-01-hostel-balance", "dehradun-42-renovation");

    /** A well-formed CPWD item number: {@code 7}, {@code 7.1}, {@code 18.1.1}, {@code a)}. */
    private static final java.util.regex.Pattern WELL_FORMED_ITEM_NO =
            java.util.regex.Pattern.compile("\\d{1,3}(?:\\.\\d{1,3}){0,3}|[a-z]\\)",
                    java.util.regex.Pattern.CASE_INSENSITIVE);

    static Stream<String> fixtures() throws IOException {
        if (!Files.isDirectory(PDF_DIR)) {
            return Stream.empty();
        }
        try (var files = Files.list(PDF_DIR)) {
            return files.map(path -> path.getFileName().toString())
                    .filter(name -> name.endsWith(".pdf"))
                    .map(name -> name.substring(0, name.length() - 4))
                    .sorted()
                    .toList()
                    .stream();
        }
    }

    @Test
    void fixturesArePresent() throws IOException {
        assumeThat(Files.isDirectory(PDF_DIR)).as("fixture PDFs checked out").isTrue();
        assertThat(fixtures().toList()).as("NIT fixture PDFs").isNotEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("fixtures")
    void reproducesTheReferenceExtraction(String fixture) throws IOException {
        Path pdf = PDF_DIR.resolve(fixture + ".pdf");
        Path expectedFile = EXPECTED_DIR.resolve(fixture + ".json");
        assumeThat(Files.exists(pdf) && Files.exists(expectedFile))
                .as("fixture %s and its expected extraction are present", fixture).isTrue();

        JsonNode expected = JSON.readTree(Files.readString(expectedFile));
        NitExtraction actual = NitPdfParser.parse(Files.readAllBytes(pdf), fixture + ".pdf");

        assertSoftly(softly -> {
            softly.assertThat(actual.pageCount()).as("pageCount").isEqualTo(expected.get("page_count").asInt());
            softly.assertThat(actual.nitNo()).as("nitNo").isEqualTo(text(expected, "nit_no"));
            softly.assertThat(actual.workName()).as("workName").isEqualTo(text(expected, "work_name"));
            softly.assertThat(actual.division()).as("division").isEqualTo(text(expected, "division"));
            softly.assertThat(actual.location()).as("location").isEqualTo(text(expected, "location"));
            softly.assertThat(actual.bidType()).as("bidType").isEqualTo(text(expected, "bid_type"));
            softly.assertThat(actual.completionPeriod()).as("completionPeriod")
                    .isEqualTo(text(expected, "completion_period"));
            softly.assertThat(actual.contractorEligibility()).as("contractorEligibility")
                    .isEqualTo(text(expected, "contractor_eligibility"));
            softly.assertThat(actual.similarWorkCriteria()).as("similarWorkCriteria")
                    .isEqualTo(text(expected, "similar_work_criteria"));

            assertMoney(softly, "estimatedCost", actual.estimatedCost(), expected, "estimated_cost");
            assertMoney(softly, "civilEstimatedCost", actual.civilEstimatedCost(), expected,
                    "civil_estimated_cost");
            assertMoney(softly, "electricalEstimatedCost", actual.electricalEstimatedCost(), expected,
                    "electrical_estimated_cost");
            assertMoney(softly, "emdAmount", actual.emdAmount(), expected, "emd_amount");
            assertMoney(softly, "performanceGuaranteePercent", actual.performanceGuaranteePercent(),
                    expected, "performance_guarantee_percent");
            assertMoney(softly, "securityDepositPercent", actual.securityDepositPercent(), expected,
                    "security_deposit_percent");
            assertMoney(softly, "civilCostIndexPercent", actual.civilCostIndexPercent(), expected,
                    "civil_cost_index_percent");
            assertMoney(softly, "electricalCostIndexPercent", actual.electricalCostIndexPercent(),
                    expected, "electrical_cost_index_percent");
            assertMoney(softly, "boqTotal", actual.boqTotal(), expected, "boq_total");

            softly.assertThat(actual.civilDsrYear()).as("civilDsrYear")
                    .isEqualTo(integer(expected, "civil_dsr_year"));
            softly.assertThat(actual.electricalDsrYear()).as("electricalDsrYear")
                    .isEqualTo(integer(expected, "electrical_dsr_year"));

            softly.assertThat(actual.submissionClosing()).as("submissionClosing")
                    .isEqualTo(dateTime(expected, "submission_closing"));
            softly.assertThat(actual.bidOpening()).as("bidOpening")
                    .isEqualTo(dateTime(expected, "bid_opening"));

            softly.assertThat(actual.warnings()).as("warnings")
                    .containsExactlyInAnyOrderElementsOf(strings(expected.get("warnings")));

            if (SCHEDULE_DIVERGES.contains(fixture)) {
                assertUsableSchedule(softly, fixture, actual, expected);
            } else {
                assertBoq(softly, actual.boqItems(), expected.get("boq_items"));
            }
        });
    }

    /**
     * For the documents in {@link #SCHEDULE_DIVERGES}: the schedule must be at least as
     * trustworthy as the reference's, without being identical to it.
     */
    private static void assertUsableSchedule(org.assertj.core.api.SoftAssertions softly,
                                             String fixture, NitExtraction actual,
                                             JsonNode expected) {
        JsonNode expectedItems = expected.get("boq_items");
        int referenceRows = expectedItems.size();

        softly.assertThat(actual.boqItems().size())
                .as("%s: row count close to the reference's %d", fixture, referenceRows)
                .isBetween((int) (referenceRows * 0.95), (int) Math.ceil(referenceRows * 1.05));

        double actualWellFormed = wellFormedShare(actual.boqItems().stream()
                .map(BoqLine::itemNo).toList());
        List<String> referenceNumbers = new ArrayList<>();
        expectedItems.forEach(node -> referenceNumbers.add(node.get("item_no").asText()));
        softly.assertThat(actualWellFormed)
                .as("%s: share of well-formed item numbers, against the reference's %.4f",
                        fixture, wellFormedShare(referenceNumbers))
                .isGreaterThanOrEqualTo(wellFormedShare(referenceNumbers));

        // Reading more than the tender is worth would mean rows were invented or double
        // counted, and the reconciler could never correct it — it only ever adds a shortfall.
        BigDecimal stated = decimal(expected, "boq_total");
        if (stated != null) {
            BigDecimal captured = actual.boqItems().stream()
                    .map(BoqLine::effectiveAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            softly.assertThat(captured)
                    .as("%s: captured schedule value never exceeds the stated total", fixture)
                    .isLessThanOrEqualTo(stated);
        }
    }

    private static double wellFormedShare(List<String> itemNumbers) {
        if (itemNumbers.isEmpty()) {
            return 0;
        }
        long wellFormed = itemNumbers.stream()
                .filter(number -> WELL_FORMED_ITEM_NO.matcher(number).matches())
                .count();
        return (double) wellFormed / itemNumbers.size();
    }

    private static void assertBoq(org.assertj.core.api.SoftAssertions softly,
                                  List<BoqLine> actual, JsonNode expected) {
        // Compared as formatted rows so a mismatch reads as a diff of the schedule itself,
        // not as an object dump.
        List<String> want = new ArrayList<>();
        expected.forEach(node -> want.add(row(
                node.get("item_no").asText(),
                node.get("description").asText(),
                decimal(node, "quantity"),
                node.get("unit").asText(),
                decimal(node, "rate"),
                decimal(node, "amount"),
                node.get("work_part").asText())));
        List<String> have = actual.stream()
                .map(item -> row(item.itemNo(), item.description(), item.quantity(), item.unit(),
                        item.rate(), item.amount(), item.workPart()))
                .toList();

        softly.assertThat(have).as("BOQ row count").hasSameSizeAs(want);
        softly.assertThat(have).as("BOQ rows").isEqualTo(want);
    }

    private static String row(String itemNo, String description, BigDecimal quantity, String unit,
                              BigDecimal rate, BigDecimal amount, String workPart) {
        return "%s | %s | %s %s @ %s = %s | %s".formatted(itemNo, description,
                plain(quantity), unit, plain(rate), plain(amount), workPart);
    }

    private static void assertMoney(org.assertj.core.api.SoftAssertions softly, String field,
                                    BigDecimal actual, JsonNode expected, String key) {
        BigDecimal want = decimal(expected, key);
        if (want == null || actual == null) {
            softly.assertThat(plain(actual)).as(field).isEqualTo(plain(want));
            return;
        }
        softly.assertThat(actual).as(field).usingComparator(BigDecimal::compareTo).isEqualTo(want);
    }

    private static String plain(BigDecimal value) {
        return value == null ? null : value.stripTrailingZeros().toPlainString();
    }

    /**
     * Reference text, folded through the same typographic normalisation the extractor applies.
     *
     * <p>{@link NitTextExtractor#normalise} deliberately flattens curly quotes and dashes to
     * their ASCII forms, so patterns never have to spell both. The reference implementation
     * has no such step and keeps whatever the encoder emitted. That is the one difference
     * between the two outputs, it is intended, and folding the expectation the same way keeps
     * the assertion about extraction rather than about punctuation.</p>
     */
    private static String text(JsonNode node, String key) {
        JsonNode value = node.get(key);
        return value == null || value.isNull() ? null : NitTextExtractor.normalise(value.asText());
    }

    private static Integer integer(JsonNode node, String key) {
        JsonNode value = node.get(key);
        return value == null || value.isNull() ? null : value.asInt();
    }

    private static BigDecimal decimal(JsonNode node, String key) {
        JsonNode value = node.get(key);
        return value == null || value.isNull() ? null : new BigDecimal(value.asText());
    }

    /** Python renders a naive datetime as {@code 2026-07-01 15:30:00}. */
    private static LocalDateTime dateTime(JsonNode node, String key) {
        String value = text(node, key);
        return value == null ? null : LocalDateTime.parse(value.replace(' ', 'T'));
    }

    private static List<String> strings(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(node -> values.add(node.asText()));
        return values;
    }
}
