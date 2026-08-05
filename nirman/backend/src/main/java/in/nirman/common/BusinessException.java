package in.nirman.common;

import org.springframework.http.HttpStatus;

/**
 * Thrown when a request is well formed but breaks a business rule: negative stock,
 * a locked period, an edit to an approved record. Maps to HTTP 422.
 */
public class BusinessException extends RuntimeException {

    private final String code;
    private final HttpStatus status;

    public BusinessException(String code, String message) {
        this(code, message, HttpStatus.UNPROCESSABLE_ENTITY);
    }

    public BusinessException(String code, String message, HttpStatus status) {
        super(message);
        this.code = code;
        this.status = status;
    }

    public String getCode() {
        return code;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public static BusinessException notFound(String entity, Object id) {
        return new BusinessException("entity.not-found",
                entity + " " + id + " was not found", HttpStatus.NOT_FOUND);
    }

    public static BusinessException forbidden(String message) {
        return new BusinessException("access.denied", message, HttpStatus.FORBIDDEN);
    }

    public static BusinessException conflict(String code, String message) {
        return new BusinessException(code, message, HttpStatus.CONFLICT);
    }
}
