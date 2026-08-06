package in.nirman.modules.tender.parser;

import in.nirman.common.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The parser's pieces, tested away from any PDF.
 *
 * <p>The fixture test proves the whole reader agrees with the reference on real documents.
 * These cover the decisions that fixture cannot reach — a rejected upload, a rounding
 * threshold, a keyword that must not match the middle of a longer word — where a regression
 * would otherwise surface as a number quietly moving on a tender nobody re-reads.</p>
 */
class NitParserUnitTest {

    @Nested
    @DisplayName("rejecting what cannot be parsed")
    class Guards {

        @Test
        void refusesAnEmptyUpload() {
            assertThatThrownBy(() -> NitTextExtractor.pageTexts(new byte[0]))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("empty");
        }

        @Test
        void refusesNull() {
            assertThatThrownBy(() -> NitTextExtractor.pageTexts(null))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        void refusesAFileThatIsNotAPdf() {
            byte[] notAPdf = "This is a text file, not a tender.".getBytes();
            assertThatThrownBy(() -> NitTextExtractor.pageTexts(notAPdf))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("could not be read");
        }

        @Test
        void reportsNoScheduleRatherThanFailingOnTextWithoutOne() {
            NitExtraction extraction = NitPdfParser.parsePages(
                    List.of("A letter about nothing in particular."), "letter.pdf");

            assertThat(extraction.boqItems()).isEmpty();
            assertThat(extraction.warnings())
                    .anyMatch(warning -> warning.contains("No priced BOQ rows"))
                    .anyMatch(warning -> warning.contains("NIT number"))
                    .anyMatch(warning -> warning.contains("Estimated cost"));
        }
    }

    @Nested
    @DisplayName("reading a schedule")
    class Schedule {

        /** The shape a CPWD schedule arrives in: a header row, then wrapped description rows. */
        private static final String PAGE = """
                SCHEDULE OF QUANTITY
                S.No. Description Qty Unit  Rate   Amount
                1 FLOORING
                1.1 Providing and laying Vitrified tiles in different sizes
                (thickness to be specified by the manufacturer), in all
                colours and shade, laid with adhesive.
                1.1.1 Size of Tile 600x600 mm 155.00 sqm 2377.15 368458.00
                1.2 Grouting the joints of flooring tiles
                1.2.1 Size of Tile 600x600 mm 1410.00 sqm 387.90 546939.00
                TOTAL 915397.00
                """;

        @Test
        void readsRowsWhoseDescriptionWrappedAcrossLines() {
            BoqScheduleParser.Result result = BoqScheduleParser.parse(List.of(PAGE), null);

            assertThat(result.items()).hasSize(2);
            BoqLine first = result.items().get(0);
            assertThat(first.itemNo()).isEqualTo("1.1.1");
            assertThat(first.description()).isEqualTo("Size of Tile 600x600 mm");
            assertThat(first.quantity()).isEqualByComparingTo("155.00");
            assertThat(first.unit()).isEqualTo("sqm");
            assertThat(first.rate()).isEqualByComparingTo("2377.15");
            assertThat(first.amount()).isEqualByComparingTo("368458.00");
            assertThat(first.workPart()).isEqualTo(BoqLine.CIVIL);
        }

        @Test
        void prefersTheTotalThatAgreesWithTheStatedEstimate() {
            String page = PAGE + "GRAND TOTAL 915397.00\n";
            BoqScheduleParser.Result result =
                    BoqScheduleParser.parse(List.of(page), new BigDecimal("915397.00"));

            assertThat(result.total()).isEqualByComparingTo("915397.00");
        }

        @Test
        void switchesWorkPartWhenTheElectricalScheduleBegins() {
            String electrical = """
                    SCHEDULE OF QUANTITY OF (E&M WORKS)
                    S.No. Description Qty Unit Rate Amount
                    1.1 Wiring for light point with PVC insulated copper conductor 40.00 point 1685.00 67400.00
                    """;
            BoqScheduleParser.Result result =
                    BoqScheduleParser.parse(List.of(PAGE, electrical), null);

            assertThat(result.items()).hasSize(3);
            assertThat(result.items().get(2).workPart()).isEqualTo(BoqLine.ELECTRICAL);
        }

