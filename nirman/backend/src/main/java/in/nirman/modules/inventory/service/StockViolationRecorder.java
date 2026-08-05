package in.nirman.modules.inventory.service;

import in.nirman.security.CurrentUserProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Records a rejected attempt to take more stock out of a store than it holds.
 *
 * <p>Runs in its own transaction ({@code REQUIRES_NEW}) for the same reason the audit
 * service does: the business transaction is about to be rolled back by the exception that
 * follows, and rolling back the evidence of the attempt with it would leave the
 * data-quality dashboard blind to precisely the events it exists to show.</p>
 *
 * <p>Plain JDBC rather than an entity, because {@code stock_violation_log} has a
 * {@code bigserial} key, no version and no update path — there is nothing for JPA to
 * manage, and mapping it would only invite somebody to edit a row.</p>
 *
 * <p>A failure to log never fails the caller. The caller is already failing, and turning a
 * clear "you do not have that much cement" into a database error helps nobody.</p>
 */
@Service
public class StockViolationRecorder {

    private static final Logger log = LoggerFactory.getLogger(StockViolationRecorder.class);

    private static final String INSERT = """
            INSERT INTO stock_violation_log
                (org_id, store_id, material_id, attempted_qty_base, available_qty_base,
                 source_type, attempted_by)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """;

    private final JdbcTemplate jdbc;
    private final CurrentUserProvider currentUser;

    public StockViolationRecorder(JdbcTemplate jdbc, CurrentUserProvider currentUser) {
        this.jdbc = jdbc;
        this.currentUser = currentUser;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(UUID orgId, UUID storeId, UUID materialId, BigDecimal attempted,
                       BigDecimal available, String sourceType) {
        try {
            jdbc.update(INSERT, orgId, storeId, materialId, attempted, available, sourceType,
                    currentUser.currentUserIdOrNull());
        } catch (RuntimeException e) {
            log.error("Could not record a negative-stock attempt on store {} material {}",
                    storeId, materialId, e);
        }
    }
}
