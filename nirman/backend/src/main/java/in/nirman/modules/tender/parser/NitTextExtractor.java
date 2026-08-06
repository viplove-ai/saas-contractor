package in.nirman.modules.tender.parser;

import in.nirman.common.BusinessException;
import org.apache.pdfbox.io.MemoryUsageSetting;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.http.HttpStatus;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns a NIT PDF into one string of text per page.
 *
 * <p>Everything downstream is line-oriented regex over this output, so the shape of the text
 * <i>is</i> the contract. Three properties are load-bearing and the settings below exist to
 * preserve them:</p>
 *
 * <ol>
 *   <li>A priced Schedule-of-Quantities row lands on a <b>single</b> space-separated line —
 *       {@code 1.1.1 Size of Tile 600x600 mm 155.00 sqm 2377.15 368458.00}. That is what
 *       {@link NitPatterns#PRICED_END} matches, and a row split across two lines is a row
 *       silently dropped.</li>
 *   <li>Pages stay separate. The work part (Civil vs E&amp;M) is detected per page, so a
 *       flattened blob would assign every line to whichever schedule appeared first.</li>
 *   <li>No glyph reordering or de-duplication, because column order carries the meaning:
 *       description, quantity, unit, rate, amount.</li>
 * </ol>
 *
 * <p>The reference implementation these rules were derived from is pypdf, whose per-page
 * output is checked in under {@code src/test/resources/nit/text} and diffed against this
 * class by {@code NitTextExtractorGoldenTest}. When a tuning knob here changes, that test is
 * what says whether the change was an improvement.</p>
 */
public final class NitTextExtractor {

    /** Matches the Python parser's ceiling. Bigger files are almost always scanned images. */
    static final int MAX_PAGES = 500;

    /** A guard against a decompression bomb, not against a large tender. */
    static final int MAX_EXTRACTED_CHARS = 20_000_000;

    /** Beyond this PDFBox is buffering to a temp file rather than the heap. */
    private static final long IN_MEMORY_BYTES = 64L * 1024 * 1024;

    private NitTextExtractor() {
    }

    /**
     * @return one entry per page, in document order; entries may be blank
     * @throws BusinessException if the file cannot be read, is encrypted, is too long, or
     *                           carries no selectable text at all
     */
    public static List<String> pageTexts(byte[] content) {
        if (content == null || content.length == 0) {
            throw new BusinessException("nit.empty", "The uploaded PDF is empty.",
                    HttpStatus.BAD_REQUEST);
        }
        List<String> pages;
        try (PDDocument document = load(content)) {
            if (document.getNumberOfPages() > MAX_PAGES) {
                throw new BusinessException("nit.too-many-pages",
                        "The PDF exceeds the %d-page limit.".formatted(MAX_PAGES),
                        HttpStatus.BAD_REQUEST);
            }
            pages = strip(document);
        } catch (BusinessException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw new BusinessException("nit.unreadable",
                    "The file could not be read as a PDF.", HttpStatus.BAD_REQUEST);
        }
        if (pages.stream().allMatch(page -> page.isBlank())) {
            throw new BusinessException("nit.no-text",
                    "No selectable text was found. This PDF may be scanned; "
                            + "text recognition is not available yet.",
                    HttpStatus.UNPROCESSABLE_ENTITY);
        }
        return pages;
    }

    private static PDDocument load(byte[] content) throws IOException {
        PDDocument document;
        try {
            // An owner password with an empty user password is ordinary on government
            // notices: the file is public, only editing is meant to be restricted. That
            // opens with the empty password; a real user password does not, and is the
            // only case we refuse.
            document = PDDocument.load(new ByteArrayInputStream(content), "",
                    MemoryUsageSetting.setupMixed(IN_MEMORY_BYTES));
        } catch (InvalidPasswordException e) {
            throw new BusinessException("nit.encrypted",
                    "Password-protected PDFs are not supported.", HttpStatus.BAD_REQUEST);
        }
        if (document.isEncrypted()) {
            document.setAllSecurityToBeRemoved(true);
        }
        return document;
    }

    private static List<String> strip(PDDocument document) throws IOException {
        PDFTextStripper stripper = new PDFTextStripper();
        // Content-stream order, which is what the reference extractor uses. Sorting by
        // position rebuilds table rows out of column order and breaks PRICED_END.
        stripper.setSortByPosition(false);
        // Article beads would interleave the two columns of a two-column preamble page.
        stripper.setShouldSeparateByBeads(false);
        // PDFBox suppresses overlapping glyphs by default; the reference does not, and a
        // dropped glyph inside a quantity is a wrong quantity rather than a missing one.
        stripper.setSuppressDuplicateOverlappingText(false);
        stripper.setAddMoreFormatting(false);
        stripper.setLineSeparator("\n");
        stripper.setWordSeparator(" ");
        stripper.setParagraphStart("");
        stripper.setParagraphEnd("");
        stripper.setArticleStart("");
        stripper.setArticleEnd("");
        stripper.setPageStart("");
        stripper.setPageEnd("");

        List<String> pages = new ArrayList<>(document.getNumberOfPages());
        int chars = 0;
        for (int page = 1; page <= document.getNumberOfPages(); page++) {
            stripper.setStartPage(page);
            stripper.setEndPage(page);
            String text = reflowItemNumbers(normalise(stripper.getText(document)));
            chars += text.length();
            if (chars > MAX_EXTRACTED_CHARS) {
                throw new BusinessException("nit.too-large",
                        "The PDF contains too much text to process safely.",
                        HttpStatus.BAD_REQUEST);
            }
            pages.add(text);
        }
        return pages;
    }

    /**
     * A hierarchical item number sitting alone on a line, with its description on the next.
     *
     * <p>Components are capped at two digits because that is what a CPWD schedule numbers
     * look like — {@code 7.1}, {@code 18.1.1}, {@code 28.1.1}. Allowing more lets a stray
     * amount such as {@code 8514038.00}, which a table also prints alone on a line, be
     * mistaken for an item number and glued to the following description. Bare integers are
     * excluded for the same reason; see {@link #reflowItemNumbers}.</p>
     */
    private static final java.util.regex.Pattern ORPHAN_ITEM_NUMBER =
            java.util.regex.Pattern.compile("\\d{1,2}(?:\\.\\d{1,2}){1,3}|[a-z]\\)",
                    java.util.regex.Pattern.CASE_INSENSITIVE);

    /**
     * Rejoins an item number that was split away from the description it labels.
     *
     * <p>The schedule is a table, and the item number is its own column. Where the columns sit
     * far enough apart, PDFBox ends the line between them and emits {@code 7.1} alone,
     * followed by {@code Screened through sieve of I.S. designation 20 mm}. The row reader
     * needs both on one line to recognise a new item at all, so a split like that does not
     * merely mislabel the row — it loses the number entirely, and every following line piles
     * into whichever item was open before.</p>
     *
     * <p>Only hierarchical numbers ({@code 7.1}, {@code 18.1.1}) and lettered sub-items
     * ({@code a)}) are rejoined. A bare integer on its own line is ambiguous — it is as
     * likely to be a page number, a quantity, or a stray table cell as an item number — and
     * gluing one to the following line would invent rows rather than recover them.</p>
     */
    static String reflowItemNumbers(String page) {
        String[] lines = page.split("\n", -1);
        StringBuilder out = new StringBuilder(page.length());
        for (int i = 0; i < lines.length; i++) {
            String current = lines[i].strip();
            if (ORPHAN_ITEM_NUMBER.matcher(current).matches()) {
                int next = i + 1;
                while (next < lines.length && lines[next].isBlank()) {
                    next++;
                }
                // Only join when something follows that could be a description; an item
                // number at the very foot of a page belongs to the next page's text.
                if (next < lines.length) {
                    out.append(current).append(' ').append(lines[next].strip()).append('\n');
                    i = next;
                    continue;
                }
            }
            out.append(lines[i]).append('\n');
        }
        out.setLength(Math.max(0, out.length() - 1));
        return out.toString();
    }

    /**
     * Folds away the typographic differences between extractors, so the patterns never have
     * to spell alternatives for characters that mean the same thing.
     *
     * <p>Each substitution is here because it changed a match: a non-breaking space between
     * a quantity and its unit defeats {@code \s}; a soft hyphen inside {@code cem­ent} defeats
     * a keyword rule; curly quotes defeat the specialised similar-work pattern that looks for
     * a quoted definition.</p>
     */
    static String normalise(String raw) {
        return raw
                .replace(' ', ' ')   // non-breaking space
                .replace(' ', ' ')   // figure space
                .replace(' ', ' ')   // narrow no-break space
                .replace("­", "")    // soft hyphen
                .replace('‘', '\'')
                .replace('’', '\'')
                .replace('“', '"')
                .replace('”', '"')
                .replace('–', '-')   // en dash
                .replace('—', '-')   // em dash
                .replace("\r\n", "\n")
                .replace('\r', '\n');
    }
}
