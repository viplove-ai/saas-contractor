package in.nirman.modules.tender.parser;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Every regular expression the NIT parser uses, in one place.
 *
 * <p>These are a port of {@code tender-intelligence/src/nit_parser.py}, tuned over a corpus of
 * CPWD tender documents. They are the specification, not an implementation detail: a pattern
 * that looks needlessly specific is usually specific because a general version matched the
 * wrong thing on a real notice. The comments record which, so the next person can tell a
 * deliberate narrowing from an accident.</p>
 *
 * <p>Line numbers in the Javadoc refer to the Python source they came from.</p>
 */
final class NitPatterns {

    private NitPatterns() {
    }

    private static final int I = Pattern.CASE_INSENSITIVE;
    private static final int IS = Pattern.CASE_INSENSITIVE | Pattern.DOTALL;
    private static final int IM = Pattern.CASE_INSENSITIVE | Pattern.MULTILINE;

    // ---------------------------------------------------------------- shared fragments
    /** A rupee figure with optional symbol and Indian digit grouping. (py:14) */
    static final String MONEY = "(?:Rs\\.?|₹)?\\s*([\\d,]+(?:\\.\\d{1,2})?)";
    /** dd.mm.yyyy, dd/mm/yyyy or dd-mm-yyyy. (py:15) */
    static final String DATE = "(\\d{1,2}[./-]\\d{1,2}[./-]\\d{2,4})";
    /** The lookbehind stops a date's year being read as an hour. (py:16) */
    static final String TIME = "(?<!\\d)(\\d{1,2}[.:]\\d{2}\\s*(?:(?:Hrs?\\.?)|(?:AM|PM))?)";

    // ---------------------------------------------------------------- summary page
    /** The page carrying all three of these is the notice's own summary table. (py:290) */
    static final Pattern NIT_NO_LABEL = Pattern.compile("\\bNIT\\s+No\\b", I);
    static final Pattern ESTIMATED_COST_LABEL = Pattern.compile("Estimated\\s+cost", I);
    static final Pattern EARNEST_MONEY_LABEL = Pattern.compile("Earnest\\s+Money", I);

    // ---------------------------------------------------------------- identification
    /** (py:296) */
    static final Pattern NIT_NO = Pattern.compile(
            "\\bNIT\\s*(?:No\\.?|Number)\\s*[:.-]?\\s*(.+?)(?=\\s+Name\\s+of\\s+Work|\\n|$)", I);
    /** Spaces around the slashes are a text-extraction artefact, not part of the number. */
    static final Pattern NIT_NO_SLASH = Pattern.compile("\\s*/\\s*");
    /** (py:302) */
    static final Pattern WORK_NAME = Pattern.compile(
            "Name\\s+of\\s+Work\\s*:?\\s*(.+?)(?=\\s+(?:Location|Estimated\\s+Cost|Earnest\\s+money"
                    + "|Stipulated\\s+Period|Last\\s+date|$))", IS);

    // ---------------------------------------------------------------- commercial figures
    /** (py:307) */
    static final Pattern ESTIMATED_COST = Pattern.compile(
            "Estimated\\s+Cost(?:\\s+put\\s+to\\s+(?:bid|tender)|\\s+of\\s+work)?"
                    + "(?:\\s+Total\\s+Estimated\\s+cost)?\\s*:?\\s*" + MONEY, I);
    /** A composite notice prints its parts, then the total in brackets. (py:308) */
    static final Pattern TOTAL_AFTER_AMOUNT = Pattern.compile(
            MONEY + "\\s*/?\\s*-?\\s*\\(\\s*Total\\s*\\)", I);
    /** (py:311) */
    static final Pattern CIVIL_COST_LABELLED = Pattern.compile("Civil\\s+Work\\s*:\\s*" + MONEY, I);
    /** (py:313) */
    static final Pattern CIVIL_COST_BRACKETED = Pattern.compile(
            MONEY + "\\s*/?\\s*-?\\s*\\(\\s*Civil\\s+Works?\\s*\\)", I);
    /** (py:314) */
    static final Pattern ELECTRICAL_COST_LABELLED =
            Pattern.compile("Electrical\\s+Work\\s*:\\s*" + MONEY, I);
    /** (py:316) */
    static final Pattern ELECTRICAL_COST_BRACKETED = Pattern.compile(
            MONEY + "\\s*/?\\s*-?\\s*\\(\\s*(?:Electrical|E\\s*&\\s*M)\\s+Works?\\s*\\)", I);
    /** (py:317) */
    static final Pattern EARNEST_MONEY = Pattern.compile(
            "(?:Earnest\\s+Money(?:\\s+Deposit)?|Amount\\s+of\\s+Earnest\\s+Money\\s+Deposit)"
                    + "\\s*:?\\s*" + MONEY, I);
    /** (py:347) */
    static final Pattern PERFORMANCE_GUARANTEE = Pattern.compile(
            "Performance\\s+Guarantee(?:\\s*\\([a-z]\\))?(?:\\s+of)?\\s+(\\d+(?:\\.\\d+)?)\\s*%", I);
    /** (py:348) */
    static final Pattern SECURITY_DEPOSIT =
            Pattern.compile("Security\\s+Deposit\\s+(\\d+(?:\\.\\d+)?)\\s*%", I);

