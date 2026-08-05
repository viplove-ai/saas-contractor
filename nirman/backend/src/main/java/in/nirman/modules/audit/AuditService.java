package in.nirman.modules.audit;

import java.util.Map;
import java.util.UUID;

/**
 * Append-only trail of who did what. Two entry points:
 *
 * <ul>
 *   <li>{@link #record} — for actions performed by the authenticated caller; org, user,
 *       IP and correlation id are taken from the current request context.</li>
 *   <li>{@link #recordUnauthenticated} — for the auth flows where there is no principal
 *       yet (failed logins, refresh-token reuse), where the actor must be stated
 *       explicitly.</li>
 * </ul>
 *
 * <p>Writes never fail the business transaction: an audit insert error is logged and
 * swallowed, because refusing a payment for want of a log row is the wrong trade — but the
 * error log line is monitored, because a silent audit gap is the second-wrong trade.</p>
 */
public interface AuditService {

    void record(String entityType, UUID entityId, String action,
                Map<String, Object> oldValues, Map<String, Object> newValues, String reason);

    void recordUnauthenticated(UUID orgId, UUID userId, String username,
                               String entityType, UUID entityId, String action, String reason);
}
