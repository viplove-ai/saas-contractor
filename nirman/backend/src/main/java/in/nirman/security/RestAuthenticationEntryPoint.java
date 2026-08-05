package in.nirman.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import in.nirman.common.ApiError;
import in.nirman.common.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 401 and 403 raised inside the security filter chain never reach
 * {@code GlobalExceptionHandler}, so this writes the same {@link ApiError} shape the rest
 * of the API uses — a client sees one error format everywhere.
 */
@Component
public class RestAuthenticationEntryPoint implements AuthenticationEntryPoint, AccessDeniedHandler {

    private static final String TYPE_BASE = "https://nirman/errors/";

    private final ObjectMapper objectMapper;

    public RestAuthenticationEntryPoint(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        write(response, request, HttpStatus.UNAUTHORIZED, "unauthenticated",
                "Sign in required", "Sign in again to continue.");
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {
        write(response, request, HttpStatus.FORBIDDEN, "access-denied",
                "Not allowed", "Your role does not permit this action, or the site is not assigned to you.");
    }

    private void write(HttpServletResponse response, HttpServletRequest request,
                       HttpStatus status, String type, String title, String detail) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        ApiError body = ApiError.of(TYPE_BASE + type, title, status.value(), detail,
                request.getRequestURI(), MDC.get(CorrelationIdFilter.MDC_KEY));
        objectMapper.writeValue(response.getOutputStream(), body);
    }
}
