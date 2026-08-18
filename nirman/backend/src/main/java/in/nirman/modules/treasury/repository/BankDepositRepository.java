package in.nirman.modules.treasury.repository;

import in.nirman.modules.treasury.domain.BankDeposit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BankDepositRepository extends JpaRepository<BankDeposit, UUID> {

    Optional<BankDeposit> findByIdAndOrgId(UUID id, UUID orgId);

    /**
     * The whole register, newest certificate first.
     *
     * <p>Unpaged for the reason the deposit register is: every figure on the screen is a total
     * over all of them, and a page would answer "what do we hold" with the first screenful. A
     * contractor accumulates a few dozen of these a year.</p>
     */
    List<BankDeposit> findByOrgIdOrderByIssuedOnDesc(UUID orgId);

    List<BankDeposit> findByIdInAndOrgId(Collection<UUID> ids, UUID orgId);

    /** The same certificate number twice would split the company's holdings into two figures. */
    Optional<BankDeposit> findByOrgIdAndDepositNumberIgnoreCase(UUID orgId, String depositNumber);
}
