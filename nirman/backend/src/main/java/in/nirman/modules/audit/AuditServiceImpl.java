package in.nirman.modules.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.nirman.common.CorrelationIdFilter;
import in.nirman.security.AuthenticatedUser;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.sql.Types;
import java.util.Map;
import java.util.UUID;

/**
 * Plain JDBC into {@code audit_logs}. No JPA entity on purpose: the table is append-only,
 * bigserial-keyed and jsonb-typed, and nothing must ever load or mutate rows through the
 * ORM. REQUIRES_NEW keeps a rolled-back business transaction from erasing the evidence of
 * the attempt — and keeps a failed audit insert from rolling back the business write.
 */
@Service
public class AuditServiceImpl implements AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditServiceImpl.class);

    private static final String INSERT = """
            INSERT INTO audit_logs (org_id, user_id, username, entity_type, entity_id, action,
                                    old_values, new_values, reason, ip_address, device_info,
                                    correlation_id)
            VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?::inet, ?, ?)
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public AuditServiceImpl(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String entityType, UUID entityId, String action,
                       Map<String, Object> oldValues, Map<String, Object> newValues, String reason) {
        AuthenticatedUser user = currentUserOrNull();
        insert(user == null ? null : user.orgId(),
                user == null ? null : user.userId(),
                user == null ? null : user.username(),
                entityType, entityId, action, oldValues, newValues, reason);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordUnauthenticated(UUID orgId, UUID userId, String username,
                                      String entityType, UUID entityId, String action, String reason) {
        insert(orgId, userId, username, entityType, entityId, action, null, null, reason);
    }

    private void insert(UUID orgId, UUID userId, String username, String entityType,
                        UUID entityId, String action, Map<String, Object> oldValues,
                        Map<String, Object> newValues, String reason) {
        try {
            jdbc.update(con -> {
                var ps = con.prepareStatement(INSERT);
                ps.setObject(1, orgId);
                ps.setObject(2, userId);
                ps.setString(3, username);
                ps.setString(4, entityType);
                ps.setObject(5, entityId);
                ps.setString(6, action);
                ps.setString(7, toJson(oldValues));
                ps.setString(8, toJson(newValues));
                ps.setString(9, reason);
                String ip = clientIp();
                if (ip == null) {
                    ps.setNull(10, Types.OTHER);
                } else {
                    ps.setString(10, ip);
                }
                ps.setString(11, deviceInfo());
                ps.setString(12, MDC.get(CorrelationIdFilter.MDC_KEY));
                return ps;
            });
        } catch (RuntimeException e) {
            log.error("AUDIT WRITE FAILED action={} entityType={} entityId={}",
                    action, entityType, entityId, e);
        }
    }

    private String toJson(Map<String, Object> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(values);
        } catch (JsonProcessingException e) {
            log.error("Audit payload not serialisable", e);
            return null;
        }
    }

    private AuthenticatedUser currentUserOrNull() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof AuthenticatedUser user) {
            return user;
        }
        return null;
    }

    private String clientIp() {
        HttpServletRequest request = currentRequestOrNull();
        if (request == null) {
            return null;
        }
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    private String deviceInfo() {
        HttpServletRequest request = currentRequestOrNull();
        if (request == null) {
            return null;
        }
        String userAgent = request.getHeader("User-Agent");
        return userAgent != null && userAgent.length() > 300 ? userAgent.substring(0, 300) : userAgent;
    }

    private HttpServletRequest currentRequestOrNull() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            return attrs.getRequest();
        }
        return null;
    }
}