    // ---------------------------------------------------------------- schedule and parties
    /** (py:318) */
    static final Pattern COMPLETION_PERIOD = Pattern.compile(
            "(?:Stipulated\\s+Period\\s+of\\s+Completion(?:\\s+of\\s+work)?|Period\\s+of\\s+completion"
                    + "|Time\\s+allowed(?:\\s+for\\s+completion\\s+of\\s+work)?)"
                    + "\\s*:?\\s*([0-9]+\\s*(?:\\([^)]*\\)\\s*)?(?:days?|months?|years?))", I);
    /** (py:322) */
    static final Pattern DIVISION_HEADING =
            Pattern.compile("(?:OFFICE\\s+OF\\s+)?EXECUTIVE\\s+ENGINEER\\s*[-–]\\s*([^\\n,]+)", I);
    /**
     * Prefer the inviting authority. Generic matches also see signature labels such as
     * "Executive Engineer (C)", where C denotes Civil, not a division. (py:326)
     */
    static final Pattern DIVISION_BRACKETED =
            Pattern.compile("The\\s+EXECUTIVE\\s+ENGINEER\\s*\\(([^)]+)\\)", I);
    /** (py:328) */
    static final Pattern DIVISION_DASHED =
            Pattern.compile("The\\s+EXECUTIVE\\s+ENGINEER\\s*[-–]\\s*([^,\\n]+)", I);
    /** (py:329) */
    static final Pattern LOCATION =
            Pattern.compile("\\bLocation\\s*:?\\s*(.+?)(?=\\s+Estimated\\s+cost)", IS);
    /** A capture that swallowed the next label is a miss, not a location. (py:332) */
    static final Pattern LOCATION_BLEED = Pattern.compile(
            "Estimated\\s+cost|Earnest\\s+Money|Period\\s+of\\s+Completion", I);
    /** Last resort: the "at <place>." tail of the work name. (py:335) */
    static final Pattern LOCATION_IN_WORK_NAME = Pattern.compile("\\bat\\s+(.+?)(?=\\.\\s*$)", I);
    /** (py:336) */
    static final Pattern PERCENTAGE_RATE_BID = Pattern.compile(
            "Percentage\\s+rate\\s+(?:composite\\s+)?bids?|PERCENTAGE\\s+RATE\\s+TENDER", I);

    // ---------------------------------------------------------------- eligibility
    /** (py:337) */
    static final Pattern ELIGIBILITY_ENLISTED = Pattern.compile(
            "(Only\\s+the\\s+enlisted\\s+contractors\\s+of\\s+Class.+?)(?=\\n\\s*2\\.)", IS);
    /** (py:339) */
    static final Pattern ELIGIBILITY_CPWD = Pattern.compile(
            "bids\\s+from\\s+(.+?eligible\\s+contractors\\s+of\\s+CPWD.+?)"
                    + "(?=\\s*for\\s+the\\s+following\\s+work)", IS);
    /** (py:344) */
    static final Pattern SIMILAR_WORK = Pattern.compile(
            "(The\\s+Contractor\\s+should\\s+have\\s+satisfactorily\\s+completed.+?)"
                    + "(?=\\n\\s*1\\.2\\.2|\\n\\s*Online\\s+bid|$)", IS);
    /** Specialised tenders define the term inline instead. (py:248) */
    static final Pattern SIMILAR_WORK_DEFINITION =
            Pattern.compile("Similar\\s+work\\s+means\\s*[\"]\\s*(.*?)[\"]", I);