        @Test
        void doesNotMistakeAWrappedQuantityForAnItemNumber() {
            // "12.00 cum ..." opening a line reads exactly like item number 12.00.
            String page = """
                    SCHEDULE OF QUANTITY
                    Description Qty Unit Rate Amount
                    3.1 Providing and laying cement concrete of specified grade
                    12.00 cum 5400.00 64800.00
                    """;
            BoqScheduleParser.Result result = BoqScheduleParser.parse(List.of(page), null);

            assertThat(result.items()).singleElement()
                    .extracting(BoqLine::itemNo).isEqualTo("3.1");
        }
    }

    @Nested
    @DisplayName("classifying a line")
    class Classification {

        private static BoqLine line(String itemNo, String description, String unit) {
            return new BoqLine(itemNo, description, BigDecimal.ONE, unit,
                    BigDecimal.ONE, BigDecimal.ONE, null);
        }

        @Test
        void prefersTheMoreSpecificTradeOverTheGeneralOne() {
            // Mentions a pipe, but a fire hydrant is firefighting, not plumbing.
            assertThat(BoqClassifier.classify(
                    line("5.1", "Providing and fixing fire hydrant with hose reel", "each")))
                    .isEqualTo("Firefighting & Fire Alarm");
            // Mentions a cable, but a solar array is renewable energy, not electrical.
            assertThat(BoqClassifier.classify(
                    line("6.1", "Supply of solar photovoltaic module with cable", "each")))
                    .isEqualTo("Solar & Renewable Energy");
        }

        @Test
        void doesNotMatchAKeywordInsideALongerWord() {
            // "lan" must not fire on "planning"; "duct" must not fire on "conductor".
            assertThat(BoqClassifier.classify(line("99.1", "Planning and site layout", "each")))
                    .isEqualTo("Miscellaneous");
        }

        @Test
        void treatsAPointAsElectricalWhenNothingElseDecides() {
            assertThat(BoqClassifier.classify(line("99.1", "Primary supply run", "point")))
                    .isEqualTo("Electrical");
        }

        @Test
        void fallsBackToTheNumberingConventionWhenTheTextSaysNothing() {
            assertThat(BoqClassifier.classify(line("1.4", "As per drawing", "cum")))
                    .isEqualTo("Earthwork");
        }

        @Test
        void sendsElectricalTradesToTheElectricalSchedule() {
            assertThat(BoqClassifier.workPart(line("6.1", "CCTV camera with NVR", "each")))
                    .isEqualTo(BoqLine.ELECTRICAL);
            assertThat(BoqClassifier.workPart(line("2.1", "Cement concrete 1:2:4", "cum")))
                    .isEqualTo(BoqLine.CIVIL);
        }

        @Test
        void namesAReconciliationRowForWhatItIs() {
            assertThat(BoqClassifier.classify(line("UNALLOCATED-Civil Works", "gap", "Lot")))
                    .isEqualTo(BoqClassifier.UNALLOCATED);
        }
    }

    @Nested
    @DisplayName("reconciling against the stated total")
    class Reconciliation {

        private static BoqLine priced(String itemNo, String rate, String workPart) {
            return new BoqLine(itemNo, "Work", BigDecimal.ONE, "cum",
                    new BigDecimal(rate), new BigDecimal(rate), workPart);
        }

        @Test
        void addsAPlaceholderForWhatTheScheduleDidNotAccountFor() {
            List<BoqLine> result = BoqReconciler.reconcile(
                    List.of(priced("1.1", "900000", BoqLine.CIVIL)),
                    new BigDecimal("1000000"), null);

            assertThat(result).hasSize(2);
            BoqLine gap = result.get(1);
            assertThat(gap.itemNo()).isEqualTo("UNALLOCATED");
            assertThat(gap.synthetic()).isTrue();
            assertThat(gap.rate()).isEqualByComparingTo("100000");
            assertThat(gap.unit()).isEqualTo("Lot");
        }

        @Test
        void leavesRoundingAlone() {
            // A gap under 0.1% of the stated total is rounding, not a missing row.
            List<BoqLine> result = BoqReconciler.reconcile(
                    List.of(priced("1.1", "999999", BoqLine.CIVIL)),
                    new BigDecimal("1000000"), null);

            assertThat(result).hasSize(1);
        }

