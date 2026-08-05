package in.nirman.modules.labour.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Where a worker is posted, over time. The allocation open on a date decides whose roster
 * he appears on that morning.
 *
 * <p>The schema permits only one open allocation per worker ({@code uq_alloc_open}), which
 * is the rule that stops the same man being marked present at two sites on the same day —
 * the oldest and most expensive error in a paper muster roll.</p>
 *
 * <p>No {@code updated_at}: an allocation is opened and later closed, never otherwise
 * edited, so this cannot extend {@code BaseEntity}.</p>
 */
@Entity
@Table(name = "worker_site_allocations")
@EntityListeners(AuditingEntityListener.class)
public class WorkerSiteAllocation {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID orgId;

    @Column(name = "worker_id", nullable = false, updatable = false)
    private UUID workerId;

    @Column(name = "site_id", nullable = false, updatable = false)
    private UUID siteId;

    @Column(name = "effective_from", nullable = false)
    private LocalDate effectiveFrom;

    @Column(name = "effective_to")
    private LocalDate effectiveTo;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @CreatedBy
    @Column(name = "created_by", updatable = false)
    private UUID createdBy;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    protected WorkerSiteAllocation() {
    }

    public WorkerSiteAllocation(UUID orgId, UUID workerId, UUID siteId, LocalDate effectiveFrom) {
        this.id = UUID.randomUUID();
        this.orgId = orgId;
        this.workerId = workerId;
        this.siteId = siteId;
        this.effectiveFrom = effectiveFrom;
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrgId() {
        return orgId;
    }

    public UUID getWorkerId() {
        return workerId;
    }

    public UUID getSiteId() {
        return siteId;
    }

    public LocalDate getEffectiveFrom() {
        return effectiveFrom;
    }

    public LocalDate getEffectiveTo() {
        return effectiveTo;
    }

    public boolean isOpen() {
        return effectiveTo == null;
    }

    public boolean appliesOn(LocalDate date) {
        return !effectiveFrom.isAfter(date) && (effectiveTo == null || !effectiveTo.isBefore(date));
    }

    public void closeOn(LocalDate lastDay) {
        this.effectiveTo = lastDay;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Long getVersion() {
        return version;
    }
}
