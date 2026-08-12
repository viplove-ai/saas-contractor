package in.nirman.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Issues and parses the short-lived access token. Claims: {@code sub} (user id),
 * {@code org}, {@code roles}, {@code perms} and {@code sites} — either a list of site ids
 * or the literal {@code ALL} for roles that are not site-scoped.
 *
 * <p>Refresh tokens are not JWTs and never pass through here; they are opaque values
 * handled by the identity module.</p>
 */
@Service
public class JwtTokenService {

    static final String CLAIM_ORG = "org";
    static final String CLAIM_ROLES = "roles";
    static final String CLAIM_PERMISSIONS = "perms";
    static final String CLAIM_SITES = "sites";
    static final String CLAIM_SESSION_EPOCH = "sep";
    static final String ALL_SITES = "ALL";

    private final JwtProperties properties;
    private final SecretKey key;

    public JwtTokenService(JwtProperties properties) {
        this.properties = properties;
        this.key = Keys.hmacShaKeyFor(properties.secret().getBytes(StandardCharsets.UTF_8));
    }

    public String createAccessToken(AuthenticatedUser user) {
        Instant now = Instant.now();
        Object sites = user.allSites()
                ? ALL_SITES
                : user.siteIds().stream().map(UUID::toString).toList();
        return Jwts.builder()
                .subject(user.userId().toString())
                .issuer(properties.issuer())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(Duration.ofMinutes(properties.accessTokenMinutes()))))
                .claim(CLAIM_ORG, user.orgId().toString())
                .claim("username", user.username())
                .claim(CLAIM_ROLES, List.copyOf(user.roles()))
                .claim(CLAIM_PERMISSIONS, List.copyOf(user.permissions()))
                .claim(CLAIM_SITES, sites)
                .claim(CLAIM_SESSION_EPOCH, user.sessionEpoch())
                .signWith(key)
                .compact();
    }

    public long accessTokenSeconds() {
        return Duration.ofMinutes(properties.accessTokenMinutes()).toSeconds();
    }

    /** @throws JwtException when the token is forged, malformed or expired. */
    public AuthenticatedUser parse(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(key)
                .requireIssuer(properties.issuer())
                .build()
                .parseSignedClaims(token)
                .getPayload();

        Set<String> roles = stringSet(claims.get(CLAIM_ROLES));
        Set<String> permissions = stringSet(claims.get(CLAIM_PERMISSIONS));

        Object sitesClaim = claims.get(CLAIM_SITES);
        boolean allSites = ALL_SITES.equals(sitesClaim);
        Set<UUID> siteIds = allSites
                ? Set.of()
                : stringSet(sitesClaim).stream().map(UUID::fromString).collect(Collectors.toSet());

        return new AuthenticatedUser(
                UUID.fromString(claims.getSubject()),
                UUID.fromString(claims.get(CLAIM_ORG, String.class)),
                claims.get("username", String.class),
                roles,
                permissions,
                siteIds,
                allSites,
                sessionEpoch(claims));
    }

    /**
     * A token minted before the claim existed reads as {@code NO_SESSION_EPOCH} and is
     * refused. That signs everybody out once, on the deploy that adds this — which is the
     * safe direction: the alternative is honouring tokens that nothing can withdraw.
     */
    private static long sessionEpoch(Claims claims) {
        Object claim = claims.get(CLAIM_SESSION_EPOCH);
        return claim instanceof Number number
                ? number.longValue()
                : AuthenticatedUser.NO_SESSION_EPOCH;
    }

    private static Set<String> stringSet(Object claim) {
        if (claim instanceof List<?> list) {
            return list.stream().map(String::valueOf).collect(Collectors.toUnmodifiableSet());
        }
        return Set.of();
    }
}