        @Test
        void reconcilesEachWorkPartSeparatelyWhenBothTotalsAreStated() {
            List<BoqLine> result = BoqReconciler.reconcile(
                    List.of(priced("1.1", "900000", BoqLine.CIVIL),
                            priced("2.1", "400000", BoqLine.ELECTRICAL)),
                    new BigDecimal("1500000"),
                    Map.of(BoqLine.CIVIL, new BigDecimal("1000000"),
                            BoqLine.ELECTRICAL, new BigDecimal("500000")));

            assertThat(result).hasSize(4);
            assertThat(result.subList(2, 4))
                    .allMatch(BoqLine::synthetic)
                    .extracting(BoqLine::itemNo)
                    .containsExactlyInAnyOrder("UNALLOCATED-Civil Works", "UNALLOCATED-E&M Works");
        }

        @Test
        void addsNothingWhenTheScheduleAlreadyAddsUp() {
            List<BoqLine> items = List.of(priced("1.1", "1000000", BoqLine.CIVIL));

            assertThat(BoqReconciler.reconcile(items, new BigDecimal("1000000"), null))
                    .isEqualTo(items);
        }

        @Test
        void neverInventsWorkWhenNoTotalWasStated() {
            List<BoqLine> items = List.of(priced("1.1", "900000", BoqLine.CIVIL));

            assertThat(BoqReconciler.reconcile(items, null, null)).isEqualTo(items);
        }
    }

    @Nested
    @DisplayName("cleaning values")
    class Cleaning {

        @Test
        void readsRupeesHoweverTheyArePrinted() {
            assertThat(TextCleaning.parseCurrency("Rs. 42,26,546.00")).isEqualByComparingTo("4226546.00");
            assertThat(TextCleaning.parseCurrency("₹1,234")).isEqualByComparingTo("1234");
            assertThat(TextCleaning.parseCurrency("(500)")).isEqualByComparingTo("-500");
        }

        @Test
        void treatsAnAbsentFigureAsAbsentRatherThanZero() {
            assertThat(TextCleaning.parseCurrency("-")).isNull();
            assertThat(TextCleaning.parseCurrency("N/A")).isNull();
            assertThat(TextCleaning.parseCurrency("")).isNull();
            assertThat(TextCleaning.parseCurrency(null)).isNull();
        }

        @Test
        void collapsesTheLineBreaksPdfTextArrivesWith() {
            assertThat(TextCleaning.compact("Tile   work in\nType-2  quarters"))
                    .isEqualTo("Tile work in Type-2 quarters");
            assertThat(TextCleaning.compact("   ")).isNull();
        }

        @Test
        void stripsAnyOfTheGivenCharactersFromBothEnds() {
            assertThat(TextCleaning.strip(" .23/EE/ACD/CPWD/Almora/2026-27 .", " .:-"))
                    .isEqualTo("23/EE/ACD/CPWD/Almora/2026-27");
        }
    }

    @Nested
    @DisplayName("reading a deadline")
    class Deadlines {

        @Test
        void acceptsTheDateAndTimeInEitherOrder() {
            assertThat(DeadlineExtractor.parse("23.07.2026", "03.30 PM"))
                    .isEqualTo(LocalDateTime.of(2026, 7, 23, 15, 30));
            assertThat(DeadlineExtractor.parse("23/07/2026", "15:30 Hrs."))
                    .isEqualTo(LocalDateTime.of(2026, 7, 23, 15, 30));
        }

        @Test
        void toleratesAValueThatWrappedMidLine() {
            assertThat(DeadlineExtractor.parse("23.07.2026", "03.30 \nPM"))
                    .isEqualTo(LocalDateTime.of(2026, 7, 23, 15, 30));
        }

        @Test
        void defaultsToMidnightWhenOnlyADateWasPrinted() {
            assertThat(DeadlineExtractor.parse("01.07.2026", null))
                    .isEqualTo(LocalDateTime.of(2026, 7, 1, 0, 0));
        }

        @Test
        void returnsNothingRatherThanGuessingAtAnUnrecognisedShape() {
            assertThat(DeadlineExtractor.parse("23.07.26", "15:30")).isNull();
            assertThat(DeadlineExtractor.parse(null, "15:30")).isNull();
        }
    }
}
