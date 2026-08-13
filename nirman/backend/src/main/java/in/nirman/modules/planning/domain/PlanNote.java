package in.nirman.modules.planning.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * An assumption the plan had to make, or a finding its reader has to act on.
 *
 * <p>Not documentation. This is what makes a plan auditable six months later when the question
 * is <i>why did it say we needed forty lakh</i>, and what lets a re-plan on better norms be
 * compared with the old one rather than merely replacing it. Both kinds share a table because
 * they are read as one block — the honest part of the output — and never queried apart.</p>
 */
@Entity
@Table(name = "plan_assumptions")
public class PlanNote {

    public static final String ASSUMPTION = "ASSUMPTION";
    public static final String FINDING = "FINDING";

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;
    @Column(name = "plan_id", nullable = false, updatable = false)
    private UUID planId;
    @Column(name = "kind", nullable = false, length = 20)
    private String kind;
    /** BLOCKING, WARNING or NOTE for a finding; null for an assumption. */
    @Column(name = "severity", length = 20)
    private String severity;
    @Column(name = "subject", length = 200)
    private String subject;
    @Column(name = "value", length = 200)
    private String value;
    @Column(name = "message", nullable = false)
    private String message;
    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    protected PlanNote() {
    }

    public PlanNote(UUID planId, String kind, String severity, String subject, String value,
                    String message, int sortOrder) {
        this.id = UUID.randomUUID();
        this.planId = planId;
        this.kind = kind;
        this.severity = severity;
        this.subject = subject;
        this.value = value;
        this.message = message;
        this.sortOrder = sortOrder;
    }

    public UUID getId() { return id; }
    public UUID getPlanId() { return planId; }
    public String getKind() { return kind; }
    public String getSeverity() { return severity; }
    public String getSubject() { return subject; }
    public String getValue() { return value; }
    public String getMessage() { return message; }
    public int getSortOrder() { return sortOrder; }
}
