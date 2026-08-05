package in.nirman.common;

import java.time.Instant;
import java.util.List;

/**
 * Single error shape for every failed request. Modelled on RFC 7807.
 *
 * @param type          stable machine-readable error identifier
 * @param title         short human summary
 * @param status        HTTP status code
 * @param detail        what went wrong, safe to show a user
 * @param instance      request path
 * @param correlationId ties the response to server logs
 * @param timestamp     server time of failure
 * @param errors        per-field validation failures, empty when not a validation error
 */
public record ApiError(
        String type,
        String title,
        int status,
        String detail,
        String instance,
        String correlationId,
        Instant timestamp,
        List<FieldError> errors) {

    public record FieldError(String field, String message, Object rejectedValue) {
    }

    public static ApiError of(String type, String title, int status, String detail,
                              String instance, String correlationId) {
        return new ApiError(type, title, status, detail, instance, correlationId,
                Instant.now(), List.of());
    }
}
