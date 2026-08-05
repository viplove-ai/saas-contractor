package in.nirman.modules.labour.repository;

import in.nirman.modules.labour.domain.WorkerBalance;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WorkerBalanceRepository extends JpaRepository<WorkerBalance, UUID> {

    /**
     * SELECT ... FOR UPDATE. Two ledger postings for the same worker must not read the same
     * starting total and then both write their own result, which is how a balance silently
     * loses one of them.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT b FROM WorkerBalance b WHERE b.workerId = :workerId")
    Optional<WorkerBalance> findForUpdate(@Param("workerId") UUID workerId);

    Optional<WorkerBalance> findByWorkerId(UUID workerId);

    List<WorkerBalance> findByWorkerIdIn(Collection<UUID> workerIds);
}
