package in.nirman.modules.tender.parser;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

/**
 * Holds PDFBox's text output to the shape the parser's patterns were written against.
 *
 * <p>The patterns downstream came from a Python implementation reading these same PDFs with
 * pypdf. Its per-page output is checked in under {@code nit/text} as the golden reference.
 * If PDFBox lays the text out differently — joins two table rows, splits a quantity from its
 * unit, reorders columns — every one of those patterns quietly stops matching, and the
 * failure surfaces hundreds of lines later as a BOQ row count that is off by nine.</p>
 *
 * <p>So this test exists to make the difference <i>measurable</i> rather than mysterious. It
 * does not demand byte-identity: the two libraries differ in trailing whitespace and in how
 * they pad empty table cells, and neither matters. It asserts on what the parser actually
 * consumes — how many priced BOQ lines each extractor yields, and how many lines match at
 * all — and prints the first disagreements so a regression names its own cause.</p>
 */
class NitTextExtractorGoldenTest {

    private static final Path PDF_DIR = Path.of("src/test/resources/nit/pdf");
    private static final Path TEXT_DIR = Path.of("src/test/resources/nit/text");

    /** Collapses the differences that are cosmetic, so the comparison is about content. */
    private static final Pattern WHITESPACE = Pattern.compile("[ \\t]+");

    static Stream<String> fixtures() throws IOException {
        if (!Files.isDirectory(PDF_DIR)) {
            return Stream.empty();
        }
        try (var files = Files.list(PDF_DIR)) {
            return files.map(p -> p.getFileName().toString())
                    .filter(n -> n.endsWith(".pdf"))
                    .map(n -> n.substring(0, n.length() - 4))
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
    void pdfBoxReproducesTheReferenceLayout(String fixture) throws IOException {
        Path pdf = PDF_DIR.resolve(fixture + ".pdf");
        Path golden = TEXT_DIR.resolve(fixture + ".pypdf.txt");
        assumeThat(Files.exists(pdf) && Files.exists(golden))
                .as("fixture %s and its golden text are present", fixture).isTrue();

        List<String> actual = NitTextExtractor.pageTexts(Files.readAllBytes(pdf));
        List<String> expected = Stream.of(
                        Files.readString(golden, StandardCharsets.UTF_8).split("\f", -1))
                .map(NitTextExtractor::normalise)
                .toList();

        assertThat(actual).as("page count for %s", fixture).hasSameSizeAs(expected);

        // The measure that matters is not how many lines agree but how many priced rows the
        // schedule reader gets out of each text. Comparing raw lines punishes PDFBox for
        // breaking a row across two lines, which the reader rejoins and does not care about.
        int referenceRows = BoqScheduleParser.parse(expected, null).items().size();
        int actualRows = BoqScheduleParser.parse(actual, null).items().size();

        int matchedLines = 0;
        int totalLines = 0;
        for (int page = 0; page < expected.size(); page++) {
            List<String> want = contentLines(expected.get(page));
            List<String> have = contentLines(actual.get(page));
            totalLines += want.size();
            for (String line : want) {
                if (have.contains(line)) {
                    matchedLines++;
                }
            }
        }
        double lineFidelity = totalLines == 0 ? 1.0 : (double) matchedLines / totalLines;

        System.out.printf("%-30s pages=%3d lines=%5d lineFidelity=%.4f rows: pdfbox=%3d pypdf=%3d%n",
                fixture, actual.size(), totalLines, lineFidelity, actualRows, referenceRows);

        assertThat(actualRows)
                .as("priced rows read from PDFBox text, against %d from the reference text, in %s",
                        referenceRows, fixture)
                .isGreaterThanOrEqualTo((int) (referenceRows * 0.95));

        // Not a demand for identical text — the two libraries differ on where a wrapped
        // description breaks, and the reader absorbs that. Across the current fixtures this
        // sits between 0.90 (dehradun-42, whose schedule is the most heavily columned) and
        // 0.996. The floor is set below that range as a drift alarm: a fall past it means
        // something structural changed, most likely a PDFBox upgrade, and the fixture test
        // is the one to consult next.
        assertThat(lineFidelity)
                .as("share of reference lines PDFBox reproduces exactly, in %s", fixture)
                .isGreaterThanOrEqualTo(0.85);
    }

    private static List<String> contentLines(String page) {
        return Stream.of(page.split("\\R", -1))
                .map(line -> WHITESPACE.matcher(line).replaceAll(" ").trim())
                .filter(line -> !line.isEmpty())
                .toList();
    }
}
