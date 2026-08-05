package in.nirman.modules.identity.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Grants a user a window of access to a site. Owned by identity (it is an authorisation
 * fact, not a project fact); {@code SiteAccessGuard} re-validates the JWT sites claim
 * against these rows on every site-scoped call.
 *
 * <p>Access is withdrawn by closing {@code assignedTo}, never by deleting the row — an old
 * assignment explains why last month's records carry that supervisor's name.</p>
 */
@Entity
@Table(name = "user_site_assignments")
@EntityListeners(AuditingEntityListener.class)
public class UserSiteAssignment {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID orgId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "site_id", nullable = false, updatable = false)
    private UUID siteId;

    @Column(name = "assigned_from", nullable = false)
    private LocalDate assignedFrom;

    @Column(name = "assigned_to")
    private LocalDate assignedTo;

    @Column(name = "is_primary", nullable = false)
    private boolean primary;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @CreatedBy
    @Column(name = "created_by", updatable = false)
    private UUID createdBy;

    protected UserSiteAssignment() {
    }

    public UserSiteAssignment(UUID orgId, UUID userId, UUID siteId, LocalDate assignedFrom,
                              boolean primary) {
        this.id = UUID.randomUUID();
        this.orgId = orgId;
        this.userId = userId;
        this.siteId = siteId;
        this.assignedFrom = assignedFrom;
        this.primary = primary;
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrgId() {
        return orgId;
    }

    public UUID getUserId() {
        return userId;
    }

    public UUID getSiteId() {
        return siteId;
    }

    public LocalDate getAssignedFrom() {
        return assignedFrom;
    }

    public LocalDate getAssignedTo() {
        return assignedTo;
    }

    public boolean isPrimary() {
        return primary;
    }

    public boolean isActiveOn(LocalDate date) {
        return !assignedFrom.isAfter(date) && (assignedTo == null || !assignedTo.isBefore(date));
    }

    /**
     * Withdraws access, from now rather than from the end of today.
     *
     * <p>The stored end date is the day before {@code today} because both readers of this
     * window — {@link #isActiveOn} and the guard's SQL — treat the end date as inclusive.
     * Storing today would leave the posting live until midnight, and an admin who removes
     * someone from a site means it to stop, not to stop tonight.</p>
     *
     * <p>The row survives, so the window still says who held the site and until when; an
     * assignment granted and withdrawn on the same day reads as never held, which is what
     * a corrected mistake should look like.</p>
     */
    public void revoke(LocalDate today) {
        this.assignedTo = today.minusDays(1);
    }

    /** Re-grants access on a closed assignment; one row per user and site is the schema rule. */
    public void reopen() {
        this.assignedTo = null;
    }
}
