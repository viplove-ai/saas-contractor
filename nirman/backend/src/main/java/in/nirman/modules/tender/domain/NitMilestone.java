package in.nirman.modules.tender.domain;

import in.nirman.modules.tender.parser.AllowedTime;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One row of the tender's table of milestones, as stored.
 *
 * <p>Written once with the document it belongs to and never edited — a corrigendum replaces the
 * whole extraction rather than amending it, which is what keeps "the milestones this project
 * runs under" a question with one answer.</p>
 *
 * <p>The percentages are both nullable and the reason matters. A milestone may state a share of
 * the tendered value, or name the work it expects finished, or both joined by <i>or</i>. Storing
 * only a number would throw away the descriptions, and those are what let a plan adopt the
 * department's own phasing instead of inventing one beside it.</p>
 */
@Entity
@Table(name = "nit_milestones")
public class NitMilestone {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "nit_document_id", nullable = false, updatable = false)
    private UUID nitDocumentId;

    @Column(name = "sequence_no", nullable = false)
    private int sequenceNo;

    @Column(name = "description", nullable = false)
    private String description;

    @Column(name = "time_allowed_value")
    private Integer timeAllowedValue;

    @Enumerated(EnumType.STRING)
    @Column(name = "time_allowed_unit", length = 10)
    private AllowedTime.Unit timeAllowedUnit;

    @Column(name = "financial_percent", precision = 6, scale = 3)
    private BigDecimal financialPercent;

    @Column(name = "withheld_percent", precision = 6, scale = 3)
    private BigDecimal withheldPercent;

    @Column(name = "physical", nullable = false)
    private boolean physical;

    protected NitMilestone() {
    }

    public NitMilestone(UUID nitDocumentId, int sequenceNo, String description,
                        AllowedTime timeAllowed, BigDecimal financialPercent,
                        BigDecimal withheldPercent, boolean physical) {
        this.id = UUID.randomUUID();
        this.nitDocumentId = nitDocumentId;
        this.sequenceNo = sequenceNo;
        this.description = description;
        this.timeAllowedValue = timeAllowed == null ? null : timeAllowed.value();
        this.timeAllowedUnit = timeAllowed == null ? null : timeAllowed.unit();
        this.financialPercent = financialPercent;
        this.withheldPercent = withheldPercent;
        this.physical = physical;
    }

    /** @return null when the notice's wording defeated the reader, never a guessed zero */
    public AllowedTime getTimeAllowed() {
        return timeAllowedValue == null || timeAllowedUnit == null
                ? null : new AllowedTime(timeAllowedValue, timeAllowedUnit);
    }

    public UUID getId() {
        return id;
    }

    public UUID getNitDocumentId() {
        return nitDocumentId;
    }

    public int getSequenceNo() {
        return sequenceNo;
    }

    public String getDescription() {
        return description;
    }

    public BigDecimal getFinancialPercent() {
        return financialPercent;
    }

    public BigDecimal getWithheldPercent() {
        return withheldPercent;
    }

    public boolean isPhysical() {
        return physical;
    }
}
