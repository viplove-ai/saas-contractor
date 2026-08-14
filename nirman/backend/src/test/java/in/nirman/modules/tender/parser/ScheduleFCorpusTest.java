package in.nirman.modules.tender.parser;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

/**
 * Holds the Schedule F reader to what the corpus of real notices actually says.
 *
 * <p>Unlike {@link NitPdfParserFixtureTest}, there is no reference implementation to compare
 * against — the Python parser never read Schedule F. So these expectations were transcribed by
 * reading the seven fixture documents, and each one is a fact about a government notice rather
 * than a snapshot of current behaviour. That is the point: if a pattern change makes a
 * milestone table read differently, the failure is a claim that the tender says something it
 * does not.</p>
 *
 * <p>The corpus covers all four layouts Schedule F appears in. Three matter enough to name:</p>
 *
 * <ul>
 *   <li>{@code almora-35-chaura-bop} is the only <b>physical</b> milestone table — prose naming
 *       the activities, joined by "or" to a financial equivalent. It is also the one whose
 *       financial cell wraps across a page break into the following row, which is what
 *       {@code assignFinancialPercents} exists to repair.</li>
 *   <li>{@code dehradun-01-hostel-balance} announces its table with a different heading
 *       entirely, and mixes physical and purely financial rows in one table.</li>
 *   <li>{@code dehradun-42-renovation} states its Clause 7 minimums in Part A and then defers
 *       to Part A in Schedule F. Reading the first statement rather than the deferral is what
 *       makes this document yield figures at all.</li>
 * </ul>
 */
class ScheduleFCorpusTest {

    private static final Path PDF_DIR = Path.of("src/test/resources/nit/pdf");

    /**
     * What each notice says, transcribed from the document.
     *
     * <p>A milestone is encoded as {@code <time>|<financial>|<withheld>|<physical>} — compact
     * enough to read as a table, and a mismatch reports as a diff of the milestone chain rather
     * than as an object dump. {@code -} is an absent figure, which is a real reading: chaura's
     * final milestone is defined only by handing over and defect rectification, and states no
     * percentage of the tendered value at all.</p>
     */
    private record Expected(AllowedTime completion, Integer startDays, Boolean clause7a,
                            List<String> interimMinimums, List<String> milestones) {
    }

    private static final Map<String, Expected> CORPUS = Map.of(
            "almora-23-main-gate", new Expected(months(6), 10, true,
                    List.of("*=800000"),
                    List.of("1 MONTHS|15|0.5|financial", "2 MONTHS|30|0.5|financial",
                            "3 MONTHS|45|1|financial", "4 MONTHS|60|1|financial",
                            "5 MONTHS|75|1|financial", "6 MONTHS|100|1|financial")),

            "almora-25-asi-mess", new Expected(months(2), 10, true,
                    List.of("*=600000"),
                    List.of("1 MONTHS|50|2.5|financial", "2 MONTHS|100|2.5|financial")),

            // The only notice that states a milestone in days rather than months.
            "almora-29-tile-flooring", new Expected(months(3), 10, true,
                    List.of("*=350000"),
                    List.of("15 DAYS|50|2.5|financial", "2 MONTHS|100|2.5|financial")),

            "almora-30-tile-work", new Expected(months(6), 10, true,
                    List.of("*=700000"),
                    List.of("1 MONTHS|15|0.5|financial", "2 MONTHS|30|1|financial",
                            "3 MONTHS|45|1|financial", "4 MONTHS|60|1|financial",
                            "5 MONTHS|75|1|financial", "6 MONTHS|100|0.5|financial")),

            // Physical milestones, and a civil/E&M split on the interim minimum.
            "almora-35-chaura-bop", new Expected(months(12), 10, true,
                    List.of("Civil Works=2100000", "E&M Works=500000"),
                    List.of("2 MONTHS|10|0.5|physical", "5 MONTHS|35|1.25|physical",
                            "8 MONTHS|60|1.25|physical", "11 MONTHS|90|1.5|physical",
                            "12 MONTHS|-|0.5|physical")),

            "dehradun-01-hostel-balance", new Expected(months(12), 10, true,
                    List.of("Civil Works=15000000", "E&M Works=3500000"),
                    List.of("3 MONTHS|20|1.25|physical", "6 MONTHS|50|1.25|physical",
                            "9 MONTHS|75|1.25|financial", "12 MONTHS|100|1.25|physical")),

            "dehradun-42-renovation", new Expected(months(8), 10, true,
                    List.of("Civil Works=2000000", "E&M Works=600000"),
                    List.of("2 MONTHS|25|1.25|financial", "4 MONTHS|50|1.25|financial",
                            "6 MONTHS|75|1.25|financial", "8 MONTHS|100|1.25|financial")));

    private static AllowedTime months(int value) {
        return new AllowedTime(value, AllowedTime.Unit.MONTHS);
    }

    static Stream<String> fixtures() {
        return CORPUS.keySet().stream().sorted();
    }