    // ---------------------------------------------------------------- rate schedules
    /** (py:215) */
    static final Pattern RATE_SCHEDULE_ANCHOR =
            Pattern.compile("Standard\\s+Schedule\\s+of\\s+Rates", I);
    /** Digits of the year arrive space-separated from some encoders, hence {@code 20\s*\d\s*\d}. */
    static String disciplineSchedule(String discipline) {
        return discipline + "\\s+Works?[\\s\\S]*?DSR\\s*[- ]?\\s*(20\\s*\\d\\s*\\d)"
                + "[\\s\\S]*?Cost\\s+Index\\s*([\\d.]+)\\s*%";
    }
    /** (py:230) */
    static final Pattern DSR_YEAR =
            Pattern.compile("Delhi\\s+Schedule\\s+of\\s+Rates\\s+(20\\s*\\d\\s*\\d)", I);
    /** (py:234) */
    static final Pattern COST_INDEX = Pattern.compile("Cost\\s+Index\\s*([\\d.]+)\\s*%", I);
    /** (py:236) */
    static final Pattern COST_INDEX_REVERSED =
            Pattern.compile("([\\d.]+)\\s*%\\s*Cost\\s+Index", I);

    // ---------------------------------------------------------------- deadlines
    /** (py:82) Tried in order; the first that matches wins. */
    static final List<Pattern> BID_OPENING = List.of(
            Pattern.compile("date(?:\\s+and\\s+time)?\\s+of\\s+(?:online\\s+)?opening\\s+of\\s+bid"
                    + "[\\s\\S]{0,120}?" + DATE + "\\s*(?:up\\s*to|upto|at)?\\s*" + TIME, I),
            Pattern.compile("(?:time\\s*&\\s*date|date(?:\\s+and\\s+time)?)\\s+of\\s+opening"
                    + "[^\\n:]*(?::|at)?\\s*" + TIME + "\\s*(?:on\\s*)?" + DATE, I),
            Pattern.compile("time\\s+and\\s+date\\s+of\\s+opening\\s+of\\s+bid\\s*" + TIME
                    + "\\s*(?:on\\s*)?" + DATE, I),
            Pattern.compile("(?:bid\\s+shall\\s+be\\s+opened|bid\\s+opening)[^.\\n]*?at\\s*" + TIME
                    + "\\s+on\\s+" + DATE, I),
            Pattern.compile("date\\s+of\\s+opening\\s*:?\\s*" + DATE, I));

    /** (py:90) */
    static final List<Pattern> SUBMISSION_CLOSING = List.of(
            Pattern.compile("last\\s+date(?:\\s*&\\s*time)?\\s+of\\s+(?:online\\s+)?submission"
                    + "\\s+of\\s+bid[\\s\\S]{0,180}?" + DATE + "\\s*(?:up\\s*to|upto|at)\\s*" + TIME, I),
            Pattern.compile("last\\s+date\\s+and\\s+time\\s+of\\s+submission\\s+of\\s+bid\\s*:\\s*"
                    + TIME + "\\s+on\\s+" + DATE, I),
            Pattern.compile("last\\s+date\\s+of\\s+online\\s+submission\\s+of\\s+bid[\\s\\S]+?"
                    + "up\\s*to\\s+" + TIME + "\\s+on\\s+" + DATE, I),
            Pattern.compile("(?:submitted|submission)[^.\\n]*?(?:up\\s*to|upto)\\s+(?:the\\s+)?"
                    + TIME + "\\s+on\\s+" + DATE, I),
            Pattern.compile("last\\s+date\\s+of\\s+submission\\s+of\\s+bid\\s*[:-]\\s*" + DATE, I));

    /** Decides which captured group of a two-group deadline match is the date. (py:103) */
    static final Pattern BARE_DATE = Pattern.compile("\\d{1,2}[./-]\\d{1,2}[./-]\\d{2,4}");
    /** (py:70) */
    static final Pattern TRAILING_HRS = Pattern.compile("(?i)\\s*HRS?\\.?$");

