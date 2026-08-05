package in.nirman.common;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Assigns the human-readable document numbers — {@code ADV-2025-0001} and its siblings.
 *
 * <p>These are the numbers people say out loud and write on paper, so they have to be
 * short, sequential and unique per organisation. That makes them the one identifier an
 * offline device <b>cannot</b> generate: two phones would each reach for 0001, and the
 * second sync would break a unique constraint that idempotency cannot rescue, because the
 * primary keys differ and it is not a replay. So the server assigns them on arrival, and
 * the device shows its own provisional reference until then (docs/09, open question 1).</p>
 *
 * <p>Incrementing is one atomic statement rather than read-then-write, so two requests
 * racing for the next number serialise on the counter row instead of both reading the same
 * value.</p>
 */
@Service
public class DocumentNumberService {

    /** The document families that carry a per-org unique number. */
    public enum DocType {
        WORKER_ADVANCE("ADV"),
        EXPENSE("EXP"),
        GOODS_RECEIPT("GRN"),
        MATERIAL_ISSUE("ISS"),
        STOCK_TRANSFER("TRF"),
        STOCK_COUNT("CNT"),
        PAYMENT("PAY"),
        SITE_ADVANCE("SAD"),
        DPR("DPR"),
        SETTLEMENT("STL");

        private final String prefix;

        DocType(String prefix) {
            this.prefix = prefix;
        }

        public String prefix() {
            return prefix;
        }
    }

    private static final String NEXT_VALUE = """
            INSERT INTO document_number_counters (org_id, doc_type, year, next_value)
            VALUES (?, ?, ?, 2)
            ON CONFLICT (org_id, doc_type, year) DO UPDATE
                SET next_value = document_number_counters.next_value + 1,
                    updated_at = now()
            RETURNING next_value - 1
            """;

    private final JdbcTemplate jdbc;

    public DocumentNumberService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Runs in the caller's transaction: a number handed out for a record that then fails to
     * save leaves a gap, and a gap in a document series is a question an auditor will ask.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public String next(UUID orgId, DocType type, LocalDate businessDate) {
        int year = businessDate.getYear();
        Long value = jdbc.queryForObject(NEXT_VALUE, Long.class, orgId, type.name(), year);
        return "%s-%d-%04d".formatted(type.prefix(), year, value);
    }
}
