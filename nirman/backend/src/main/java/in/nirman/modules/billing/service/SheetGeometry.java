package in.nirman.modules.billing.service;

import java.util.ArrayList;
import java.util.List;

/**
 * The printed measurement sheet's layout, in millimetres, as the single definition shared by
 * the thing that prints it and the thing that reads it back.
 *
 * <p><b>This class is the whole reason a local reader is possible.</b> Recognising handwriting
 * from a photograph is hard; recognising a single digit inside a box whose position you already
 * know is not. Everything else in the pipeline — finding the corner marks, correcting the
 * perspective — exists to get the photograph into these coordinates, after which every box is
 * arithmetic rather than search.</p>
 *
 * <p>So the numbers below are printed <i>and</i> served. The PDF is rendered from them and the
 * browser fetches the same values to know where to look. Two copies of a layout is how a reader
 * silently starts reading the wrong column the first time somebody nudges a margin.</p>
 *
 * <p><b>The corner marks are the origin.</b> Four filled squares at known offsets from the page
 * corners, in a deliberately asymmetric arrangement — the bottom-left mark is twice the width of
 * the others — so a page photographed upside down can be told from one the right way up. A
 * symmetric set would read a rotated sheet as a valid one and silently transpose every row.</p>
 */
public final class SheetGeometry {

    /** A4, and the reader assumes it. */
    public static final double PAGE_WIDTH_MM = 210;
    public static final double PAGE_HEIGHT_MM = 297;

    /** Corner marks: centres, measured from the top-left of the page. */
    public static final double MARK_INSET_MM = 12;
    public static final double MARK_SIZE_MM = 6;
    /** The bottom-left mark is wider, which is what makes the page's orientation readable. */
    public static final double MARK_WIDE_SIZE_MM = 12;

    public static final int ROWS = 12;

    /** The grid's own origin and pitch, from the page's top-left. */
    public static final double GRID_TOP_MM = 96;
    public static final double ROW_HEIGHT_MM = 12;

    private static final double BOX_WIDTH_MM = 4.8;
    private static final double BOX_HEIGHT_MM = 8.0;
    private static final double BOX_GAP_MM = 0.5;
    /** The gap a printed decimal point sits in, wider than the one between digits. */
    private static final double DECIMAL_GAP_MM = 2.0;

    /** The right-hand limit. Nothing may be printed past it, and {@link #assertFits} says so. */
    private static final double RIGHT_MARGIN_MM = 16;

    /**
     * One numeric field on a row: where its boxes start, how many, and where the decimal falls.
     *
     * @param decimals digits after the point. Zero means an integer field with no point printed.
     */
    public record Field(String name, double leftMm, int digits, int decimals) {

        /** Every box's left edge, in order, accounting for the wider decimal gap. */
        public List<Double> boxLefts() {
            List<Double> lefts = new ArrayList<>(digits);
            double x = leftMm;
            int integerDigits = digits - decimals;
            for (int i = 0; i < digits; i++) {
                lefts.add(x);
                x += BOX_WIDTH_MM + BOX_GAP_MM;
                if (decimals > 0 && i == integerDigits - 1) {
                    x += DECIMAL_GAP_MM - BOX_GAP_MM;
                }
            }
            return lefts;
        }

        public double widthMm() {
            List<Double> lefts = boxLefts();
            return lefts.get(lefts.size() - 1) + BOX_WIDTH_MM - leftMm;
        }
    }

    /**
     * The fields on a measurement row, left to right.
     *
     * <p>Sizes come from what a measurement book actually holds. Nos and the multiplier are
     * counts and rarely exceed two digits; a dimension in metres wants two decimals and almost
     * never exceeds two integer digits, and the quantity has to hold the product of three of
     * them.</p>
     */
    public static final List<Field> FIELDS = List.of(
            new Field("nos", 56, 2, 0),
            new Field("mult", 69.5, 2, 0),
            new Field("length", 83, 4, 2),
            new Field("breadth", 108.5, 4, 2),
            new Field("height", 134, 4, 2),
            new Field("qty", 159.5, 6, 2));

    /** The written total at the foot, which is the checksum the whole design turns on. */
    public static final Field TOTAL_FIELD = new Field("total", 159.5, 6, 2);
    public static final double TOTAL_TOP_MM = GRID_TOP_MM + ROWS * ROW_HEIGHT_MM + 3;

    /** Location is handwriting nobody reads automatically — a note to a person. */
    public static final double LOCATION_LEFT_MM = 16;
    public static final double LOCATION_WIDTH_MM = 38;

    public static final double BOX_W_MM = BOX_WIDTH_MM;
    public static final double BOX_H_MM = BOX_HEIGHT_MM;

    static {
        assertFits();
    }

    private SheetGeometry() {
    }

    /**
     * Refuses to load if any box would print off the paper.
     *
     * <p>A field that overflows the page is invisible in code and obvious only on the printed
     * sheet — by which time a bulk run has been ordered. Worse, the reader would still compute
     * a position for it and read whatever the camera happened to see there. Failing at startup
     * is the cheapest possible place to find that out.</p>
     */
    private static void assertFits() {
        double limit = PAGE_WIDTH_MM - RIGHT_MARGIN_MM;
        for (Field field : FIELDS) {
            double right = field.leftMm() + field.widthMm();
            if (right > limit) {
                throw new IllegalStateException(
                        "Measurement sheet field '" + field.name() + "' ends at " + right
                                + "mm, past the " + limit + "mm right margin of an A4 page.");
            }
        }
        double totalRight = TOTAL_FIELD.leftMm() + TOTAL_FIELD.widthMm();
        if (totalRight > limit) {
            throw new IllegalStateException(
                    "Measurement sheet total field ends at " + totalRight + "mm, past "
                            + limit + "mm.");
        }
        double bottom = TOTAL_TOP_MM + BOX_HEIGHT_MM;
        if (bottom > PAGE_HEIGHT_MM - 20) {
            throw new IllegalStateException(
                    "Measurement sheet grid ends at " + bottom + "mm, leaving no room for the "
                            + "signature line.");
        }
    }

    /** One box, in page millimetres — what both the printer and the reader work in. */
    public record Box(int row, String field, int digit, double leftMm, double topMm,
                      double widthMm, double heightMm) {
    }

    /** Every box on the page, in reading order. The reader walks exactly this list. */
    public static List<Box> boxes() {
        List<Box> boxes = new ArrayList<>();
        for (int row = 0; row < ROWS; row++) {
            double top = GRID_TOP_MM + row * ROW_HEIGHT_MM + (ROW_HEIGHT_MM - BOX_HEIGHT_MM) / 2;
            for (Field field : FIELDS) {
                List<Double> lefts = field.boxLefts();
                for (int digit = 0; digit < lefts.size(); digit++) {
                    boxes.add(new Box(row, field.name(), digit, lefts.get(digit), top,
                            BOX_WIDTH_MM, BOX_HEIGHT_MM));
                }
            }
        }
        List<Double> totalLefts = TOTAL_FIELD.boxLefts();
        for (int digit = 0; digit < totalLefts.size(); digit++) {
            boxes.add(new Box(-1, "total", digit, totalLefts.get(digit), TOTAL_TOP_MM,
                    BOX_WIDTH_MM, BOX_HEIGHT_MM));
        }
        return boxes;
    }
}
