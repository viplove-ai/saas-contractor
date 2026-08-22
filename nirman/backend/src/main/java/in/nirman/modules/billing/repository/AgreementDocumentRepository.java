package in.nirman.modules.billing.repository;

import in.nirman.modules.billing.domain.AgreementDocument;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface AgreementDocumentRepository extends JpaRepository<AgreementDocument, UUID> {

    List<AgreementDocument> findByAgreementIdOrderByRoleAsc(UUID agreementId);

    void deleteByAgreementId(UUID agreementId);

    /** Whether any tender still cites an edition — asked before it is withdrawn. */
    long countByDocumentId(UUID documentId);
}
