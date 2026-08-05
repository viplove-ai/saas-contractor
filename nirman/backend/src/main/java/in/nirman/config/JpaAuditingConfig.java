package in.nirman.config;

import in.nirman.security.CurrentUserProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.AuditorAware;

import java.util.Optional;
import java.util.UUID;

/** Supplies created_by and updated_by from the authenticated principal. */
@Configuration
public class JpaAuditingConfig {

    /**
     * Resolved through an ObjectProvider rather than injected directly, because
     * {@link CurrentUserProvider} has no implementation until Phase 2 wires it to the JWT
     * security context. A hard dependency stops the whole application context from starting,
     * which would leave Phase 1 unable to run at all.
     *
     * <p>Until then auditing yields an empty auditor and created_by / updated_by stay null,
     * which is honest: there is no authenticated user yet. The lookup happens per call, so
     * the real bean is picked up the moment Phase 2 defines one — nothing here changes.</p>
     */
    @Bean
    public AuditorAware<UUID> auditorAware(ObjectProvider<CurrentUserProvider> currentUserProvider) {
        return () -> Optional.ofNullable(currentUserProvider.getIfAvailable())
                .map(CurrentUserProvider::currentUserIdOrNull);
    }
}
