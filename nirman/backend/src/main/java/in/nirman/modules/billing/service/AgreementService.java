package in.nirman.modules.billing.service;

import in.nirman.common.BusinessException;
import in.nirman.modules.audit.AuditService;
import in.nirman.modules.billing.api.dto.BillingDtos.AgreementRequest;
import in.nirman.modules.billing.api.dto.BillingDtos.AgreementResponse;
import in.nirman.modules.billing.domain.Agreement;
import in.nirman.modules.billing.repository.AgreementRepository;
import in.nirman.security.CurrentUserProvider;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The tender's own details, asked once and standing for every bill of that tender.
 *
 * <p>Two kinds of thing live here and they are here for the same reason. The <b>names</b> —
 * contractor, the officer whose measurements the bill is based on, who prepared it, who
 * checked it, the division — print on every page of every bill and change only between
 * tenders. The <b>rate chain</b> — coefficient, cost index, tender percentage — is the
 * arithmetic that turns a published schedule rate into what this contract pays.</p>
 *
 * <p>Both were previously retyped into forty-three worksheets by hand, which is how the
 * workbook this was designed against came to say "3rd RA Bill" on its measurement pages,
 * "4th RA Bill" on its abstract, and to carry a sheet named MB1st RA. Asked once, at the
 * first bill of the tender, and never again.</p>
 */
@Service
@Transactional
public class AgreementService {

    private static final String ENTITY_TYPE = "AGREEMENT";

    /** What the chain does to a round thousand, so a screen can show the arithmetic. */
    private static final BigDecimal SAMPLE_RATE = new BigDecimal("1000");

    private final AgreementRepository agreements;
    private final CurrentUserProvider currentUser;
    private final AuditService audit;

    public AgreementService(AgreementRepository agreements, CurrentUserProvider currentUser,
                            AuditService audit) {
        this.agreements = agreements;
        this.currentUser = currentUser;
        this.audit = audit;
    }

    /**
     * Creates the agreement or amends it. One row per project, so this is an upsert rather
     * than a create — the screen that asks for these details is the same screen whether it is
     * the first bill or a correction three bills later.
     */
    @PreAuthorize("hasAuthority('billing:prepare')")
    public AgreementResponse save(UUID projectId, AgreementRequest request) {
        UUID orgId = currentUser.currentOrgId();
        Agreement agreement = agreements.findByOrgIdAndProjectId(orgId, projectId)
                .orElseGet(() -> new Agreement(orgId, projectId));
        boolean isNew = agreement.getVersion() == null;

        if (request.dsrCoefficient().signum() <= 0) {
            throw new BusinessException("billing.coefficient-must-be-positive",
                    "The coefficient factor multiplies every derived rate, so zero or less "
                            + "would price the work at nothing. It is 1 when the schedule rate "
                            + "is taken as printed.");
        }

        agreement.setAgreementNo(request.agreementNo());
        agreement.setDivision(request.division());
        agreement.setSubDivision(request.subDivision());
        agreement.setDsrScheduleId(request.dsrScheduleId());
        agreement.setDsrCoefficient(request.dsrCoefficient());
        agreement.setCostIndexPct(request.costIndexPct());
        agreement.setTenderPct(request.tenderPct());
        agreement.setDeviationLimitPct(request.deviationLimitPct());
        agreement.setContractorName(request.contractorName());
        agreement.setMeasuredByName(request.measuredByName());
        agreement.setMeasuredByDesignation(request.measuredByDesignation());
        agreement.setPreparedByName(request.preparedByName());
        agreement.setPreparedByDesignation(request.preparedByDesignation());
        agreement.setCheckedByName(request.checkedByName());
        agreement.setCheckedByDesignation(request.checkedByDesignation());
        agreement.setExecutiveEngineer(request.executiveEngineer());
        agreement.setCmbNo(request.cmbNo());
        agreement.setEstimatedCost(request.estimatedCost());
        agreement.setTenderedCost(request.tenderedCost());
        agreement.setDateOfStart(request.dateOfStart());
        agreement.setStipulatedCompletion(request.stipulatedCompletion());
        agreements.save(agreement);

        audit.record(ENTITY_TYPE, agreement.getId(), isNew ? "CREATE" : "UPDATE", null,
                Map.of("projectId", projectId.toString(),
                        "agreementNo", String.valueOf(request.agreementNo()),
                        "dsrCoefficient", request.dsrCoefficient(),
                        "costIndexPct", request.costIndexPct(),
                        "tenderPct", request.tenderPct()), null);
        return toResponse(agreement);
    }

    @PreAuthorize("hasAuthority('billing:read')")
    @Transactional(readOnly = true)
    public Optional<AgreementResponse> find(UUID projectId) {
        return agreements.findByOrgIdAndProjectId(currentUser.currentOrgId(), projectId)
                .map(AgreementService::toResponse);
    }

    private static AgreementResponse toResponse(Agreement a) {
        return new AgreementResponse(a.getId(), a.getProjectId(), a.getAgreementNo(),
                a.getDivision(), a.getSubDivision(), a.getDsrScheduleId(), a.getDsrCoefficient(),
                a.getCostIndexPct(), a.getTenderPct(), a.getDeviationLimitPct(),
                a.getContractorName(), a.getMeasuredByName(), a.getMeasuredByDesignation(),
                a.getPreparedByName(), a.getPreparedByDesignation(), a.getCheckedByName(),
                a.getCheckedByDesignation(), a.getExecutiveEngineer(), a.getCmbNo(),
                a.getEstimatedCost(), a.getTenderedCost(), a.getDateOfStart(),
                a.getStipulatedCompletion(), a.deriveRate(SAMPLE_RATE), a.getVersion());
    }
}
