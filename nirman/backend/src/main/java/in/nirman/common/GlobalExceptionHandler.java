package in.nirman.common;

import in.nirman.modules.expense.api.dto.ExpenseDtos;
import in.nirman.modules.expense.service.DuplicateExpenseException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.List;

/**
 * The only place that turns an exception into a response body. Controllers never catch.
 *
 * <p>Messages are deliberately non-specific for auth failures so the API does not reveal
 * whether a username exists or whether a record the caller cannot see is present.</p>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final String TYPE_BASE = "https://nirman/errors/";

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex,
                                                     HttpServletRequest request) {
        List<ApiError.FieldError> fields = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> new ApiError.FieldError(fe.getField(), fe.getDefaultMessage(),
                        fe.getRejectedValue()))
                .toList();
        ApiError body = new ApiError(TYPE_BASE + "validation", "Validation failed",
                HttpStatus.BAD_REQUEST.value(),
                fields.size() + " field(s) rejected", request.getRequestURI(),
                correlationId(), Instant.now(), fields);
        return ResponseEntity.badRequest().body(body);
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiError> handleBusiness(BusinessException ex, HttpServletRequest request) {
        log.warn("Business rule rejected request: {} {}", ex.getCode(), ex.getMessage());
        return ResponseEntity.status(ex.getStatus()).body(
                ApiError.of(TYPE_BASE + ex.getCode(), "Request rejected", ex.getStatus().value(),
                        ex.getMessage(), request.getRequestURI(), correlationId()));
    }

    /**
     * The one error that carries a payload beyond the RFC 7807 fields. A duplicate warning
     * is only useful with the candidates attached — refusing without saying what the new
     * expense collided with sends somebody hunting through a month of paper for a row the
     * server already had in hand.
     */
    @ExceptionHandler(DuplicateExpenseException.class)
    public ResponseEntity<DuplicateExpenseError> handleDuplicateExpense(
            DuplicateExpenseException ex, HttpServletRequest request) {
        ApiError base = ApiError.of(TYPE_BASE + "expense.duplicate", "Possible duplicate",
                ex.getStatus().value(), ex.getMessage(), request.getRequestURI(), correlationId());
        return ResponseEntity.status(ex.getStatus())
                .body(new DuplicateExpenseError(base, ex.getCandidates()));
    }

    /** {@link ApiError} with the candidate list appended. */
    public record DuplicateExpenseError(
            String type, String title, int status, String detail, String instance,
            String correlationId, Instant timestamp,
            List<ExpenseDtos.DuplicateCandidate> candidates) {

        DuplicateExpenseError(ApiError base, List<ExpenseDtos.DuplicateCandidate> candidates) {
            this(base.type(), base.title(), base.status(), base.detail(), base.instance(),
                    base.correlationId(), base.timestamp(), candidates);
        }
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ApiError> handleStaleUpdate(OptimisticLockingFailureException ex,
                                                      HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(
                ApiError.of(TYPE_BASE + "stale-record", "Record changed",
                        HttpStatus.CONFLICT.value(),
                        "Someone else changed this record. Reload it and apply your change again.",
                        request.getRequestURI(), correlationId()));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiError> handleIntegrity(DataIntegrityViolationException ex,
                                                    HttpServletRequest request) {
        log.warn("Constraint violated", ex);
        return ResponseEntity.status(HttpStatus.CONFLICT).body(
                ApiError.of(TYPE_BASE + "constraint", "Conflicting data",
                        HttpStatus.CONFLICT.value(),
                        "This record conflicts with one that already exists.",
                        request.getRequestURI(), correlationId()));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> handleAccessDenied(AccessDeniedException ex,
                                                       HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(
                ApiError.of(TYPE_BASE + "access-denied", "Not allowed",
                        HttpStatus.FORBIDDEN.value(),
                        "Your role does not permit this action, or the site is not assigned to you.",
                        request.getRequestURI(), correlationId()));
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiError> handleAuth(AuthenticationException ex,
                                               HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(
                ApiError.of(TYPE_BASE + "unauthenticated", "Sign in required",
                        HttpStatus.UNAUTHORIZED.value(),
                        "Sign in again to continue.", request.getRequestURI(), correlationId()));
    }

    /**
     * An upload past the configured multipart limit is thrown by the servlet container before
     * any controller runs, so without this it surfaces as a bare 500 and the user is told
     * nothing useful about a file they can see is large.
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiError> handleUploadTooLarge(MaxUploadSizeExceededException ex,
                                                         HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(
                ApiError.of(TYPE_BASE + "upload.too-large", "File too large",
                        HttpStatus.PAYLOAD_TOO_LARGE.value(),
                        "The file is larger than this server accepts. Try a smaller file.",
                        request.getRequestURI(), correlationId()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception on {}", request.getRequestURI(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                ApiError.of(TYPE_BASE + "internal", "Something went wrong",
                        HttpStatus.INTERNAL_SERVER_ERROR.value(),
                        "The request could not be completed. Quote the correlation id when reporting this.",
                        request.getRequestURI(), correlationId()));
    }

    private String correlationId() {
        return MDC.get(CorrelationIdFilter.MDC_KEY);
    }
}
