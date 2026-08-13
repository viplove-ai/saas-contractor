package in.nirman.modules.tender.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * The Clause 7 threshold for one work part: gross work that must exist before a running account
 * bill may be raised.
 *
 * <p>Its own row rather than a column because a composite notice states it twice, once for civil
 * and once for E&amp;M, and the two bill on separate rhythms. {@link #getWorkPart()} is null on a
 * notice that states a single figure, meaning the whole contract.</p>
 */
@Entity
@Table(name = "nit_interim_minimums")
public class NitInterimMinimum {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "nit_document_id", nullable = false, updatable = false)
    private UUID nitDocumentId;

    @Column(name = "work_part", length = 40)
    private String workPart;

    @Column(name = "amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal amount;

    protected NitInterimMinimum() {
    }

    public NitInterimMinimum(UUID nitDocumentId, String workPart, BigDecimal amount) {
        this.id = UUID.randomUUID();
        this.nitDocumentId = nitDocumentId;
        this.workPart = workPart;
        this.amount = amount;
    }

    public UUID getId() {
        return id;
    }

    public UUID getNitDocumentId() {
        return nitDocumentId;
    }

    public String getWorkPart() {
        return workPart;
    }

    public BigDecimal getAmount() {
        return amount;
    }
}