    // ---------------------------------------------------------------- schedule of quantities
    /** A page that both announces the schedule and has a quantity column starts the BOQ. (py:114) */
    static final Pattern SCHEDULE_HEADING =
            Pattern.compile("Schedule\\s+of\\s+(?:Work|Quantit(?:y|ies))\\s*(?:\\([^)]*\\))?", I);
    /** (py:115) */
    static final Pattern QUANTITY_COLUMN = Pattern.compile("\\b(?:Qty|Quantity)\\b", I);
    /** (py:147) */
    static final Pattern ELECTRICAL_SCHEDULE = Pattern.compile(
            "SCHEDULE\\s+OF\\s+(?:QUANTIT(?:Y|IES)|WORK)\\s*(?:OF\\s+)?\\(?\\s*"
                    + "(?:E\\s*&\\s*M|ELECTRICAL)\\s+WORKS?", I);
    /** (py:152) */
    static final Pattern CIVIL_SCHEDULE = Pattern.compile(
            "SCHEDULE\\s+OF\\s+(?:QUANTIT(?:Y|IES)|WORK)\\s*(?:OF\\s+)?\\(?\\s*CIVIL\\s+WORKS?", I);
    /** Running headers and correction footers are not work items. (py:159) */
    static final Pattern PAGE_FURNITURE =
            Pattern.compile("^(?:Corrections|Insertions|Omissions|P\\s*a\\s*g\\s*e)\\b", I);

    /** Item numbers may be hierarchical (3.1.2), integers, or lettered sub-items. (py:123) */
    static final Pattern ITEM_START = Pattern.compile("^\\s*(\\d+(?:\\.\\d+)*|[a-z]\\))\\s+(.+?)\\s*$", I);

    /**
     * A complete priced row: description, quantity, unit, rate, an optional second rate
     * column some formats carry, then the amount. (py:124)
     *
     * <p>The unit list is closed on purpose — an open {@code \w+} matched the first word of
     * wrapped description text and invented rows. The cost of that choice is that a unit
     * outside this vocabulary drops its row silently, which is why the preview reports the
     * extracted total against the tender's stated total.</p>
     */
    static final Pattern PRICED_END = Pattern.compile(
            "^(.+?)\\s+(\\d[\\d,]*(?:\\.\\d+)?)\\s+"
                    + "(cum|sqm|sq\\.?\\s*m|kg|mtr|metre|meter|each|lot|job|point|pair|set|nos?\\.?"
                    + "|number|hour|kWp|per\\s+bag(?:\\s+of\\s+50\\s+kg\\s+cement\\s+used)?)\\.?\\s+"
                    + "(\\d[\\d,]*(?:\\.\\d+)?)\\s+(?:(\\d[\\d,]*(?:\\.\\d+)?)\\s+)?"
                    + "(₹?\\s*\\d[\\d,]*(?:\\.\\d+)?)\\s*$", I);

    /** A wrapped {@code 12.00 cum} line looks exactly like item number 12.00. (py:164) */
    static final Pattern QUANTITY_TOKEN = Pattern.compile("\\d+\\.00");
    /** (py:165) */
    static final Pattern QUANTITY_TOKEN_UNIT = Pattern.compile(
            "(?:cum|sqm|kg|mtr|metre|meter|each|lot|job|point|pair|set|nos?\\.?|hour|kWp)\\b", I);
    /** And "1.5 m dia" mid-description looks exactly like item number 1.5. (py:170) */
    static final Pattern MEASUREMENT_TOKEN = Pattern.compile("\\d+\\.\\d+");
    /** (py:171) */
    static final Pattern MEASUREMENT_FRAGMENT =
            Pattern.compile("(?:m|cm|mm)\\s+(?:in|dia|wide|deep|long)\\b", I);

    /** (py:194) Tried in order, all matches of each collected. */
    static final List<Pattern> BOQ_TOTALS = List.of(
            Pattern.compile("^\\s*TOTAL\\s*(?:₹\\s*)?([\\d,]+(?:\\.\\d{1,2})?)\\s*$", IM),
            Pattern.compile("^\\s*GRAND\\s+TOTAL\\s+(?:Rs\\.?|₹)\\s*[:=]?\\s*"
                    + "([\\d,]+(?:\\.\\d{1,2})?)\\s*/?\\s*-?\\s*$", IM),
            Pattern.compile("^\\s*GRAND\\s+TOTAL\\s*[:=]?\\s*([\\d,]+(?:\\.\\d{1,2})?)\\s*$", IM),
            Pattern.compile("^\\s*GRAND\\s+TOTAL\\s+of\\s+[^\\n]*?\\s+"
                    + "([\\d,]+(?:\\.\\d{1,2})?)\\s*$", IM),
            Pattern.compile("^\\s*GRAND\\s+TOTAL\\s+of\\s+[^\\d\\n]*\\n\\s*"
                    + "([\\d,]+(?:\\.\\d{1,2})?)\\s*$", IM));

    // ---------------------------------------------------------------- warnings
    /** Words that betray an eligibility clause pasted from an unrelated tender. (py:360) */
    static final Pattern WORD = Pattern.compile("[a-z]{4,}");
}