    @Test
    void everyFixtureIsCovered() throws IOException {
        assumeThat(Files.isDirectory(PDF_DIR)).as("fixture PDFs checked out").isTrue();
        try (var files = Files.list(PDF_DIR)) {
            List<String> present = files.map(p -> p.getFileName().toString())
                    .filter(n -> n.endsWith(".pdf"))
                    .map(n -> n.substring(0, n.length() - 4)).sorted().toList();
            // A new fixture with no transcription would otherwise be silently unread here.
            assertThat(present).as("every fixture has transcribed Schedule F expectations")
                    .allMatch(CORPUS::containsKey);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("fixtures")
    void readsWhatTheNoticeSays(String fixture) throws IOException {
        Path pdf = PDF_DIR.resolve(fixture + ".pdf");
        assumeThat(Files.exists(pdf)).as("fixture %s is present", fixture).isTrue();

        Expected want = CORPUS.get(fixture);
        ScheduleFExtractor.ScheduleF got =
                NitPdfParser.parse(Files.readAllBytes(pdf), fixture + ".pdf").scheduleF();

        assertSoftly(softly -> {
            softly.assertThat(got.completionTime()).as("time allowed").isEqualTo(want.completion());
            softly.assertThat(got.startReckoningDays()).as("days to reckon the start")
                    .isEqualTo(want.startDays());
            softly.assertThat(got.clause7aApplicable()).as("clause 7A applies")
                    .isEqualTo(want.clause7a());
            softly.assertThat(got.interimMinimums().stream().map(ScheduleFCorpusTest::encode)
                            .toList())
                    .as("Clause 7 minimum value of work for an interim bill")
                    .isEqualTo(want.interimMinimums());
            softly.assertThat(got.milestones().stream().map(ScheduleFCorpusTest::encode).toList())
                    .as("milestone chain").isEqualTo(want.milestones());
        });
    }

    /**
     * A milestone chain must be usable as a schedule, whatever the individual figures are.
     * These hold for every notice and would catch a row read twice or read out of order —
     * failures that leave each field plausible and the chain nonsense.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("fixtures")
    void theChainIsCoherent(String fixture) throws IOException {
        Path pdf = PDF_DIR.resolve(fixture + ".pdf");
        assumeThat(Files.exists(pdf)).as("fixture %s is present", fixture).isTrue();

        List<MilestoneLine> milestones =
                NitPdfParser.parse(Files.readAllBytes(pdf), fixture + ".pdf")
                        .scheduleF().milestones();

        assertSoftly(softly -> {
            softly.assertThat(milestones).as("milestones found").isNotEmpty();
            softly.assertThat(milestones.stream().map(MilestoneLine::sequence).toList())
                    .as("numbered 1..n with no gaps or repeats")
                    .isEqualTo(java.util.stream.IntStream.rangeClosed(1, milestones.size())
                            .boxed().toList());

            List<Integer> days = milestones.stream()
                    .map(m -> m.timeAllowed() == null ? null : m.timeAllowed().approximateDays())
                    .toList();
            softly.assertThat(days).as("every milestone is dated").doesNotContainNull();
            softly.assertThat(days).as("milestones run forwards").isSorted();

            List<BigDecimal> financial = milestones.stream()
                    .map(MilestoneLine::financialPercent).filter(java.util.Objects::nonNull)
                    .toList();
            softly.assertThat(financial).as("cumulative percentages only ever rise").isSorted();
            softly.assertThat(financial).as("no milestone claims more than the whole contract")
                    .allMatch(percent -> percent.compareTo(new BigDecimal("100")) <= 0);

            // The withheld percentages are what the contract costs if the dates slip, so a
            // reading that summed past the contract value would be self-evidently wrong.
            BigDecimal withheld = milestones.stream().map(MilestoneLine::withheldPercent)
                    .filter(java.util.Objects::nonNull)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            softly.assertThat(withheld).as("total withholding at risk stays a sane share")
                    .isBetween(BigDecimal.ZERO, new BigDecimal("25"));
        });
    }

    /**
     * The additional-guarantee clause, which one notice carries and nine do not.
     *
     * <p>Both halves matter. Reading it where it exists is what tells a contractor that bidding
     * thirty percent below a one-crore estimate asks for ten lakh of extra bank guarantee before
     * the work order. <b>Not</b> reading it into the nine that are silent matters just as much:
     * an invented clause would put lakhs of imaginary guarantee in front of somebody deciding
     * how deep to bid.</p>
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("fixtures")
    void additionalGuaranteeIsReadOnlyWhereItIsStated(String fixture) throws IOException {
        Path pdf = PDF_DIR.resolve(fixture + ".pdf");
        assumeThat(Files.exists(pdf)).as("fixture %s is present", fixture).isTrue();

        var guarantee = NitPdfParser.parse(Files.readAllBytes(pdf), fixture + ".pdf")
                .scheduleF().additionalGuarantee();

        if ("dehradun-01-hostel-balance".equals(fixture)) {
            assertThat(guarantee).as("this notice states the clause").isNotNull();
            assertThat(guarantee.thresholdPercent()).isEqualByComparingTo("80");
            // The CPWD form's own arithmetic: the shortfall against the threshold, not a
            // percentage of anything. Its worked example is "if ECPT is A and quoted amount is
            // 0.7A then the amount of APG shall be 0.8A - 0.7A".
            assertThat(guarantee.method()).isEqualTo("DIFFERENCE");
        } else {
            assertThat(guarantee).as("%s states no such clause, so none is applied", fixture)
                    .isNull();
        }
    }

    private static String encode(MilestoneLine milestone) {
        return "%s %s|%s|%s|%s".formatted(
                milestone.timeAllowed() == null ? "-" : milestone.timeAllowed().value(),
                milestone.timeAllowed() == null ? "-" : milestone.timeAllowed().unit(),
                plain(milestone.financialPercent()), plain(milestone.withheldPercent()),
                milestone.physical() ? "physical" : "financial");
    }

    private static String encode(InterimMinimum minimum) {
        return "%s=%s".formatted(minimum.workPart() == null ? "*" : minimum.workPart(),
                plain(minimum.amount()));
    }

    private static String plain(BigDecimal value) {
        return value == null ? "-" : value.stripTrailingZeros().toPlainString();
    }
}
