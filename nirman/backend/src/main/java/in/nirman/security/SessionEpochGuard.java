package in.nirman.security;

import java.util.UUID;

/**
 * Answers whether an access token still belongs to a live session.
 *
 * <p>Access tokens are signed and self-contained, which is what makes them cheap and is
 * also what makes them impossible to withdraw: revoking the refresh family stops the
 * <em>next</em> fifteen minutes, not this one. For most changes that lag is acceptable. For
 * the one that says "this account is in the wrong hands" it is not, and a password reset is
 * almost always that.</p>
 *
 * <p>So each token carries the counter its account held when it was issued, and this asks
 * the row whether the counter has moved on. It says no far more usefully than it says yes:
 * a matching counter means nothing has revoked the session, not that the session is
 * otherwise in good standing — the permission checks and {@link SiteAccessGuard} still run.</p>
 *
 * <p>Asked by JDBC rather than through the identity module's repositories, for the reason
 * {@link SiteAccessGuard} is: this runs on every authenticated request, ahead of any module,
 * and reaching into one from here is the dependency cycle the boundaries exist to prevent.</p>
 */
public interface SessionEpochGuard {

    /**
     * @return true when the token's stamp still matches the account's counter. False for a
     *         token issued before the last sign-out-everywhere, for one carrying no stamp
     *         at all, and for a user id that no longer exists.
     */
    boolean isCurrent(UUID userId, long sessionEpoch);
}
