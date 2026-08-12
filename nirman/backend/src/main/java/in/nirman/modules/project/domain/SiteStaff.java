package in.nirman.modules.project.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.UUID;

/**
 * One person named on one site, under one post.
 *
 * <p>A row rather than a column on {@code sites}, because a site is run by however many
 * people run it: a block split between a day and a night supervisor, or a job big enough
 * for two engineers, could not be described at all while the register held one name each.
 * The same person may hold both posts, which is the small job where the engineer supervises
 * his own site.</p>
 *
 * <p>Owned by the project module: this is the register saying who runs a site, which is a
 * project fact. What the guard reads is {@code user_site_assignments}, an identity fact, and
 * the sync between the two runs one way — see {@code SiteStaffing} and
 * {@code SitePostingGuard}.</p>
 */
@Entity
@Table(name = "site_staff")
@EntityListeners(AuditingEntityListener.class)
public class SiteStaff {

    /**
     * What somebody is called on a site, which is not the same as the role they hold.
     *
     * <p>A role says what a member may do anywhere in the company; a post says what they
     * are on this site. An administrator standing in for an absent engineer is posted
     * ENGINEER here and remains an ADMIN everywhere else.</p>
     */
    public enum Post { ENGINEER, SUPERVISOR }

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID orgId;

    @Column(name = "site_id", nullable = false, updatable = false)
    private UUID siteId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "post", nullable = false, length = 20, updatable = false)
    private Post post;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @CreatedBy
    @Column(name = "created_by", updatable = false)
    private UUID createdBy;

    protected SiteStaff() {
    }

    public SiteStaff(UUID orgId, UUID siteId, UUID userId, Post post) {
        this.id = UUID.randomUUID();
        this.orgId = orgId;
        this.siteId = siteId;
        this.userId = userId;
        this.post = post;
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrgId() {
        return orgId;
    }

    public UUID getSiteId() {
        return siteId;
    }

    public UUID getUserId() {
        return userId;
    }

    public Post getPost() {
        return post;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public UUID getCreatedBy() {
        return createdBy;
    }
}
