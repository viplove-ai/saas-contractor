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

    // ---------------------------------------------------------------- Schedule F
    /*
     * These have no Python counterpart. They were written against the ten notices in
     * `docs/NIT documents/`, and each shape below is one a real notice actually uses — the
     * corpus disagrees with itself far more than the summary page does, because Schedule F is
     * retyped per division rather than generated.
     */

    /**
     * Where the milestone table starts. Two headings, because four of the ten notices announce
     * the table one way and the rest the other; {@code dehradun-01} uses neither phrase in
     * capitals and is caught by the second.
     */
    static final Pattern MILESTONE_TABLE = Pattern.compile(
            "TABLE\\s+OF\\s+MILE\\s*STONES?"
                    + "|Mile\\s*stones?\\s+as\\s+per\\s+table\\s+given\\s+below", I);

    /**
     * Where it stops. The notes below the table are numbered "1." and "2." and would otherwise
     * be read as two more milestones.
     */
    static final Pattern MILESTONE_TABLE_END = Pattern.compile(
            "\\bNotes?\\s*:|Authority\\s+to\\s+decide|\\bClause\\s*[-\\s]*[89]\\b", I);

    /**
     * The end of the table's column headings, and so the start of its first row.
     *
     * <p>All four layouts in the corpus close their heading row with the withholding column,
     * whose title always ends on the word milestone — "Amount to be withheld in case of
     * non-achievement of milestone", "Withheld amount for non-achievement of mile stone.",
     * "... of each Mile stone(s)". Without cutting there, the whole heading is read as the
     * description of milestone 1, which then reports itself as a physical milestone naming
     * work that is really a set of column titles.</p>
     */
    static final Pattern MILESTONE_HEADER_END = Pattern.compile(
            "(?:Amount\\s+to\\s+be\\s+withheld|Withheld\\s+amount)[\\s\\S]{0,200}?"
                    + "(?:mile\\s*stones?|milestones?)\\s*(?:\\(\\s*s\\s*\\))?\\s*\\.?", I);

    /**
     * A page break inside the milestone table. Bare page numbers are deliberately not listed:
     * one notice prints each milestone's sequence number alone on its own line, and a rule that
     * dropped lone numbers would delete the row numbering along with the page numbering.
     */
    static final Pattern MILESTONE_FURNITURE = Pattern.compile(
            "^(?:Corrections?\\b|Insertions?\\b|Omissions?\\b|Overwriting\\b|Page\\s*\\d+\\b"
                    + "|A\\s*E\\s*\\(\\s*P\\s*\\)|E\\s*E\\s*\\(\\s*P\\s*\\)).*$", I);

    /**
     * One row, matched against the table region with its line breaks collapsed.
     *
     * <p>Flattening first is what lets one pattern read all four layouts: a row may occupy one
     * line ({@code 1 15% of Tendered Amount 01 Month 0.5%}) or twenty, with the description
     * wrapped across a column and the time and withholding trailing it. Every layout in the
     * corpus ends the row the same way — a duration, then a percentage — so the row is
     * recognised by its tail rather than by its shape.</p>
     *
     * <p>The description is lazy and capped: unbounded, a failed match on one row swallows the
     * rest of the table into the row before it.</p>
     */
    static final Pattern MILESTONE_ROW = Pattern.compile(
            "(\\d{1,2})\\s*[.)]?\\s+(.{5,1600}?)\\s+(\\d{1,3})\\s*(Days?|Months?)\\b"
                    + "\\s+(\\d{1,3}(?:\\.\\d+)?)\\s*%", IS);

    /**
     * A percentage of the contract, inside a milestone description. Anchored on the word
     * {@code tender} within the same phrase, which is what tells "10% of tendered Value" apart
     * from the "100% RRM/Retaining Wall" and "25% other development works" printed beside it —
     * only one of the three is the milestone's financial test.
     */
    static final Pattern MILESTONE_FINANCIAL = Pattern.compile(
            "(\\d{1,3}(?:\\.\\d+)?)\\s*%\\s*(?:of\\s+)?(?:the\\s+)?(?:accepted\\s+)?tender", I);

    /**
     * The last milestone of one notice reads "100% Physically completion of Work done" and
     * never says "tender", so the pattern above correctly declines it — and a final milestone
     * with no percentage leaves the cumulative curve short of 100 at the completion date.
     * Anchored on the word complete so it cannot pick up the "100% RRM/Retaining Wall" and
     * "25% other development works" that sit inside a physical description.
     */
    static final Pattern MILESTONE_COMPLETION_PERCENT = Pattern.compile(
            "(\\d{1,3}(?:\\.\\d+)?)\\s*%\\s*(?:physical(?:ly)?\\s+)?complet(?:ion|ed|e)\\b", I);

    /**
     * The financial phrasing, removed from a description to see whether anything is left. What
     * remains is the physical milestone — the activities the department expects finished — and
     * it is the most valuable thing in the document, because it is the department's own phasing
     * of the work in the vocabulary {@link BoqClassifier} already sorts BOQ lines into.
     */
    static final Pattern MILESTONE_FINANCIAL_CLAUSE = Pattern.compile(
            "(?:Financially\\s+)?(?:Gross\\s+value\\s+of\\s+work\\s+done\\s*:?\\s*)?"
                    + "\\d{1,3}(?:\\.\\d+)?\\s*%\\s*(?:of\\s+)?(?:the\\s+)?(?:accepted\\s+)?"
                    + "tender(?:ed)?\\s+(?:amount|value)(?:\\s+of\\s+work)?", I);

    /** Filler left behind once the financial clause is gone; not evidence of physical scope. */
    static final Pattern MILESTONE_RESIDUE_NOISE = Pattern.compile(
            "\\b(?:or|and|of\\s+work|completed\\s+in\\s+all\\s+respects?|"
                    + "Work\\s+done\\s+amounting\\s+to)\\b|[.,:;&]", I);

    // ---------------------------------------------------------------- Clause 7 and 7A
    /** Clause 7's own words, and the anchor the interim minimums are read after. */
    static final Pattern INTERIM_PAYMENT_ANCHOR =
            Pattern.compile("being\\s+eligible\\s+to\\s+interim\\s+payment", I);

    /** {@code Civil Works Rs. 21 Lakhs} — the label leads. */
    static final Pattern INTERIM_CIVIL_LABELLED = Pattern.compile(
            "Civil\\s+Works?\\s*:?\\s*(?:Rs\\.?|₹)?\\s*([\\d,]+(?:\\.\\d+)?)"
                    + "\\s*(Lakhs?|Lacs?|Crores?)?", I);
    /** {@code Electrical Works Rs 05 Lakhs for E&M works} — the same, one row down. */
    static final Pattern INTERIM_ELECTRICAL_LABELLED = Pattern.compile(
            "(?:Electrical|E\\s*&\\s*M)\\s+Works?\\s*:?\\s*(?:Rs\\.?|₹)?\\s*([\\d,]+(?:\\.\\d+)?)"
                    + "\\s*(Lakhs?|Lacs?|Crores?)?", I);
    /** {@code Rs. 150 lakh (civil)} — the label trails instead. */
    static final Pattern INTERIM_CIVIL_BRACKETED = Pattern.compile(
            "(?:Rs\\.?|₹)?\\s*([\\d,]+(?:\\.\\d+)?)\\s*(Lakhs?|Lacs?|Crores?)?\\s*\\(\\s*civil\\s*\\)", I);
    /** {@code Rs. 35 lakh (electrical)}. */
    static final Pattern INTERIM_ELECTRICAL_BRACKETED = Pattern.compile(
            "(?:Rs\\.?|₹)?\\s*([\\d,]+(?:\\.\\d+)?)\\s*(Lakhs?|Lacs?|Crores?)?"
                    + "\\s*\\(\\s*(?:electrical|e\\s*&\\s*m)\\s*\\)", I);
    /** A non-composite notice states one figure and no label: {@code 3.50 Lacs}. */
    static final Pattern INTERIM_BARE = Pattern.compile(
            "(?:Rs\\.?|₹)?\\s*([\\d,]+(?:\\.\\d+)?)\\s*(Lakhs?|Lacs?|Crores?)", I);

    /**
     * Whether Clause 7A bites — no running account bill until the EPFO, ESIC and BOCW
     * registrations are filed.
     *
     * <p>The negative lookahead is load-bearing and had to be widened once. The clause states
     * itself before it is answered, in two phrasings — "No Running Account Bill shall be paid"
     * and "(No RA bill shall be paid till submission of EPFO …) Yes" — so a bare
     * {@code (Yes|No)} reads the clause's own first word as the answer and reports Clause 7A as
     * <i>not</i> applying on documents where it plainly does. Getting this backwards would tell
     * a contractor his first bill is unconditional when in fact nothing is payable until three
     * registrations are filed.</p>
     */
    static final Pattern CLAUSE_7A = Pattern.compile(
            "Clause\\s*[-\\s]*7\\s*A\\s*:?[\\s\\S]{0,400}?"
                    + "\\b(Yes|No)\\b(?!\\s+(?:RA\\b|Running\\s+Account))", I);

    // ---------------------------------------------------------------- guarantees
    /**
     * The threshold below which a second guarantee is due: "If the quoted bid amount is lesser
     * than 80% of the estimated cost put to tender ...". Written against the one notice in the
     * corpus that carries the clause; the other nine are silent, and silence is a reading.
     */
    static final Pattern APG_THRESHOLD = Pattern.compile(
            "quoted\\s+bid\\s+amount\\s+is\\s+lesser\\s+than\\s*(\\d{1,3}(?:\\.\\d+)?)\\s*%"
                    + "[\\s\\S]{0,120}?estimated\\s+cost\\s+put\\s+to\\s+tender", I);

    /**
     * The CPWD form's arithmetic: the guarantee is the <i>difference</i> between the threshold
     * share of the estimate and what was bid, not a percentage of anything. Bid 30% below a
     * ₹1 crore estimate and the additional guarantee is ₹10 lakh.
     */
    static final Pattern APG_DIFFERENCE = Pattern.compile(
            "difference\\s+between\\s+the\\s*(\\d{1,3})\\s*%\\s*amount\\s+of\\s+ECPT"
                    + "\\s+and\\s+quoted\\s+amount", I);

    /** Other departments levy a flat share of the tendered amount instead. */
    static final Pattern APG_PERCENT_OF_BID = Pattern.compile(
            "[Aa]dditional\\s+[Pp]erformance\\s+(?:[Ss]ecurity|[Gg]uarantee)\\s*(?:@|of)?\\s*"
                    + "(\\d{1,3}(?:\\.\\d+)?)\\s*%\\s*of\\s+the\\s+tendered\\s+amount", I);

    // ---------------------------------------------------------------- time allowed
    /** Schedule F's own statement of the time allowed, which is where the planner reads it. */
    static final Pattern TIME_ALLOWED = Pattern.compile(
            "Time\\s+allowed\\s+for\\s+(?:execution|completion)(?:\\s+of\\s+work)?\\s*:?\\s*"
                    + "(\\d{1,3})\\s*(?:\\([^)]*\\)\\s*)?(Days?|Months?|Years?)", I);

    /**
     * The gap between the acceptance letter and the date work is reckoned to start. Ten days in
     * all ten notices, which is consistent enough to default and far too load-bearing to
     * hardcode — the entire plan calendar hangs off it.
     */
    static final Pattern START_RECKONING_DAYS = Pattern.compile(
            "Numbers?\\s+of\\s+days\\s+from\\s+(?:the\\s+)?date\\s+of\\s+issue\\s+of\\s+letter"
                    + "\\s+of\\s+acceptance\\s+for\\s+reckoning\\s+date\\s+of\\s+start\\s*:?\\s*"
                    + "(\\d{1,3})", I);

    /** Reads the duration out of the free-text completion period the summary page prints. */
    static final Pattern DURATION_IN_TEXT =
            Pattern.compile("(\\d{1,3})\\s*(?:\\([^)]*\\)\\s*)?(Days?|Months?|Years?)", I);

    // ---------------------------------------------------------------- warnings
    /** Words that betray an eligibility clause pasted from an unrelated tender. (py:360) */
    static final Pattern WORD = Pattern.compile("[a-z]{4,}");
}
