package in.nirman.modules.expense.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

/**
 * One bill attached to a settlement.
 *
 * <p>A composite key of settlement and expense, so the same bill cannot be attached twice to
 * one settlement; {@code uq_ase_expense_settled_once} (V8) stops it being attached to two
 * different ones, which is the version that actually clears somebody's float twice.</p>
 */
@Entity
@Table(name = "advance_settlement_expenses")
public class AdvanceSettlementExpense {

    @Embeddable
    public record Key(
            @Column(name = "settlement_id", nullable = false) UUID settlementId,
            @Column(name = "expense_id", nullable = false) UUID expenseId)
            implements Serializable {
    }

    @EmbeddedId
    private Key id;

    @Column(name = "amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal amount;

    protected AdvanceSettlementExpense() {
    }

    public AdvanceSettlementExpense(UUID settlementId, UUID expenseId, BigDecimal amount) {
        this.id = new Key(settlementId, expenseId);
        this.amount = amount;
    }

    public UUID getSettlementId() {
        return id.settlementId();
    }

    public UUID getExpenseId() {
        return id.expenseId();
    }

    public BigDecimal getAmount() {
        return amount;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        return other instanceof AdvanceSettlementExpense that && Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}
