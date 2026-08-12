package in.nirman.security;

import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns a valid {@code Authorization: Bearer} header into a security context. An invalid
 * or absent token simply leaves the request anonymous — the authorization rules then
 * produce 401 through {@link RestAuthenticationEntryPoint}, so this filter never writes
 * a response body itself.
 *
 * <p>Authorities are the permission codes verbatim ({@code hasAuthority('user:write')})
 * plus {@code ROLE_}-prefixed role codes for the rare check that is about who someone is
 * rather than what they may do.</p>
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtTokenService tokenService;
    private final SessionEpochGuard sessionEpochGuard;

    public JwtAuthenticationFilter(JwtTokenService tokenService,
                                   SessionEpochGuard sessionEpochGuard) {
        this.tokenService = tokenService;
        this.sessionEpochGuard = sessionEpochGuard;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith(BEARER_PREFIX)) {
            try {
                AuthenticatedUser user = tokenService.parse(header.substring(BEARER_PREFIX.length()));
                // A valid signature is not a live session. A password reset, a password
                // change or a deactivation moves the account's counter on, and every token
                // stamped with the old one stops being a token here rather than whenever it
                // happens to expire.
                if (!sessionEpochGuard.isCurrent(user.userId(), user.sessionEpoch())) {
                    log.debug("Rejected bearer token: session ended for {}", user.userId());
                    SecurityContextHolder.clearContext();
                    chain.doFilter(request, response);
                    return;
                }
                List<GrantedAuthority> authorities = new ArrayList<>();
                user.permissions().forEach(p -> authorities.add(new SimpleGrantedAuthority(p)));
                user.roles().forEach(r -> authorities.add(new SimpleGrantedAuthority("ROLE_" + r)));
                UsernamePasswordAuthenticationToken authentication =
                        new UsernamePasswordAuthenticationToken(user, null, authorities);
                SecurityContextHolder.getContext().setAuthentication(authentication);
            } catch (JwtException | IllegalArgumentException e) {
                log.debug("Rejected bearer token: {}", e.getMessage());
                SecurityContextHolder.clearContext();
            }
        }
        chain.doFilter(request, response);
    }
}
