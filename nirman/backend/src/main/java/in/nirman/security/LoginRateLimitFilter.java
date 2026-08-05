package in.nirman.security;

import in.nirman.common.ApiError;
import in.nirman.common.CorrelationIdFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A ceiling on how fast one source may attempt to sign in.
 *
 * <p>This is not the account lockout, and the difference is the whole reason it exists.
 * {@code AuthService} locks an <i>account</i> after five bad passwords, which stops somebody
 * guessing at one person's password and does nothing at all about the attack that matters
 * more here: one bad password tried against every username in turn. Every one of those
 * attempts is the first failure on a different account, so the lockout never fires, and a
 * list of a contractor's staff is not a secret — it is the payroll.</p>
 *
 * <p>So the fence is per source address and it counts attempts rather than failures. Sixty
 * in five minutes is roughly ten times what a site full of supervisors signing in at seven
 * in the morning produces, and about a thousandth of what a spray needs to be worth
 * running.</p>
 *
 * <p><b>Per instance, and deliberately so.</b> A shared counter in Postgres or Redis would be
 * exact across a scaled-out deployment, at the cost of a round trip on the one endpoint that
 * is reachable without a token — which is the endpoint an attacker would then use to make
 * the round trips. With N instances the real ceiling is N times the limit, and for a system
 * whose whole user base is one contractor's staff, N is one or two. The comment is here so
 * the day it becomes four, this is a decision somebody revisits rather than a bug they
 * discover.</p>
 */
@Component
public class LoginRateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(LoginRateLimitFilter.class);
    private static final String LOGIN_PATH = "/api/v1/auth/login";
    private static final String REFRESH_PATH = "/api/v1/auth/refresh";

    /** Stops the map growing without bound if something starts cycling source addresses. */
    private static final int MAX_TRACKED_SOURCES = 10_000;

    private final Map<String, Deque<Long>> attempts = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper;
    private final int loginLimit;
    private final int refreshLimit;
    private final Duration window;

    public LoginRateLimitFilter(ObjectMapper objectMapper,
                                @Value("${app.security.login-attempts-per-window:60}") int loginLimit,
                                @Value("${app.security.refresh-attempts-per-window:600}") int refreshLimit,
                                @Value("${app.security.login-window-minutes:5}") int windowMinutes) {
        this.objectMapper = objectMapper;
        this.loginLimit = loginLimit;
        this.refreshLimit = refreshLimit;
        this.window = Duration.ofMinutes(windowMinutes);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !(LOGIN_PATH.equals(path) || REFRESH_PATH.equals(path));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String path = request.getRequestURI();
        String source = sourceOf(request);
        if (!allow(path + '|' + source, limitFor(path), System.currentTimeMillis())) {
            log.warn("Rate limit hit on {} from {}", path, source);
            reject(request, response);
            return;
        }
        chain.doFilter(request, response);
    }

    /**
     * Refresh gets an allowance an order of magnitude larger than login, because it is not
     * the same kind of request. A signing-in human types a password; a refresh is the app
     * rotating a token it already holds, once every fifteen minutes per open phone, and a
     * whole site behind one 4G gateway shares a single source address. Setting them equal
     * would mean a company of forty supervisors locking itself out over the course of a
     * morning while nothing was wrong.
     *
     * <p>Token theft on the refresh endpoint is not what this defends against and does not
     * need it to: refresh is single-use with rotation, and presenting a rotated token revokes
     * the whole family. A thief gets one use and is detected. A password guesser gets sixty
     * tries in five minutes and is not.</p>
     */
    private int limitFor(String path) {
        return REFRESH_PATH.equals(path) ? refreshLimit : loginLimit;
    }

    /** Records an attempt and says whether it may proceed. Public so a test can drive it. */
    public boolean allow(String key, int limit, long nowMillis) {
        if (attempts.size() > MAX_TRACKED_SOURCES) {
            attempts.clear();
        }
        Deque<Long> recent = attempts.computeIfAbsent(key, unused -> new ArrayDeque<>());
        synchronized (recent) {
            long cutoff = nowMillis - window.toMillis();
            for (Iterator<Long> it = recent.iterator(); it.hasNext(); ) {
                if (it.next() >= cutoff) {
                    break;   // the deque is in arrival order, so the first live one ends it
                }
                it.remove();
            }
            if (recent.size() >= limit) {
                return false;
            }
            recent.addLast(nowMillis);
            return true;
        }
    }

    /**
     * The proxy's idea of the caller when there is one, else the socket.
     *
     * <p>{@code X-Forwarded-For} is trusted here because in every deployment this runs in
     * there is a proxy in front terminating TLS — Fly's edge, or the nginx in front of the
     * frontend — and behind one, the socket address is the proxy for every caller alike,
     * which would make the limit a single global counter. The header is spoofable by anyone
     * who can reach the app directly, and that is worth saying plainly: this raises the cost
     * of a spray, it does not make one impossible.</p>
     */
    private static String sourceOf(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            String first = (comma > 0 ? forwarded.substring(0, comma) : forwarded).trim();
            if (!first.isEmpty() && first.length() <= 45) {   // an IPv6 literal at its longest
                return first;
            }
        }
        return request.getRemoteAddr();
    }

    private void reject(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        // Seconds rather than a timestamp: it is what the standard header carries and what a
        // client can act on without a clock the server agrees with.
        response.setHeader("Retry-After", String.valueOf(window.toSeconds()));
        ApiError body = ApiError.of("https://nirman/errors/rate-limited", "Too many attempts",
                HttpStatus.TOO_MANY_REQUESTS.value(),
                "Too many sign-in attempts from this connection. Wait a few minutes and try again.",
                request.getRequestURI(), MDC.get(CorrelationIdFilter.MDC_KEY));
        objectMapper.writeValue(response.getOutputStream(), body);
    }

    /** Test seam: forgets everything counted so far. */
    public void reset() {
        attempts.clear();
    }
}
