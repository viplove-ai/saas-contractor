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
        SETTLEMENT("STL"),
        /**
         * Not a document — a material named at the gate, which needs a code the catalogue
         * has not already used. The same counter answers it: unique per organisation, and
         * nobody has to invent a code while a lorry waits.
         */
        MATERIAL("MAT"),
        /**
         * Nor is an expense head named at a site. It needs a code the taxonomy has not
         * already used, and nobody at a site is going to invent one that the office's own
         * naming would not collide with a month later.
         */
        EXPENSE_CATEGORY("EXH"),
        /**
         * Nor is a supplier. His code is derived from what he supplies and what he is called;
         * this is the fallback for the name that yields nothing usable and for the improbable
         * tenth firm of one name — a code nobody would choose, but never a collision.
         */
        VENDOR("VEN"),
        /**
         * Nor is a worker. His number is what goes against his name on the muster roll, and
         * it was being typed by whoever took him on — which means a supervisor at the gate
         * inventing one, and two men on two sites given the same. The counter has always
         * been the answer to that question; nobody was asking it of workers yet.
         */
        WORKER("W");

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
