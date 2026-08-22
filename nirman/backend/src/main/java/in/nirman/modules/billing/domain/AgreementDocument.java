package in.nirman.modules.billing.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Which edition a tender was priced under — a stored fact, not a lookup.
 *
 * <p>This is the whole point of the vault. "Whichever DSR is current" is the wrong question to
 * ask of a bill: an agreement let in 2025 is a DSR 2023 agreement for as long as it runs, and
 * a bill that repriced itself the day DSR 2026 was published would invent money. So the tender
 * points at the edition it cites, and publishing a newer one moves nothing.</p>
 *
 * <p>{@code workPart} exists because a tender ordinarily prices civil and electrical work under
 * different schedules — which is exactly why the NIT reader has always extracted a civil DSR
 * year and an electrical one separately.</p>
 */
@Entity
@Table(name = "agreement_documents")
public class AgreementDocument {

    /** What the document does for this tender. One tender cites several. */
    public enum Role { SCHEDULE_OF_RATES, COST_INDEX, SPECIFICATION, OTHER }

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "agreement_id", nullable = false, updatable = false)
    private UUID agreementId;

    @Column(name = "document_id", nullable = false, updatable = false)
    private UUID documentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 30)
    private Role role = Role.SCHEDULE_OF_RATES;

    @Column(name = "work_part", length = 40)
    private String workPart;

    @Column(name = "notes")
    private String notes;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected AgreementDocument() {
    }

    public AgreementDocument(UUID agreementId, UUID documentId, Role role, String workPart) {
        this.id = UUID.randomUUID();
        this.agreementId = agreementId;
        this.documentId = documentId;
        this.role = role == null ? Role.SCHEDULE_OF_RATES : role;
        this.workPart = workPart;
    }

    public UUID getId() {
        return id;
    }

    public UUID getAgreementId() {
        return agreementId;
    }

    public UUID getDocumentId() {
        return documentId;
    }

    public Role getRole() {
        return role;
    }

    public String getWorkPart() {
        return workPart;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
